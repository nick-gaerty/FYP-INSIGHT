package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener, SensorEventListener, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private var vibrator: Vibrator? = null
    private lateinit var tts: TextToSpeech

    private var lastSpokenTime = 0L
    private val speakCooldownMillis = 1000L

    private var currentMode = AppMode.NONE
    private var targetObject: String? = null
    private var selectedMode = AppMode.GENERAL

    private var tapCount = 0
    private var holdStartTime: Long = 0
    private var isHolding = false
    private var holdHandler: Handler? = null
    private var holdRunnable: Runnable? = null

    private var labelList: List<String> = emptyList()
    private var currentLabelIndex = 0
    private var selectingTarget = false
    private var pendingTargetSelectionPrompt = false
    private var detectionEnabled = false
    private var awaitingTargetConfirmation = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true) {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        tts = TextToSpeech(this, this)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                when (utteranceId) {
                    "MODE_CONFIRM" -> detectionEnabled = true
                    "TARGET_CONFIRM" -> {
                        awaitingTargetConfirmation = false
                        detectionEnabled = true
                        setupTouchInteraction()
                    }
                }
            }
            override fun onError(utteranceId: String?) {}
        })

        cameraExecutor.execute {
            detector = Detector(baseContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this)
            val ignoreList = listOf("bath unit", "sideboard", "couch", "shower cabinet", "dining table")
            labelList = (detector?.getLabels() ?: emptyList()).filterNot { it in ignoreList }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupTouchInteraction()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("Tap the screen to switch between modes. Hold to confirm selection.")
        }
    }

    private fun speak(message: String, utteranceId: String? = null) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun setupTouchInteraction() {
        binding.root.setOnTouchListener { _, event ->
            val screenWidth = binding.root.width
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    holdStartTime = System.currentTimeMillis()
                    isHolding = true
                    holdHandler = Handler(Looper.getMainLooper())
                    holdRunnable = object : Runnable {
                        override fun run() {
                            if (isHolding) {
                                if (selectingTarget) {
                                    // Confirm object selection
                                    targetObject = labelList[currentLabelIndex]
                                    awaitingTargetConfirmation = true
                                    speak("${targetObject} selected", "TARGET_CONFIRM")
                                    selectingTarget = false
                                    // Important: don't remove touch listener — we now want to allow return-to-menu later
                                } else if (currentMode != AppMode.NONE) {
                                    // Return to main menu only after object has been selected
                                    detectionEnabled = false
                                    selectingTarget = false
                                    awaitingTargetConfirmation = false
                                    currentMode = AppMode.NONE
                                    speak("Returned to main menu. Tap to switch between modes. Hold to confirm selection.")
                                } else {
                                    // Confirm mode selection
                                    currentMode = selectedMode
                                    speak("${selectedMode.name.lowercase().replaceFirstChar { it.uppercase() }} mode selected", "MODE_CONFIRM")
                                    if (selectedMode == AppMode.SPECIFIC) {
                                        pendingTargetSelectionPrompt = true
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            if (pendingTargetSelectionPrompt) {
                                                selectingTarget = true
                                                speak("Tap to choose the object you want to navigate to.")
                                                Handler(Looper.getMainLooper()).postDelayed({
                                                    speak(labelList[currentLabelIndex])
                                                }, 3000)
                                            }
                                        }, 2000)
                                    }
                                }
                                isHolding = false
                            }
                        }
                    }
                    holdHandler?.postDelayed(holdRunnable!!, 1500)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldTime = System.currentTimeMillis() - holdStartTime
                    if (isHolding && heldTime <= 1500) {
                        if (selectingTarget) {
                            // Tap left/right to cycle labels
                            if (event.x < screenWidth / 2) {
                                currentLabelIndex = (currentLabelIndex - 1 + labelList.size) % labelList.size
                            } else {
                                currentLabelIndex = (currentLabelIndex + 1) % labelList.size
                            }
                            speak(labelList[currentLabelIndex])
                        } else if (currentMode == AppMode.NONE) {
                            // Switch between modes
                            selectedMode = if (selectedMode == AppMode.GENERAL) AppMode.SPECIFIC else AppMode.GENERAL
                            speak("${selectedMode.name.lowercase().replaceFirstChar { it.uppercase() }} mode")
                        }
                        // Ignore tap during active mode unless selecting target
                    }
                    isHolding = false
                    holdHandler?.removeCallbacks(holdRunnable!!)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (!detectionEnabled || awaitingTargetConfirmation) return

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(boundingBoxes)

            val currentTime = System.currentTimeMillis()

            when (currentMode) {
                AppMode.NONE -> {}
                AppMode.GENERAL -> {
                    if (boundingBoxes.isNotEmpty()) {
                        val mostCentered = boundingBoxes.minByOrNull { Math.abs(0.5f - it.cx) }

                        mostCentered?.let { box ->
                            val label = box.clsName
                            val distanceDesc = getDistanceDescription(box)
                            val currentTime = System.currentTimeMillis()

                            if (currentTime - lastSpokenTime > speakCooldownMillis && !tts.isSpeaking) {
                                tts.speak("$label is $distanceDesc", TextToSpeech.QUEUE_ADD, null, null)
                                vibrateOnce(200)
                                lastSpokenTime = currentTime
                            }
                        }
                    }
                }
                AppMode.SPECIFIC -> {
                    if (targetObject != null) {
                        val currentTime = System.currentTimeMillis()
                        var foundTarget = false

                        for (box in boundingBoxes) {
                            if (box.clsName.equals(targetObject, ignoreCase = true)) {
                                // Vibrate for target object (no TTS)
                                val centerOffset = Math.abs(0.5f - box.cx)
                                val intensity = (1f - centerOffset) * 255
                                vibrateOnce(intensity.toInt().coerceIn(50, 255))
                                foundTarget = true
                                break // vibrate only once per frame
                            }
                        }

                        if (!foundTarget && currentTime - lastSpokenTime > speakCooldownMillis) {
                            // Say the first non-target object if one exists
                            boundingBoxes.firstOrNull {
                                !it.clsName.equals(
                                    targetObject,
                                    ignoreCase = true
                                )
                            }?.let { nonTarget ->
                                tts.speak(nonTarget.clsName, TextToSpeech.QUEUE_FLUSH, null, null)
                                lastSpokenTime = currentTime
                            }
                        }
                    }
                }
            }
        }
    }

    private fun vibrateOnce(durationMs: Int) {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                it.vibrate(effect)
            } else {
                it.vibrate(durationMs.toLong())
            }
        }
    }

    private fun getDistanceDescription(box: BoundingBox): String {
        return when {
            box.h > 0.5f -> "very close"
            box.h > 0.3f -> "close"
            box.h > 0.15f -> "medium distance"
            else -> "far"
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        tts.shutdown()
        cameraExecutor.shutdown()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val GYRO_THRESHOLD = 5.0f
        private const val ACCEL_THRESHOLD = 15.0f
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE
        ).toTypedArray()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }
}
