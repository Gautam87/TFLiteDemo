package com.gautam.tflitedemo

import android.Manifest.permission.CAMERA
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.gautam.tflitedemo.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Task
import com.google.android.gms.tflite.java.TfLite
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.annotations.AfterPermissionGranted
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "MainActivity" // shortcut logt
private const val REQUEST_CODE_CAMERA_PERMISSION = 123

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private lateinit var bitmapBuffer: Bitmap
    private val executor = Executors.newSingleThreadExecutor()
    private var imageRotationDegrees: Int = 0

    // Initialize TFLite using play services Task
    private val initializeTask: Task<Void> by lazy {
        TfLite.initialize(this).addOnFailureListener {
            Log.e(TAG, "TFLite in Play Services failed to initialize.", it)
        }
    }

    // Use TFLite in Play Services runtime by setting the option to FROM_SYSTEM_ONLY
    private val interpreterInitializer = lazy {
        val interpreterOption = InterpreterApi.Options()
            .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
        InterpreterApi.create(
            FileUtil.loadMappedFile(applicationContext, MODEL_PATH),
            interpreterOption
        )

    }

    // init Tensorflow Interpreter
    private val interpreter: InterpreterApi by interpreterInitializer
    private val tfInputImgSize by lazy {
        val inputIndex = 0
        // Model Shape: {1, <img_height>, <img_width>, 3} 3 is for RGB channels
        val inputShape = interpreter.getInputTensor(inputIndex).shape()
        Size(inputShape[2], inputShape[1])
    }
    private var tfInputBuffer = TensorImage(DataType.UINT8)
    private lateinit var tfImageProcessor: ImageProcessor
    private val preprocessNormalizeOp = NormalizeOp(127.0f, 128.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
        checkCameraPermission()
        // Initialize TFLite asynchronously
        initializeTask.addOnSuccessListener {
            Log.d(TAG, "TFLite in Play Services initialized successfully.")
            Toast.makeText(this, "TFLite successfully initialized", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // Terminate all outstanding analyzing jobs (if there is any).
        executor.apply {
            shutdown()
            awaitTermination(1000, TimeUnit.MILLISECONDS)
        }
        //Release TFlite resources
        if (interpreterInitializer.isInitialized()) {
            interpreter.close()
        }
        super.onDestroy()
    }

    private fun bindCameraUseCases() =
        activityMainBinding.cameraPreview.post { // Making sure view is setup
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener(
                {
                    // Camera provider is now guaranteed to be available
                    val cameraProvider = cameraProviderFuture.get()

                    // Set up the view finder use case to display camera preview
                    val preview =
                        Preview.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3) //Image sensor ratio
                            .setTargetRotation(activityMainBinding.cameraPreview.display.rotation)
                            .build()

                    // Create a new camera selector each time, enforcing lens facing
                    val cameraSelector =
                        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build()

                    // Set up the image analysis use case which will process frames in real time
                    val imageAnalysis =
                        ImageAnalysis.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setTargetRotation(activityMainBinding.cameraPreview.display.rotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                    imageAnalysis.setAnalyzer(executor) { image ->
                        if (!::bitmapBuffer.isInitialized) {    // :: is class reference
                            // The image rotation and RGB image buffer are initialized only once
                            // the analyzer has started running
                            imageRotationDegrees = image.imageInfo.rotationDegrees
                            bitmapBuffer =
                                Bitmap.createBitmap(
                                    image.width,
                                    image.height,
                                    Bitmap.Config.ARGB_8888
                                )
                        }

                        // Copy out RGB bits to our shared buffer
                        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

                        tfInputBuffer = preProcessImage(bitmapBuffer, imageRotationDegrees)

                    }

                    // Apply declared configs to CameraX using the same lifecycle owner
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this as LifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                    // Use the camera object to link our preview use case with the view
                    preview.setSurfaceProvider(activityMainBinding.cameraPreview.surfaceProvider)
                },
                ContextCompat.getMainExecutor(this)
            )
        }

    private fun preProcessImage(bitmapBuffer: Bitmap, imageRotationDegrees: Int): TensorImage {
        // Initializes preprocessor
        if (!::tfImageProcessor.isInitialized) {
            val cropSize = minOf(bitmapBuffer.width, bitmapBuffer.height)
            ImageProcessor.Builder()
                .add(ResizeWithCropOrPadOp(cropSize, cropSize))
                .add(
                    ResizeOp(
                        tfInputImgSize.height,
                        tfInputImgSize.width,
                        ResizeOp.ResizeMethod.NEAREST_NEIGHBOR
                    )
                )
                .add(Rot90Op(-imageRotationDegrees / 90))
                .add(preprocessNormalizeOp)
                .build()
                .also {
                    tfImageProcessor = it
                    Log.d(TAG, "tfImageProcessor initialized successfully. imageSize: $cropSize")
                }
        }
        return tfImageProcessor.process(tfInputBuffer.apply { load(bitmapBuffer) })
    }


    @AfterPermissionGranted(REQUEST_CODE_CAMERA_PERMISSION)
    private fun checkCameraPermission() {
        if (EasyPermissions.hasPermissions(this, CAMERA)) {
            // Have permission, do things!
            bindCameraUseCases()
        } else {
            // Ask for one permission
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.permission_camera_message),
                REQUEST_CODE_CAMERA_PERMISSION,
                CAMERA
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // EasyPermissions handles the request result.
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    companion object {
        private const val MODEL_PATH = "efficientnet-lite0-fp32.tflite"
        private const val LABELS_PATH = "labels_without_background.txt"
    }

}