package com.rncamerakit

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.MediaActionSound
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.util.Range
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.camera2.interop.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

@SuppressLint("ViewConstructor") // Extra constructors unused. Not using visual layout tools
class CKCamera(context: ThemedReactContext) : FrameLayout(context), LifecycleObserver {
    private val currentContext: ThemedReactContext = context

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var orientationListener: OrientationEventListener? = null
    private var viewFinder: PreviewView = PreviewView(context)
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var outputPath: String? = null

    // Camera Props
    private var lensType = CameraSelector.LENS_FACING_BACK
    private var autoFocus = "on"
    private var isActive = false
    private var zoomMode = "on"

    // Barcode Props
    private var scanBarcode: Boolean = false
    private var frameColor = Color.GREEN
    private var laserColor = Color.RED

    private fun getActivity() : Activity {
        return currentContext.currentActivity!!
    }

    init {
        viewFinder.layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        )
        installHierarchyFitter(viewFinder)
        addView(viewFinder)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasPermissions()) {
            viewFinder.post { setupCamera() }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        cameraExecutor.shutdown()
        orientationListener?.disable()
        cameraProvider?.unbindAll()
    }

    // If this is not called correctly, view finder will be black/blank
    // https://github.com/facebook/react-native/issues/17968#issuecomment-633308615
    private fun installHierarchyFitter(view: ViewGroup) {
        Log.d(TAG, "CameraView looking for ThemedReactContext")
        if (context is ThemedReactContext) { // only react-native setup
            Log.d(TAG, "CameraView found ThemedReactContext")
            view.setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
                override fun onChildViewAdded(parent: View?, child: View?) {
                    parent?.measure(
                            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
                    )
                    parent?.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
                }
            })
        }
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(getActivity())
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Rotate the image according to device orientation, even when UI orientation is locked
            orientationListener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
                override fun onOrientationChanged(orientation: Int) {
                    val imageCapture = imageCapture ?: return
                    var newOrientation: Int = imageCapture.targetRotation
                    if (orientation >= 315 || orientation < 45) {
                        newOrientation = Surface.ROTATION_0
                    } else if (orientation in 225..314) {
                        newOrientation = Surface.ROTATION_90
                    } else if (orientation in 135..224) {
                        newOrientation = Surface.ROTATION_180
                    } else if (orientation in 45..134) {
                        newOrientation = Surface.ROTATION_270
                    }
                    if (newOrientation != imageCapture.targetRotation) {
                        imageCapture.targetRotation = newOrientation
                        onOrientationChange(newOrientation)
                    }
                }
            }
            orientationListener!!.enable()

            val scaleDetector =  ScaleGestureDetector(context, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector?): Boolean {
                    if (zoomMode == "off") return true
                    val cameraControl = camera?.cameraControl ?: return true
                    val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return true
                    val scaleFactor = detector?.scaleFactor ?: return true
                    val scale = zoom * scaleFactor
                    cameraControl.setZoomRatio(scale)
                    return true
                }
            })

            // Tap to focus
            viewFinder.setOnTouchListener { _, event ->
                if (event.action != MotionEvent.ACTION_UP) {
                    return@setOnTouchListener scaleDetector.onTouchEvent(event)
                }
                focusOnPoint(event.x, event.y)
                return@setOnTouchListener true
            }

            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(getActivity()))
    }

    private fun bindCameraUseCases() {
        if (viewFinder.display == null) return
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // Must unbind the use-cases before later rebinding them
        cameraProvider.unbindAll()

        if (!isActive) return

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensType).build()

        // Preview
        val previewBuilder = Preview.Builder()

        preview = previewBuilder.build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

        val useCases = mutableListOf(preview, imageCapture)

        if (scanBarcode) {
            val analyzer = QRCodeAnalyzer { barcodes ->
                if (barcodes.isNotEmpty()) {
                    onBarcodeRead(barcodes)
                }
            }
            imageAnalyzer!!.setAnalyzer(cameraExecutor, analyzer)
            useCases.add(imageAnalyzer)
        }

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(getActivity() as AppCompatActivity, cameraSelector, *useCases.toTypedArray())

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    fun capture(options: Map<String, Any>, promise: Promise) {
        // Create the output file option to store the captured image in MediaStore
        val outputPath: String = when {
            outputPath != null -> outputPath!!
            else -> {
                val out = File.createTempFile("ckcap", ".jpg", context.cacheDir)
                out.deleteOnExit()
                out.canonicalPath
            }
        }

        val outputFile = File(outputPath)
        val outputOptions = ImageCapture.OutputFileOptions
                    .Builder(outputFile)
                    .build()

        // Setup image capture listener which is triggered after photo has been taken
        imageCapture?.takePicture(
                outputOptions, ContextCompat.getMainExecutor(getActivity()), object : ImageCapture.OnImageSavedCallback {
            override fun onError(ex: ImageCaptureException) {
                Log.e(TAG, "CameraView: Photo capture failed: ${ex.message}", ex)
                promise.reject("E_CAPTURE_FAILED", "takePicture failed: ${ex.message}")
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val uri = output.savedUri ?: Uri.fromFile(outputFile)
                    val id = uri?.path
                    val name = uri?.lastPathSegment
                    val path = uri?.path

                    val savedUri = (output.savedUri ?: outputPath).toString()
                    onPictureTaken(savedUri)
                    Log.d(TAG, "CameraView: Photo capture succeeded: $savedUri")

                    val imageInfo = Arguments.createMap()
                    imageInfo.putString("uri", uri.toString())
                    imageInfo.putString("id", id)
                    imageInfo.putString("name", name)
                    imageInfo.putInt("width", width)
                    imageInfo.putInt("height", height)
                    imageInfo.putString("path", path)

                    promise.resolve(imageInfo)
                } catch (ex: Exception) {
                    Log.e(TAG, "Error while saving or decoding saved photo: ${ex.message}", ex)
                    promise.reject("E_ON_IMG_SAVED", "Error while reading saved photo: ${ex.message}")
                }
            }
        })
    }

    private fun focusOnPoint(x: Float?, y: Float?) {
        if (x === null || y === null) {
            camera?.cameraControl?.cancelFocusAndMetering()
            return
        }
        val factory = viewFinder.meteringPointFactory
        val builder = FocusMeteringAction.Builder(factory.createPoint(x, y))

        camera?.cameraControl?.startFocusAndMetering(builder.build())
    }

    private fun onBarcodeRead(barcodes: List<String>) {
        val event: WritableMap = Arguments.createMap()
        event.putString("codeStringValue", barcodes.first())
        currentContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(
                id,
                "onReadCode",
                event
        )
    }

    private fun onOrientationChange(orientation: Int) {
        val remappedOrientation = when (orientation) {
            Surface.ROTATION_0 -> RNCameraKitModule.PORTRAIT
            Surface.ROTATION_90 -> RNCameraKitModule.LANDSCAPE_LEFT
            Surface.ROTATION_180 -> RNCameraKitModule.PORTRAIT_UPSIDE_DOWN
            Surface.ROTATION_270 -> RNCameraKitModule.LANDSCAPE_RIGHT
            else -> {
                Log.e(TAG, "CameraView: Unknown device orientation detected: $orientation")
                return
            }
        }

        val event: WritableMap = Arguments.createMap()
        event.putInt("orientation", remappedOrientation)
        currentContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(
                id,
                "onOrientationChange",
                event
        )
    }

    private fun onPictureTaken(uri: String) {
        val event: WritableMap = Arguments.createMap()
        event.putString("uri", uri)
        currentContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(
                id,
                "onPictureTaken",
                event
        )
    }

    fun setIsActive(value: Boolean) {
        val restartCamera = value != isActive
        isActive = value
        if (restartCamera) bindCameraUseCases()
    }

    fun setFlashMode(mode: String?) {
        val imageCapture = imageCapture ?: return
        val camera = camera ?: return
        when (mode) {
            "on" -> {
                camera.cameraControl.enableTorch(false)
                imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
            }
            "off" -> {
                camera.cameraControl.enableTorch(false)
                imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
            }
            else -> { // 'auto' and any wrong values
                imageCapture.flashMode = ImageCapture.FLASH_MODE_AUTO
                camera.cameraControl.enableTorch(false)
            }
        }
    }

    fun setTorchMode(mode: String?) {
        val camera = camera ?: return
        when (mode) {
            "on" -> {
                camera.cameraControl.enableTorch(true)
            }
            "off" -> {
                camera.cameraControl.enableTorch(false)
            }
            else -> { // 'auto' and any wrong values
                camera.cameraControl.enableTorch(false)
            }
        }
    }

    fun setAutoFocus(mode: String = "on") {
        autoFocus = mode
        when(mode) {
            // "cancel" clear AF points and engages continuous auto-focus
            "on" -> camera?.cameraControl?.cancelFocusAndMetering()
            // 'off': Handled when you tap to focus
        }
    }

    fun setZoomMode(mode: String = "on") {
        zoomMode = mode
    }

    fun setScanBarcode(enabled: Boolean) {
        val restartCamera = enabled != scanBarcode
        scanBarcode = enabled
        if (restartCamera) bindCameraUseCases()
    }

    fun setCameraType(type: String = "back") {
        val newLensType = when (type) {
            "front" -> CameraSelector.LENS_FACING_FRONT
            else -> CameraSelector.LENS_FACING_BACK
        }
        val restartCamera = lensType != newLensType
        lensType = newLensType
        if (restartCamera) bindCameraUseCases()
    }

    fun setOutputPath(path: String) {
        outputPath = path
    }

    private fun convertDeviceHeightToSupportedAspectRatio(actualWidth: Int, actualHeight: Int): Int {
        val maxScreenRatio = 16 / 9f
        return (if (actualHeight / actualWidth > maxScreenRatio) actualWidth * maxScreenRatio else actualHeight).toInt()
    }

    private fun hasPermissions(): Boolean {
        val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
        if (requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }) {
            return true
        }
        ActivityCompat.requestPermissions(
                getActivity(),
                requiredPermissions,
                42 // random callback identifier
        )
        return false
    }

    companion object {

        private const val TAG = "CameraKit"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}
