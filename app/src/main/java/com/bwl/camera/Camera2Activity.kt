package com.bwl.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity

/**
 * Created by baiwenlong on 2020/7/16
 */
class Camera2Activity : AppCompatActivity() {

    companion object {
        private const val REQUIRED_SUPPORTED_HARDWARE_LEVEL =
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        private const val MSG_OPEN_CAMERA = 0
        private const val MSG_CLOSE_CAMERA = 1
        private const val MSG_SET_PREVIEW_SIZE = 2
        private const val MSG_CREATE_CAPTURE_SESSION = 3
        private const val MSG_CREATE_CAPTURE_REQUEST = 4
        private const val MSG_START_PREVIEW = 5
        private const val MSG_STOP_PREVIEW = 6
    }

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private var cameraDevice: CameraDevice? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest? = null

    private val contentLayout by lazy {
        findViewById<View>(R.id.content_layout)
    }
    private val cameraPreview by lazy {
        findViewById<TextureView>(R.id.camera_preview)
    }
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null


    private var frontCameraId: String? = null
    private var frontCameraCharacteristics: CameraCharacteristics? = null
    private var backCameraId: String? = null
    private var backCameraCharacteristics: CameraCharacteristics? = null
    private var isWaitingSetPreviewSizeToCreateCaptureSession = false

    private val cameraHandlerThread = HandlerThread("camera").also {
        it.start()
    }

    private data class OpenCameraMessage(
        val cameraId: String,
        val cameraStateCallback: CameraStateCallback
    )

    private val cameraHandler = object : Handler(cameraHandlerThread.looper) {

        private fun msgIdToText(msgId: Int): String {
            return when (msgId) {
                MSG_OPEN_CAMERA -> "open camera"
                MSG_CLOSE_CAMERA -> "close camera"
                MSG_SET_PREVIEW_SIZE -> "set preview size"
                MSG_CREATE_CAPTURE_SESSION -> "create capture session"
                MSG_CREATE_CAPTURE_REQUEST -> "create capture request"
                MSG_START_PREVIEW -> "start preview"
                MSG_STOP_PREVIEW -> "stop review"
                else -> "unknown: $msgId"
            }
        }

        @SuppressLint("MissingPermission")
        override fun handleMessage(msg: Message) {
            if (isDestroyed) {
                return
            }
            val startTime = SystemClock.elapsedRealtime()
            when (msg.what) {
                MSG_OPEN_CAMERA -> {
                    isWaitingSetPreviewSizeToCreateCaptureSession = false
                    val openCameraMessage = msg.obj as OpenCameraMessage
                    val cameraId = openCameraMessage.cameraId
                    val cameraStateCallback = openCameraMessage.cameraStateCallback
                    cameraManager.openCamera(cameraId, cameraStateCallback, this)
                }
                MSG_CLOSE_CAMERA -> {
                    cameraDevice?.close()
                    cameraDevice = null
                }
                MSG_SET_PREVIEW_SIZE -> {
                    val previewSize = getOptimalSize(
                        cameraCharacteristics!!,
                        SurfaceTexture::class.java,
                        contentLayout.width,
                        contentLayout.height
                    )?: return
                    // 更新TextureView的高度，以适配预览比例
                    cameraPreview.post {
                        val lp = cameraPreview.layoutParams
                        lp.height = (previewSize.height.toFloat() / previewSize.width * cameraPreview.width).toInt()
                        if (lp.height > contentLayout.height) {
                            lp.height = contentLayout.height
                            lp.width = (previewSize.width.toFloat() / previewSize.height * cameraPreview.height).toInt()
                        }
                        cameraPreview.layoutParams = lp
                    }

                    previewSurfaceTexture?.setDefaultBufferSize(
                        previewSize.width,
                        cameraPreview.height
                    )
                    previewSurface = Surface(previewSurfaceTexture)
                    if (isWaitingSetPreviewSizeToCreateCaptureSession) {
                        isWaitingSetPreviewSizeToCreateCaptureSession = false
                        sendEmptyMessage(MSG_CREATE_CAPTURE_SESSION)
                    }
                }
                MSG_CREATE_CAPTURE_SESSION -> {
                    if (previewSurface == null) {
                        isWaitingSetPreviewSizeToCreateCaptureSession = true
                    } else {
                        val sessionStateCallback = SessionStateCallback()
                        val outputs = listOf(previewSurface)
                        cameraDevice?.createCaptureSession(outputs, sessionStateCallback, mainHandler)
                    }
                }
                MSG_CREATE_CAPTURE_REQUEST -> {
                    val requestBuilder =
                        cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
                    requestBuilder.addTarget(previewSurface!!)
                    captureRequest = requestBuilder.build()
                    sendEmptyMessage(MSG_START_PREVIEW)
                }
                MSG_START_PREVIEW -> {
                    captureSession?.setRepeatingRequest(
                        captureRequest!!,
                        RepeatingCaptureStateCallback(),
                        mainHandler
                    )
                }
                MSG_STOP_PREVIEW -> {
                    captureSession?.stopRepeating()
                }
            }
            val endTime = SystemClock.elapsedRealtime()
            Log.d("bwl", "${msgIdToText(msg.what)} used time: ${endTime - startTime}ms")
        }
    }
    private val mainHandler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera2)
        val cameraIdList = cameraManager.cameraIdList
        cameraIdList.forEach { cameraId ->
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (cameraCharacteristics.isHardwareLevelSupported(REQUIRED_SUPPORTED_HARDWARE_LEVEL)) {
                if (cameraCharacteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = cameraId
                    frontCameraCharacteristics = cameraCharacteristics
                } else if (cameraCharacteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = cameraId
                    backCameraCharacteristics = cameraCharacteristics
                }
            }
        }
        cameraPreview.surfaceTextureListener = PreviewSurfaceTextureListener()
    }

    override fun onResume() {
        super.onResume()
        openCamera()
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    private fun openCamera() {
        val cameraId = backCameraId ?: frontCameraId
        cameraCharacteristics = backCameraCharacteristics ?: frontCameraCharacteristics
        if (cameraId != null) {
            val openCameraMessage = OpenCameraMessage(cameraId, CameraStateCallback())
            cameraHandler?.obtainMessage(MSG_OPEN_CAMERA, openCameraMessage).sendToTarget()
        } else {
            throw RuntimeException("Camera id must no be null")
        }
    }

    @WorkerThread
    private fun getOptimalSize(
        cameraCharacteristics: CameraCharacteristics,
        clazz: Class<*>,
        maxWith: Int,
        maxHeight: Int
    ): Size? {
        val aspectRatio = maxWith.toFloat() / maxHeight
        val streamConfigurationMap =
            cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = streamConfigurationMap?.getOutputSizes(clazz)

        var targetSize: Size? = null
        var targetRatioDiff = Float.MAX_VALUE
        Log.d("bwl", "getOptimalSize: maxWidth = $maxWith, maxHeight = $maxHeight")
        if (supportedSizes != null) {
            for (size in supportedSizes) {
                Log.d("bwl", "$size")
                if (size.height <= maxWith) {
                    if (size.width.toFloat() / size.height - aspectRatio < targetRatioDiff) {
                        targetRatioDiff = size.width.toFloat() / size.height - aspectRatio
                        targetSize = size
                    }
                }
            }
        }
        return targetSize
    }

    private fun closeCamera() {
        stopPreview()
        cameraHandler.sendEmptyMessage(MSG_CLOSE_CAMERA)
    }

    private fun stopPreview() {
        cameraHandler.sendEmptyMessage(MSG_STOP_PREVIEW)
    }

    private inner class CameraStateCallback : CameraDevice.StateCallback() {
        @WorkerThread
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            cameraHandler.sendEmptyMessage(MSG_CREATE_CAPTURE_SESSION)
            runOnUiThread {
                Toast.makeText(this@Camera2Activity, "相机已开启", Toast.LENGTH_SHORT).show()
            }
        }

        @WorkerThread
        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice = null
        }

        @WorkerThread
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private inner class PreviewSurfaceTextureListener : TextureView.SurfaceTextureListener {
        @MainThread
        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
        }

        @MainThread
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
        }

        @MainThread
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            return false
        }

        @MainThread
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            previewSurfaceTexture = surface
            cameraHandler.sendEmptyMessage(MSG_SET_PREVIEW_SIZE)
        }
    }

    private inner class SessionStateCallback : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
        }

        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            cameraHandler.sendEmptyMessage(MSG_CREATE_CAPTURE_REQUEST)
        }
    }

    private inner class RepeatingCaptureStateCallback : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
        }
    }
}

fun CameraCharacteristics.isHardwareLevelSupported(requiredLevel: Int): Boolean {
    val sortedLevels = intArrayOf(
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
    )
    val deviceLevel: Int? = this[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
    if (deviceLevel == requiredLevel) {
        return true
    }
    for (soredLevel in sortedLevels) {
        if (requiredLevel == soredLevel) {
            return true
        } else if (deviceLevel == soredLevel) {
            return false
        }
    }
    return false
}
