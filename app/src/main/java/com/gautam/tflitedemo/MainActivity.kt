package com.gautam.tflitedemo

import android.Manifest.permission.CAMERA
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.gautam.tflitedemo.databinding.ActivityMainBinding
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.annotations.AfterPermissionGranted

private const val REQUEST_CODE_CAMERA_PERMISSION = 123

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
        checkCameraPermission()

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

                    // Apply declared configs to CameraX using the same lifecycle owner
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this as LifecycleOwner,
                        cameraSelector,
                        preview
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