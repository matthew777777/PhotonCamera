package com.particlesdevs.photoncamera.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.particlesdevs.photoncamera.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the opening, closing, and lifecycle of the CameraDevice.
 */
@Singleton
class CameraLifecycleManager @Inject constructor() {
    private val TAG = "CameraLifecycleManager"
    
    private val cameraOpenCloseLock = Semaphore(1)
    
    var cameraDevice: CameraDevice? = null
        private set
        
    private var backgroundThread: HandlerThread? = null
    private var _backgroundHandler: Handler? = null

    val backgroundHandler: Handler
        get() {
            if (_backgroundHandler == null) {
                Log.w(TAG, "backgroundHandler requested while null, starting thread now")
                startBackgroundThread()
            }
            return _backgroundHandler!!
        }

    fun openCamera(activity: Activity, cameraId: String, stateCallback: CameraDevice.StateCallback) {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            startBackgroundThread()
            manager.openCamera(cameraId, stateCallback, _backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera: ", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraDevice?.close()
            cameraDevice = null
            // We keep the thread alive until app termination or explicit stop to avoid race conditions
            // stopBackgroundThread() 
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun onCameraOpened(device: CameraDevice) {
        cameraDevice = device
    }

    @Synchronized
    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply { start() }
            _backgroundHandler = Handler(backgroundThread!!.looper)
            Log.d(TAG, "startBackgroundThread(): CameraBackground thread started")
        }
    }

    @Synchronized
    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            _backgroundHandler = null
            Log.d(TAG, "stopBackgroundThread(): CameraBackground thread stopped")
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread: ", e)
        }
    }
}
