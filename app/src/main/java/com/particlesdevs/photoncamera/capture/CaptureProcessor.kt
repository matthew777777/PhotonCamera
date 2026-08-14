package com.particlesdevs.photoncamera.capture

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.media.ImageReader
import com.particlesdevs.photoncamera.api.CameraMode
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.control.GyroBurst
import com.particlesdevs.photoncamera.processing.ImageFrame
import com.particlesdevs.photoncamera.processing.ImageSaver
import com.particlesdevs.photoncamera.processing.ImageSaverSelector
import com.particlesdevs.photoncamera.processing.SaverImplementation
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex
import com.particlesdevs.photoncamera.processing.parameters.FrameNumberSelector
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector
import com.particlesdevs.photoncamera.ui.camera.viewmodel.TimerFrameCountViewModel
import com.particlesdevs.photoncamera.util.Allocator
import com.particlesdevs.photoncamera.util.Log
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modern component extracted from CaptureController to handle ImageReader results
 * and coordinate processing.
 */
@Singleton
class CaptureProcessor @Inject constructor(
    private val lifecycleManager: CameraLifecycleManager
) {
    private val TAG = "CaptureProcessor"
    
    private val zslRingBuffer = ArrayDeque<Image>()
    private val zslBufferLock = Any()
    
    @Volatile
    var isZslCapturing = false
        private set

    var imageSaver: ImageSaver? = null
    
    val yuvImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        imageSaver?.initProcess(reader)
    }

    val rawImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        if (isZslMode()) {
            val img: Image?
            try {
                img = reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to acquire next image: ${e.message}")
                return@OnImageAvailableListener
            }
            if (img == null) return@OnImageAvailableListener
            
            if (isZslCapturing) {
                img.close()
                return@OnImageAvailableListener
            }
            
            synchronized(zslBufferLock) {
                zslRingBuffer.addLast(img)
                val maxFrames = Math.min(PhotonCamera.getSettings().frameCount, 37)
                val safeMax = Math.min(maxFrames, reader.maxImages - 1)
                while (zslRingBuffer.size > safeMax) {
                    val old = zslRingBuffer.pollFirst()
                    old?.close()
                }
            }
            return@OnImageAvailableListener
        }
        
        imageSaver?.initProcess(reader)
    }

    fun isZslMode(): Boolean {
        return PhotonCamera.getSettings().selectedMode == CameraMode.MOTION &&
                !IsoExpoSelector.HDR
    }

    fun triggerZslCapture(
        controller: CaptureController,
        characteristics: CameraCharacteristics,
        previewResult: CaptureResult?,
        sensorOrientation: Int
    ) {
        if (isZslCapturing || CaptureController.isProcessing) {
            Log.w(TAG, "ZSL: capture already in progress, ignoring")
            return
        }
        isZslCapturing = true
        CaptureController.burst = false

        val frameCount = FrameNumberSelector.getFrames()
        val cameraRotation = PhotonCamera.getGravity().getCameraRotation(sensorOrientation)
        val burstShakiness = ArrayList<GyroBurst>()
        val exposures = HashMap<Long, Double>()

        val rawImages: List<Image>
        synchronized(zslBufferLock) {
            rawImages = ArrayList(zslRingBuffer)
            zslRingBuffer.clear()
        }

        val take = Math.min(rawImages.size, frameCount)
        val skip = rawImages.size - take
        for (i in 0 until skip) {
            rawImages[i].close()
        }

        var previewExpTime = 1.0
        var previewISO = 100.0
        var exposureTimeNs = 0L
        if (previewResult != null) {
            val expTimeNs = previewResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val isoVal = previewResult.get(CaptureResult.SENSOR_SENSITIVITY)
            if (expTimeNs != null) {
                exposureTimeNs = expTimeNs
                previewExpTime = expTimeNs / 1_000_000_000.0
            }
            if (isoVal != null) previewISO = isoVal.toDouble()
        }
        val exposureVal = previewExpTime * previewISO

        val selected = ArrayList<ImageFrame>()
        for (i in skip until rawImages.size) {
            val img = rawImages[i]
            val rowStride = img.planes[0].rowStride
            val pixelStride = img.planes[0].pixelStride
            val width = if (img.format == ImageFormat.RAW_SENSOR) img.width 
                        else if (pixelStride > 0) rowStride / pixelStride 
                        else img.width
            var height = img.height
            val bufCapacity = img.planes[0].buffer.capacity()
            var offset = 0
            
            if (PhotonCamera.getSettings().aspect169 && width > height) {
                height = width * 9 / 16
                val offsetH = (img.height - height) / 2
                val finalOffsetH = offsetH - (offsetH % 2)
                offset = rowStride * finalOffsetH
            }
            
            Allocator.binning = PhotonCamera.getSettings().binning
            val frame = ImageFrame(img.planes[0].buffer, img.format, width, rowStride, offset, bufCapacity)
            frame.timestamp = img.timestamp
            frame.width = width
            frame.height = height
            
            if (PhotonCamera.getSettings().binning) {
                frame.width /= 2
                frame.height /= 2
            }
            
            img.close()
            exposures[frame.timestamp] = exposureVal
            selected.add(frame)
        }
        
        val actualCount = selected.size
        val saver = ImageSaver(controller.cameraEventsListener)
        imageSaver = saver
        controller.mImageSaver = saver
        saver.setFrameCount(actualCount)
        saver.setImageFormat(CaptureController.RAW_FORMAT)
        saver.implementation = ImageSaverSelector.getImageSaver(CaptureController.RAW_FORMAT, saver.implementation)
        saver.implementation.frameCount = actualCount

        SaverImplementation.IMAGE_BUFFER.clear()
        SaverImplementation.IMAGE_BUFFER.addAll(selected)

        CaptureController.mCaptureResult = previewResult
        controller.mMeasuredFrameCnt = actualCount

        controller.cameraEventsListener.onFrameCountSet(actualCount)
        controller.cameraEventsListener.onCaptureStillPictureStarted("ZSLCaptureStarted!")
        controller.cameraEventsListener.onBurstPrepared(null)
        
        val frametime = ExposureIndex.time2sec(IsoExpoSelector.GenerateExpoPair(-1, controller).exposure)
        for (i in 0 until actualCount) {
            controller.cameraEventsListener.onFrameCaptureStarted(null)
            controller.cameraEventsListener.onFrameCaptureCompleted(
                TimerFrameCountViewModel.FrameCntTime(i, actualCount, frametime)
            )
        }
        controller.cameraEventsListener.onCaptureSequenceCompleted(null)

        val frameTimestamps = LongArray(actualCount)
        for (i in 0 until actualCount) {
            frameTimestamps[i] = selected[i].timestamp
        }
        PhotonCamera.getGyro().buildZslBurstShakiness(frameTimestamps, exposureTimeNs, burstShakiness)

        IsoExpoSelector.fullpairs.clear()
        for (i in 0 until actualCount) {
            IsoExpoSelector.fullpairs.add(IsoExpoSelector.GenerateExpoPair(i, controller))
        }

        controller.processExecutor.execute {
            try {
                PhotonCamera.getGyro().CompleteSequence()
                lifecycleManager.backgroundHandler.post(controller::unlockFocus)
                if (actualCount == 0) {
                    Log.w(TAG, "ZSL ring buffer was empty, no frames to process")
                    controller.cameraEventsListener.onProcessingFinished("ZSL buffer empty")
                    return@execute
                }
                saver.implementation.bufferLock = false
                saver.updateFrameCount(actualCount)
                saver.runRaw(
                    characteristics, 
                    previewResult, 
                    CaptureController.mPreviewCaptureRequest,
                    ArrayList(burstShakiness), 
                    controller.cameraRotation, 
                    exposures, 
                    ArrayList(controller.mCaptureResults)
                )
            } catch (e: Exception) {
                Log.e(TAG, "ZSL runRaw error: ${Log.getStackTraceString(e)}")
                controller.cameraEventsListener.onProcessingError(e.localizedMessage)
            } finally {
                isZslCapturing = false
            }
        }
    }

    fun clearZslBuffer() {
        synchronized(zslBufferLock) {
            for (img in zslRingBuffer) {
                img.close()
            }
            zslRingBuffer.clear()
        }
    }
}
