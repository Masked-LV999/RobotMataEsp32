package com.medisyne.robotmata

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val prefsName = "robot_mata_prefs"
    private val keyMode = "ip_mode" // "BROADCAST" atau "MANUAL"
    private val keyManualIp = "manual_ip"
    private val keyCustomVideoPath = "custom_video_path"
    private val keyStopPercent = "stop_distance_percent"
    private val broadcastIp = "255.255.255.255"
    private val esp32Port = 4210

    // Default 80%: robot tetap MAJU sampai orang cukup dekat (tinggi objek >= 80% layar)
    private var stopDistancePercent = 80
    private var isEyeFullscreen = false
    private var currentMode = "BROADCAST"
    private var manualIp = "192.168.4.1"
    private var customVideoPath: String? = null

    private lateinit var controlScrollView: ScrollView
    private lateinit var controlLayout: LinearLayout
    private lateinit var eyeFullscreenContainer: FrameLayout
    private lateinit var eyeVideoView: VideoView
    private lateinit var statusText: TextView
    private lateinit var targetInfoText: TextView
    private lateinit var distanceInfoText: TextView
    private lateinit var seekStopDistance: SeekBar
    private lateinit var videoInfoText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var rbBroadcast: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var etManualIp: EditText
    private lateinit var btnSaveIp: Button
    private lateinit var btnPickVideo: Button
    private lateinit var btnOpenEye: Button

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

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                importCustomVideo(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        currentMode = prefs.getString(keyMode, "BROADCAST") ?: "BROADCAST"
        manualIp = prefs.getString(keyManualIp, "192.168.4.1") ?: "192.168.4.1"
        customVideoPath = prefs.getString(keyCustomVideoPath, null)
        stopDistancePercent = prefs.getInt(keyStopPercent, 80).coerceIn(20, 95)

        buildUi()
        setupBackBehavior()
        setupDetector()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        // 1. Layar Kontrol dibungkus ScrollView agar tidak pernah terpotong di layar kecil/lanskap
        controlScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "Perintah: DIAM"
            textSize = 20f
        }

        targetInfoText = TextView(this).apply {
            text = "Target: ${activeTargetIp()}:$esp32Port ($currentMode)"
            textSize = 13f
            setPadding(0, 4, 0, 10)
        }

        // Kontrol batas jarak berhenti (slider 20% - 95%)
        distanceInfoText = TextView(this).apply {
            text = "Tinggi objek: - | Batas stop: $stopDistancePercent% (geser kanan = bisa lebih dekat)"
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }

        seekStopDistance = SeekBar(this).apply {
            max = 75 // rentang 20..95 -> progress 0..75
            progress = stopDistancePercent - 20
            setPadding(0, 4, 0, 12)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    stopDistancePercent = progress + 20
                    distanceInfoText.text =
                        "Tinggi objek: - | Batas stop: $stopDistancePercent% (geser kanan = bisa lebih dekat)"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                        .putInt(keyStopPercent, stopDistancePercent)
                        .apply()
                }
            })
        }

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        rbBroadcast = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Broadcast"
            isChecked = currentMode == "BROADCAST"
        }
        rbManual = RadioButton(this).apply {
            id = View.generateViewId()
            text = "IP Manual"
            isChecked = currentMode == "MANUAL"
        }
        modeGroup.addView(rbBroadcast)
        modeGroup.addView(rbManual)

        etManualIp = EditText(this).apply {
            hint = "Masukkan IP ESP32 (contoh: 192.168.4.1)"
            setText(manualIp)
            isEnabled = currentMode == "MANUAL"
        }

        btnSaveIp = Button(this).apply {
            text = "Simpan Mode & IP"
            setOnClickListener {
                val chosenMode = if (rbManual.isChecked) "MANUAL" else "BROADCAST"
                val inputIp = etManualIp.text.toString().trim()
                if (chosenMode == "MANUAL" && inputIp.isNotEmpty()) {
                    manualIp = inputIp
                }
                currentMode = chosenMode
                getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                    .putString(keyMode, currentMode)
                    .putString(keyManualIp, manualIp)
                    .apply()
                targetInfoText.text = "Target: ${activeTargetIp()}:$esp32Port ($currentMode)"
            }
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isManualSelected = checkedId == rbManual.id
            etManualIp.isEnabled = isManualSelected
            currentMode = if (isManualSelected) "MANUAL" else "BROADCAST"
            getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                .putString(keyMode, currentMode)
                .apply()
            targetInfoText.text = "Target: ${activeTargetIp()}:$esp32Port ($currentMode)"
        }

        videoInfoText = TextView(this).apply {
            text = getVideoLabel()
            textSize = 13f
            setPadding(0, 10, 0, 4)
        }

        btnPickVideo = Button(this).apply {
            text = "Pilih / Ganti Video Mata"
            setOnClickListener {
                pickVideoLauncher.launch("video/*")
            }
        }

        btnOpenEye = Button(this).apply {
            text = "Tampilkan Mata Fullscreen"
            setOnClickListener {
                enterEyeFullscreen()
            }
        }

        controlLayout.addView(statusText)
        controlLayout.addView(targetInfoText)
        controlLayout.addView(distanceInfoText)
        controlLayout.addView(seekStopDistance)
        controlLayout.addView(modeGroup)
        controlLayout.addView(etManualIp)
        controlLayout.addView(btnSaveIp)
        controlLayout.addView(videoInfoText)
        controlLayout.addView(btnPickVideo)
        controlLayout.addView(btnOpenEye)

        controlScrollView.addView(controlLayout)

        // 2. Layar Video Mata Fullscreen (di tengah layar, keluar hanya lewat tombol Back)
        eyeFullscreenContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            setOnClickListener { /* Sengaja diabaikan, keluar hanya via Back */ }
        }

        eyeVideoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                start()
            }
        }

        eyeFullscreenContainer.addView(eyeVideoView)

        root.addView(controlScrollView)
        root.addView(eyeFullscreenContainer)
        setContentView(root)
    }

    private fun getVideoLabel(): String {
        val path = customVideoPath
        return if (!path.isNullOrBlank() && File(path).exists()) {
            "Video aktif: Video kustom dari HP (loop)"
        } else {
            "Video aktif: Bawaan aplikasi (loop)"
        }
    }

    private fun importCustomVideo(sourceUri: Uri) {
        try {
            val destFile = File(filesDir, "custom_eye_video.mp4")
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            customVideoPath = destFile.absolutePath
            getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                .putString(keyCustomVideoPath, customVideoPath)
                .apply()
            videoInfoText.text = getVideoLabel()
        } catch (e: Exception) {
            videoInfoText.text = "Gagal memuat video: ${e.message}"
        }
    }

    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isEyeFullscreen) {
                    exitEyeFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setStatusBarHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun enterEyeFullscreen() {
        isEyeFullscreen = true
        controlScrollView.visibility = View.GONE
        eyeFullscreenContainer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatusBarHidden(true)

        val path = customVideoPath
        val videoUri = if (!path.isNullOrBlank() && File(path).exists()) {
            Uri.fromFile(File(path))
        } else {
            Uri.parse("android.resource://$packageName/${R.raw.mata}")
        }

        eyeVideoView.setVideoURI(videoUri)
        eyeVideoView.start()
    }

    private fun exitEyeFullscreen() {
        isEyeFullscreen = false
        eyeVideoView.stopPlayback()
        eyeFullscreenContainer.visibility = View.GONE
        controlScrollView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatusBarHidden(false)
    }

    private fun activeTargetIp(): String {
        return if (currentMode == "MANUAL") manualIp else broadcastIp
    }

    private fun setupDetector() {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.35f)
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
            val prepared = rotateAndMirrorFrontCamera(
                bitmap,
                imageProxy.imageInfo.rotationDegrees.toFloat()
            )
            val mpImage = BitmapImageBuilder(prepared).build()
            val result = objectDetector?.detect(mpImage)

            val personBox = result?.detections()?.firstOrNull { detection ->
                detection.categories().any {
                    it.categoryName().equals("person", ignoreCase = true)
                }
            }?.boundingBox()

            val height = prepared.height.toFloat()
            val boxHeightRatio = personBox?.let { it.height() / height } ?: 0f
            val stopRatio = stopDistancePercent / 100f

            val command = if (personBox == null) {
                "DIAM"
            } else {
                val centerX = personBox.centerX()
                val width = prepared.width.toFloat()
                when {
                    centerX < width * 0.35f -> "KIRI"
                    centerX > width * 0.65f -> "KANAN"
                    boxHeightRatio >= stopRatio -> "DIAM"
                    else -> "MAJU"
                }
            }

            val targetIp = activeTargetIp()
            val useBroadcast = currentMode == "BROADCAST"
            sendUdp(command, targetIp, useBroadcast)

            val ratioPercent = (boxHeightRatio * 100).toInt()
            runOnUiThread {
                statusText.text = "Perintah: $command"
                targetInfoText.text = "Target: $targetIp:$esp32Port ($currentMode)"
                distanceInfoText.text = if (personBox != null) {
                    "Tinggi objek: $ratioPercent% | Batas stop: $stopDistancePercent%"
                } else {
                    "Tinggi objek: - | Batas stop: $stopDistancePercent%"
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "Error proses frame: ${e.message}"
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateAndMirrorFrontCamera(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply {
            if (angle != 0f) {
                postRotate(angle)
            }
            // Mirror horizontal agar kiri-kanan kamera depan sesuai arah pandang alami
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun sendUdp(message: String, targetIp: String, broadcast: Boolean) {
        udpExecutor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = broadcast
                val bytes = message.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(bytes, bytes.size, address, esp32Port)
                socket.send(packet)
            } catch (_: Exception) {
                // Abaikan error jaringan sementara saat belum terhubung
            } finally {
                socket?.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        eyeVideoView.stopPlayback()
        objectDetector?.close()
        cameraExecutor.shutdown()
        udpExecutor.shutdown()
    }
}
