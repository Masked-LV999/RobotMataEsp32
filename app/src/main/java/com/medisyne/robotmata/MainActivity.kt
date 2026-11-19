package com.medisyne.robotmata

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Ganti sesuai IP Wi-Fi ESP32 saat pengujian di jaringan yang sama
    private val esp32Ip = "192.168.4.1"
    private val esp32Port = 4210

    private lateinit var statusText: TextView
    private var objectDetector: ObjectDetector? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val udpExecutor = Executors.newSingleThreadExecutor()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                runOnUiThread {
                    statusText.text = "Izin kamera ditolak."
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusText = TextView(this).apply {
            text = "Menyiapkan kamera & detektor..."
            textSize = 22f
            setPadding(40, 40, 40, 40)
        }
        setContentView(statusText)

        setupDetector()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupDetector() {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.5f)
            .setMaxResults(5)
            .build()

        objectDetector = ObjectDetector.createFromOptions(this, options)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Gagal membuka kamera: ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())
            val mpImage = BitmapImageBuilder(rotated).build()
            val result = objectDetector?.detect(mpImage)

            val personBox = result?.detections()?.firstOrNull { detection ->
                detection.categories().any {
                    it.categoryName().equals("person", ignoreCase = true)
                }
            }?.boundingBox()

            val command = if (personBox == null) {
                "DIAM"
            } else {
                val centerX = personBox.centerX()
                val width = rotated.width.toFloat()
                when {
                    centerX < width * 0.35f -> "KIRI"
                    centerX > width * 0.65f -> "KANAN"
                    else -> "MAJU"
                }
            }

            sendUdp(command)

            runOnUiThread {
                statusText.text = "Perintah: $command | Target ESP32: $esp32Ip:$esp32Port"
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "Error proses frame: ${e.message}"
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) return source
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun sendUdp(message: String) {
        udpExecutor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                val bytes = message.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(esp32Ip)
                val packet = DatagramPacket(bytes, bytes.size, address, esp32Port)
                socket.send(packet)
            } catch (_: Exception) {
                // Abaikan error jaringan sementara saat belum terhubung ke ESP32
            } finally {
                socket?.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        objectDetector?.close()
        cameraExecutor.shutdown()
        udpExecutor.shutdown()
    }
}
