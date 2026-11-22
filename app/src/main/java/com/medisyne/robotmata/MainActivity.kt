package com.medisyne.robotmata

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
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
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private val prefsName = "robot_mata_prefs"
    private val keyMode = "ip_mode" // "BROADCAST" atau "MANUAL"
    private val keyManualIp = "manual_ip"
    private val keyCustomVideoPath = "custom_video_path"
    private val keyStopWidthPercent = "stop_width_percent"
    private val broadcastIp = "255.255.255.255"
    private val esp32Port = 4210

    // Parameter tracking & gestur
    private val toggleCooldownMs = 2500L     // Jeda dua arah setelah Lock / Unlock
    private val palmHoldConfirmMs = 350L     // Tahan 5 jari minimal 0.35 detik
    private val targetLostTimeoutMs = 1800L  // Lepas kunci otomatis jika target hilang > 1.8 detik
    private val emaAlpha = 0.6f              // Smoothing posisi horizontal (0.0 - 1.0)

    // Batas hysteresis horizontal (rasio 0.0 - 1.0)
    private val enterLeftRatio = 0.28f
    private val exitLeftRatio = 0.40f
    private val enterRightRatio = 0.72f
    private val exitRightRatio = 0.60f

    // Default 45% lebar layar: cocok untuk kamera rendah di ruang sempit
    @Volatile private var stopWidthPercent = 45
    @Volatile private var currentMode = "BROADCAST"
    @Volatile private var manualIp = "192.168.4.1"

    private var isEyeFullscreen = false
    private var customVideoPath: String? = null

    // State penguncian target (hanya diakses di cameraExecutor)
    private var isLocked = false
    private var lastTargetX = 0f
    private var lastTargetY = 0f
    private var lastTargetBox: RectF? = null
    private var lastTargetSeenTime = 0L
    private var smoothedCenterX: Float? = null
    private var currentTurnState = "CENTER"
    private var lastToggleTime = 0L
    private var waitingHandRelease = false
    private var palmHoldStartTime = 0L
    private var frameCounter = 0

    // Komponen UI
    private lateinit var controlScrollView: ScrollView
    private lateinit var controlLayout: LinearLayout
    private lateinit var eyeFullscreenContainer: FrameLayout
    private lateinit var eyeVideoView: VideoView
    private lateinit var statusText: TextView
    private lateinit var lockInfoText: TextView
    private lateinit var targetInfoText: TextView
    private lateinit var distanceInfoText: TextView
    private lateinit var seekStopDistance: SeekBar
    private lateinit var videoInfoText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var rbBroadcast: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var etManualIp: EditText
    private lateinit var btnSaveIp: Button
    private lateinit var btnResetLock: Button
    private lateinit var btnPickVideo: Button
    private lateinit var btnOpenEye: Button

    // Model AI, Audio & Thread
    private var objectDetector: ObjectDetector? = null
    private var gestureRecognizer: GestureRecognizer? = null
    private var toneGenerator: ToneGenerator? = null
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
        stopWidthPercent = prefs.getInt(keyStopWidthPercent, 45).coerceIn(20, 90)

        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (_: Exception) {
            null
        }

        buildUi()
        setupBackBehavior()
        setupModels()

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

        // 1. Layar Kontrol (ScrollView agar tidak terpotong di layar kecil/lanskap)
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

        lockInfoText = TextView(this).apply {
            text = "Status: UNLOCKED (tunjukkan 5 jari untuk mengunci)"
            textSize = 14f
            setPadding(0, 4, 0, 4)
        }

        targetInfoText = TextView(this).apply {
            text = "Target: ${activeTargetIp()}:$esp32Port ($currentMode)"
            textSize = 13f
            setPadding(0, 2, 0, 10)
        }

        // Slider batas jarak berdasarkan LEBAR badan (20% - 90%)
        distanceInfoText = TextView(this).apply {
            text = "Lebar objek: - | Batas stop: $stopWidthPercent% (geser kanan = lebih dekat)"
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }

        seekStopDistance = SeekBar(this).apply {
            max = 70 // rentang 20..90 -> progress 0..70
            progress = stopWidthPercent - 20
            setPadding(0, 4, 0, 12)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    stopWidthPercent = progress + 20
                    distanceInfoText.text =
                        "Lebar objek: - | Batas stop: $stopWidthPercent% (geser kanan = lebih dekat)"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                        .putInt(keyStopWidthPercent, stopWidthPercent)
                        .apply()
                }
            })
        }

        btnResetLock = Button(this).apply {
            text = "Lepas Kunci Target (Manual)"
            setOnClickListener {
                cameraExecutor.execute {
                    unlockTarget(playBeep = true, requireRelease = false)
                    sendUdp("DIAM", activeTargetIp(), currentMode == "BROADCAST")
                    runOnUiThread {
                        statusText.text = "Perintah: DIAM"
                        lockInfoText.text = "Status: UNLOCKED (direset manual)"
                    }
                }
            }
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
        controlLayout.addView(lockInfoText)
        controlLayout.addView(targetInfoText)
        controlLayout.addView(distanceInfoText)
        controlLayout.addView(seekStopDistance)
        controlLayout.addView(btnResetLock)
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

    private fun setupModels() {
        try {
            val detectorOptions = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("efficientdet_lite0.tflite")
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(0.35f)
                .setMaxResults(5)
                .build()
            objectDetector = ObjectDetector.createFromOptions(this, detectorOptions)
        } catch (e: Exception) {
            statusText.text = "Gagal muat model objek: ${e.message}"
        }

        try {
            val gestureOptions = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("gesture_recognizer.task")
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            gestureRecognizer = GestureRecognizer.createFromOptions(this, gestureOptions)
        } catch (e: Exception) {
            lockInfoText.text = "Gagal muat gesture_recognizer.task: ${e.message}"
        }
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
            val width = prepared.width.toFloat()
            val height = prepared.height.toFloat()
            val now = System.currentTimeMillis()

            var command = "DIAM"
            var lockStatusMsg: String
            var widthRatioPercent = -1

            if (!isLocked) {
                // === MODE STANDBY (UNLOCKED) ===
                // Hanya jalankan GestureRecognizer agar ringan; ObjectDetector dipanggil saat 5 jari terkonfirmasi
                val openPalms = detectOpenPalms(mpImage, width, height)

                if (waitingHandRelease) {
                    if (openPalms.isEmpty()) {
                        waitingHandRelease = false
                        palmHoldStartTime = 0L
                        lockStatusMsg = "Status: UNLOCKED (tunjukkan 5 jari untuk mengunci)"
                    } else {
                        lockStatusMsg = "Status: UNLOCKED (turunkan tangan dulu...)"
                    }
                } else if (now - lastToggleTime < toggleCooldownMs) {
                    palmHoldStartTime = 0L
                    val sisaDetik = ((toggleCooldownMs - (now - lastToggleTime)) / 1000f)
                    lockStatusMsg = String.format("Status: UNLOCKED (jeda %.1f dtk)", sisaDetik)
                } else if (openPalms.isNotEmpty()) {
                    if (palmHoldStartTime == 0L) {
                        palmHoldStartTime = now
                    }
                    if (now - palmHoldStartTime >= palmHoldConfirmMs) {
                        val personBoxes = detectPersonBoxes(mpImage)
                        val matched = findPersonForPalm(openPalms, personBoxes, width, height)
                        if (matched != null) {
                            lockTarget(matched, now)
                            lockStatusMsg = "Status: LOCKED (target dikunci!)"
                        } else {
                            lockStatusMsg = "Status: 5 jari terbaca, badan belum terdeteksi"
                        }
                    } else {
                        lockStatusMsg = "Status: Menahan 5 jari untuk mengunci..."
                    }
                } else {
                    palmHoldStartTime = 0L
                    lockStatusMsg = "Status: UNLOCKED (tunjukkan 5 jari untuk mengunci)"
                }
            } else {
                // === MODE MENGIKUTI (LOCKED) ===
                frameCounter++
                val personBoxes = detectPersonBoxes(mpImage)
                val trackedBox = matchLockedPerson(personBoxes, width)

                if (trackedBox != null) {
                    lastTargetBox = RectF(trackedBox)
                    lastTargetX = trackedBox.centerX()
                    lastTargetY = trackedBox.centerY()
                    lastTargetSeenTime = now

                    // Cek gestur 5 jari untuk UNLOCK (tiap 4 frame, atau tiap frame saat sedang menahan 5 jari)
                    val shouldCheckGesture = (frameCounter % 4 == 0) || (palmHoldStartTime > 0L) || waitingHandRelease
                    var unlockedNow = false

                    if (shouldCheckGesture) {
                        val openPalms = detectOpenPalms(mpImage, width, height)
                        val palmOnTarget = openPalms.any { (px, py) ->
                            isPointNearTarget(px, py, trackedBox, width, height)
                        }

                        if (waitingHandRelease) {
                            if (!palmOnTarget) {
                                waitingHandRelease = false
                                palmHoldStartTime = 0L
                            }
                        } else if (now - lastToggleTime >= toggleCooldownMs) {
                            if (palmOnTarget) {
                                if (palmHoldStartTime == 0L) {
                                    palmHoldStartTime = now
                                }
                                if (now - palmHoldStartTime >= palmHoldConfirmMs) {
                                    unlockTarget(playBeep = true, requireRelease = true)
                                    unlockedNow = true
                                }
                            } else {
                                palmHoldStartTime = 0L
                            }
                        } else {
                            palmHoldStartTime = 0L
                        }
                    }

                    if (unlockedNow) {
                        command = "DIAM"
                        lockStatusMsg = "Status: UNLOCKED (dibuka dengan 5 jari)"
                    } else {
                        // Hitung posisi halus (EMA) + Hysteresis anti-goyang
                        val rawX = trackedBox.centerX()
                        val smoothX = smoothedCenterX?.let { emaAlpha * rawX + (1f - emaAlpha) * it } ?: rawX
                        smoothedCenterX = smoothX
                        val normX = smoothX / width

                        currentTurnState = when (currentTurnState) {
                            "KIRI" -> when {
                                normX > enterRightRatio -> "KANAN"
                                normX > exitLeftRatio -> "CENTER"
                                else -> "KIRI"
                            }
                            "KANAN" -> when {
                                normX < enterLeftRatio -> "KIRI"
                                normX < exitRightRatio -> "CENTER"
                                else -> "KANAN"
                            }
                            else -> when {
                                normX < enterLeftRatio -> "KIRI"
                                normX > enterRightRatio -> "KANAN"
                                else -> "CENTER"
                            }
                        }

                        // Jarak berdasarkan LEBAR badan
                        val boxWidthRatio = trackedBox.width() / width
                        widthRatioPercent = (boxWidthRatio * 100).toInt()
                        val stopRatio = stopWidthPercent / 100f

                        command = when {
                            currentTurnState == "KIRI" -> "KIRI"
                            currentTurnState == "KANAN" -> "KANAN"
                            boxWidthRatio >= stopRatio -> "DIAM"
                            else -> "MAJU"
                        }

                        lockStatusMsg = if (palmHoldStartTime > 0L) {
                            "Status: LOCKED (menahan 5 jari untuk unlock...)"
                        } else {
                            "Status: LOCKED (mengikuti target)"
                        }
                    }
                } else {
                    // Target sempat hilang dari kamera
                    palmHoldStartTime = 0L
                    val lostMs = now - lastTargetSeenTime
                    if (lostMs > targetLostTimeoutMs) {
                        unlockTarget(playBeep = true, requireRelease = false)
                        command = "DIAM"
                        lockStatusMsg = "Status: UNLOCKED (target keluar frame)"
                    } else {
                        command = "DIAM"
                        val sisa = ((targetLostTimeoutMs - lostMs) / 1000f)
                        lockStatusMsg = String.format("Status: LOCKED (mencari target %.1f dtk)", sisa)
                    }
                }
            }

            val targetIp = activeTargetIp()
            val useBroadcast = currentMode == "BROADCAST"
            sendUdp(command, targetIp, useBroadcast)

            val currentStopPct = stopWidthPercent
            runOnUiThread {
                statusText.text = "Perintah: $command"
                lockInfoText.text = lockStatusMsg
                targetInfoText.text = "Target: $targetIp:$esp32Port ($currentMode)"
                distanceInfoText.text = if (widthRatioPercent >= 0) {
                    "Lebar objek: $widthRatioPercent% | Batas stop: $currentStopPct%"
                } else {
                    "Lebar objek: - | Batas stop: $currentStopPct%"
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

    private fun detectOpenPalms(mpImage: MPImage, width: Float, height: Float): List<Pair<Float, Float>> {
        val result = gestureRecognizer?.recognize(mpImage) ?: return emptyList()
        val gestures = result.gestures()
        val landmarks = result.landmarks()
        val palms = mutableListOf<Pair<Float, Float>>()

        for (i in gestures.indices) {
            val isOpenPalm = gestures[i].any {
                it.categoryName() == "Open_Palm" && it.score() >= 0.5f
            }
            if (isOpenPalm && i < landmarks.size && landmarks[i].size > 9) {
                // Landmark 9 = pangkal jari tengah (titik tengah telapak tangan)
                val centerLm = landmarks[i][9]
                palms.add(Pair(centerLm.x() * width, centerLm.y() * height))
            }
        }
        return palms
    }

    private fun detectPersonBoxes(mpImage: MPImage): List<RectF> {
        val result = objectDetector?.detect(mpImage) ?: return emptyList()
        return result.detections()
            .filter { detection ->
                detection.categories().any {
                    it.categoryName().equals("person", ignoreCase = true)
                }
            }
            .map { RectF(it.boundingBox()) }
    }

    private fun findPersonForPalm(
        palms: List<Pair<Float, Float>>,
        personBoxes: List<RectF>,
        width: Float,
        height: Float
    ): RectF? {
        if (personBoxes.isEmpty()) return null
        val (px, py) = palms.first()

        // Prioritas 1: kotak orang yang melingkupi posisi telapak tangan (dengan margin sedikit)
        val containing = personBoxes.filter { isPointNearTarget(px, py, it, width, height) }
        if (containing.isNotEmpty()) {
            return containing.minByOrNull { hypot(it.centerX() - px, it.centerY() - py) }
        }

        // Prioritas 2: orang terdekat jika tangan sedikit terentang ke samping
        val nearest = personBoxes.minByOrNull { hypot(it.centerX() - px, it.centerY() - py) }
        if (nearest != null && hypot(nearest.centerX() - px, nearest.centerY() - py) <= width * 0.40f) {
            return nearest
        }
        return null
    }

    private fun isPointNearTarget(
        px: Float,
        py: Float,
        box: RectF,
        width: Float,
        height: Float
    ): Boolean {
        val marginX = maxOf(box.width() * 0.18f, width * 0.06f)
        val marginY = maxOf(box.height() * 0.12f, height * 0.06f)
        return px >= (box.left - marginX) &&
            px <= (box.right + marginX) &&
            py >= (box.top - marginY) &&
            py <= (box.bottom + marginY)
    }

    private fun matchLockedPerson(personBoxes: List<RectF>, width: Float): RectF? {
        if (personBoxes.isEmpty()) return null
        val refBox = lastTargetBox
        val maxJump = maxOf(width * 0.38f, (refBox?.width() ?: 0f) * 0.9f)

        var bestBox: RectF? = null
        var bestDist = Float.MAX_VALUE
        for (box in personBoxes) {
            val dist = hypot(box.centerX() - lastTargetX, box.centerY() - lastTargetY)
            if (dist < bestDist && dist <= maxJump) {
                bestDist = dist
                bestBox = box
            }
        }
        return bestBox
    }

    private fun lockTarget(box: RectF, now: Long) {
        isLocked = true
        lastTargetBox = RectF(box)
        lastTargetX = box.centerX()
        lastTargetY = box.centerY()
        smoothedCenterX = box.centerX()
        lastTargetSeenTime = now
        currentTurnState = "CENTER"
        lastToggleTime = now
        waitingHandRelease = true
        palmHoldStartTime = 0L
        frameCounter = 0
        playLockBeep()
    }

    private fun unlockTarget(playBeep: Boolean, requireRelease: Boolean) {
        val wasLocked = isLocked
        isLocked = false
        lastTargetBox = null
        smoothedCenterX = null
        currentTurnState = "CENTER"
        lastToggleTime = System.currentTimeMillis()
        waitingHandRelease = requireRelease
        palmHoldStartTime = 0L
        frameCounter = 0
        if (playBeep && wasLocked) {
            playUnlockBeep()
        }
    }

    private fun playLockBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
        }
    }

    private fun playUnlockBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 260)
        } catch (_: Exception) {
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
        gestureRecognizer?.close()
        toneGenerator?.release()
        cameraExecutor.shutdown()
        udpExecutor.shutdown()
    }
}
