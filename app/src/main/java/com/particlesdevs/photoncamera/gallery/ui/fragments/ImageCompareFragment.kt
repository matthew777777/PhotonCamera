package com.particlesdevs.photoncamera.gallery.ui.fragments

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.net.Uri
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ToggleButton
import androidx.core.content.FileProvider
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.particlesdevs.photoncamera.R
import com.particlesdevs.photoncamera.databinding.FragmentGalleryImageCompareBinding
import com.particlesdevs.photoncamera.gallery.compare.SSIVListener
import com.particlesdevs.photoncamera.gallery.compare.ScaleAndPan
import com.particlesdevs.photoncamera.gallery.helper.Constants.*
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLConnection
import java.nio.file.Files
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ImageCompareFragment : Fragment() {
    private var toSync = true
    private val ssivListener = SSIVListenerImpl()
    private val fragment1 = ImageViewerFragment()
    private val fragment2 = ImageViewerFragment()
    private lateinit var imagesDir: File
    private var binding: FragmentGalleryImageCompareBinding? = null
    private lateinit var mContext: Context
    
    private val shareHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.obj is Uri) shareUri(msg.obj as Uri)
        hideButtons(false)
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = true
        mContext = requireContext()
        createDir()
    }

    private fun createDir() {
        imagesDir = File(mContext.cacheDir, "images")
        imagesDir.mkdirs()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_gallery_image_compare, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = arguments
        if (b != null) {
            toSync = true
            binding?.onSyncClick = View.OnClickListener { onSyncClick(it) }
            binding?.onShare = View.OnClickListener { onShareClick(it) }
            val trans = childFragmentManager.beginTransaction()

            val b1 = Bundle().apply {
                putString(MODE_KEY, COMPARE)
                putInt(IMAGE_POSITION_KEY, b.getInt(IMAGE1_KEY))
            }
            fragment1.arguments = b1
            fragment1.setSsivListener(ssivListener)
            trans.add(R.id.image_container1, fragment1, "image_container1")

            val b2 = Bundle().apply {
                putString(MODE_KEY, COMPARE)
                putInt(IMAGE_POSITION_KEY, b.getInt(IMAGE2_KEY))
            }
            fragment2.arguments = b2
            fragment2.setSsivListener(ssivListener)
            trans.add(R.id.image_container2, fragment2, "image_container2")

            trans.commit()
            
            // Start observing state flow only after views are created
            ssivListener.startObserving()
        }
    }

    private fun onSyncClick(view: View) {
        toSync = (view as ToggleButton).isChecked
    }

    private fun onShareClick(view: View) {
        hideButtons(true)
        val bmpThread = HandlerThread("ScreenshotThread", Process.THREAD_PRIORITY_BACKGROUND)
        bmpThread.start()
        Handler(bmpThread.looper).post {
            shareHandler.obtainMessage(0, saveBitmap(screenShot(binding!!.root))).sendToTarget()
        }
        bmpThread.quitSafely()
    }

    private fun shareUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = URLConnection.guessContentTypeFromName(uri.toString())
            clipData = ClipData.newUri(mContext.contentResolver, "", uri)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun hideButtons(toHide: Boolean) {
        binding?.hideButtons = toHide
    }

    private fun saveBitmap(bitmap: Bitmap): Uri? {
        var uri: Uri? = null
        try {
            val file = File(imagesDir, "compare_screenshot.jpg")
            val stream = Files.newOutputStream(file.toPath())
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            stream.flush()
            stream.close()
            uri = FileProvider.getUriForFile(mContext, mContext.packageName + ".provider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(mContext, "Failed!", Toast.LENGTH_SHORT).show()
        }
        return uri
    }

    private fun screenShot(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        childFragmentManager.beginTransaction().remove(fragment1).remove(fragment2).commitAllowingStateLoss()
        binding = null
    }

    private inner class SSIVListenerImpl : SSIVListener() {
        private val scaleAndPan = ScaleAndPan()
        private var idTouched = 0

        fun startObserving() {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    scaleAndPan.stateFlow.collect { state ->
                        if (toSync) {
                            val v1 = fragment1.currentSSIV
                            val v2 = fragment2.currentSSIV
                            if (v1 != null && v2 != null) {
                                copyZoomPan(v1, v2, state)
                            }
                        }
                        if (fragment1.isAdded) fragment1.updateScaleText()
                        if (fragment2.isAdded) fragment2.updateScaleText()
                    }
                }
            }
        }

        override fun onScaleChanged(newScale: Float, origin: Int) {
            scaleAndPan.origin = origin
            scaleAndPan.scale = newScale
        }

        override fun onCenterChanged(newCenter: PointF, origin: Int) {
            scaleAndPan.origin = origin
            scaleAndPan.center = newCenter
        }

        override fun onTouched(id: Int) {
            idTouched = id
        }

        private fun copyZoomPan(v1: SubsamplingScaleImageView, v2: SubsamplingScaleImageView, state: ScaleAndPan.State) {
            if (v1.id == idTouched) state.center?.let { v2.setScaleAndCenter(state.scale, it) }
            if (v2.id == idTouched) state.center?.let { v1.setScaleAndCenter(state.scale, it) }
        }
    }
}
