package com.gautam.tflitedemo

import android.Manifest.permission.CAMERA
import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.gautam.tflitedemo.databinding.ActivityMainBinding
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.annotations.AfterPermissionGranted
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val REQUEST_CODE_CAMERA_PERMISSION = 123

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private lateinit var bitmapBuffer: Bitmap
    private val executor = Executors.newSingleThreadExecutor()
    private var imageRotationDegrees: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
        checkCameraPermission()

    }

    override fun onDestroy() {
        // Terminate all outstanding analyzing jobs (if there is any).
        executor.apply {
            shutdown()
            awaitTermination(1000, TimeUnit.MILLISECONDS)
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
                        // debug bitmap check orientation and image.imageInfo.rotationDegrees
                        //var tmp = bitmapBuffer
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

}