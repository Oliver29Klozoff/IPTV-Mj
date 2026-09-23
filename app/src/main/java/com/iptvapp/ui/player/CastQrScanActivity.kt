package com.iptvapp.ui.player

import android.content.Intent
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.iptvapp.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scans the "Receive a Cast" QR code. Replaces zxing-android-embedded's CaptureActivity for this
 * one screen only (LoginActivity's backup-restore QR scan is a different payload/purpose and is
 * left on zxing) - that library's CaptureActivity is a Camera1 screen with no zoom hook at all,
 * and a TV's on-screen QR is often too small/far to decode without zooming in. CameraX exposes a
 * real zoom ratio the pinch gesture below drives directly; ML Kit does the actual decoding.
 */
class CastQrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var scanner: BarcodeScanner? = null
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast_qr_scan)
        previewView = findViewById(R.id.previewView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupPinchZoom()
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                finishWithoutResult("Could not open camera")
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            )
            this.scanner = scanner
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(scanner, proxy) }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                finishWithoutResult("No usable camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(scanner: BarcodeScanner, proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                if (resultDelivered) return@addOnSuccessListener
                val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return@addOnSuccessListener
                resultDelivered = true
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, value))
                finish()
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Pinch anywhere on the preview to zoom - the one thing zxing's CaptureActivity had no hook for. */
    private fun setupPinchZoom() {
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zoomState = cam.cameraInfo.zoomState.value ?: return true
                val target = (zoomState.zoomRatio * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun finishWithoutResult(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.close()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
