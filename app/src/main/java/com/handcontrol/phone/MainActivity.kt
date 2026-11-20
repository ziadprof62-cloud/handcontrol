package com.handcontrol.phone

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: HandOverlayView
    private lateinit var controls: View
    private lateinit var statusView: TextView
    private lateinit var flashView: TextView
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var holdBar: ProgressBar
    private lateinit var volumeView: TextView
    private lateinit var startBtn: Button
    private lateinit var pipBtn: Button

    private lateinit var actions: PhoneActions
    private lateinit var cameraExecutor: ExecutorService

    private val engine = GestureEngine { action -> handleAction(action) }

    @Volatile private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var running = false
    private var starting = false

    @Volatile private var imgW = 0
    @Volatile private var imgH = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startEngine() else setStatus("السماح باستخدام الكاميرا مطلوب")
        }

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        controls = findViewById(R.id.controls)
        statusView = findViewById(R.id.status)
        flashView = findViewById(R.id.flash)
        titleView = findViewById(R.id.gestureTitle)
        hintView = findViewById(R.id.gestureHint)
        holdBar = findViewById(R.id.holdBar)
        volumeView = findViewById(R.id.volumeText)
        startBtn = findViewById(R.id.startBtn)
        pipBtn = findViewById(R.id.pipBtn)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        actions = PhoneActions(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        volumeView.text = "🔊 ${actions.volumePercent()}%"

        startBtn.setOnClickListener {
            if (running || starting) {
                stopEngine()
            } else if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startEngine()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        pipBtn.setOnClickListener {
            if (running) enterPip() else setStatus("شغّل الكاميرا أولاً")
        }
    }

    override fun onStop() {
        super.onStop()
        // Stops the camera when the app is really closed. Entering the
        // mini window does NOT call onStop, so detection keeps running.
        stopEngine()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEngine()
        cameraExecutor.shutdown()
    }

    // ------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------

    private fun startEngine() {

        if (running || starting) return

        starting = true
        startBtn.isEnabled = false
        setStatus("جاري تحميل النموذج… (أول مرة يحتاج إنترنت)")

        Thread {
            try {

                val model = ModelLoader.load(this)

                val options = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.4f)
                    .setMinHandPresenceConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { e -> runOnUiThread { setStatus("خطأ: ${e.message}") } }
                    .build()

                val rec = GestureRecognizer.createFromOptions(this, options)

                runOnUiThread {
                    recognizer = rec
                    startCamera()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    starting = false
                    startBtn.isEnabled = true
                    setStatus("تعذّر تحميل النموذج: ${e.message}")
                }
            }
        }.start()
    }

    private fun startCamera() {

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({

            try {

                val provider = future.get()
                cameraProvider = provider

                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) } }

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )

                running = true
                starting = false

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updatePipParams()

                startBtn.isEnabled = true
                startBtn.text = "إيقاف"
                setStatus("الكاميرا تعمل — اضغط Home لفتح النافذة الصغيرة فوق أي فيديو")

            } catch (e: Exception) {

                starting = false
                startBtn.isEnabled = true
                setStatus("تعذّر تشغيل الكاميرا: ${e.message}")
                stopEngine()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopEngine() {

        val wasActive = running || starting || recognizer != null

        running = false
        starting = false

        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null

        engine.reset()
        overlay.setResult(null, 0, 0)

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updatePipParams()

        startBtn.isEnabled = true
        startBtn.text = "ابدأ"
        titleView.text = "ارفع يدك أمام الكاميرا"
        hintView.text = " "
        holdBar.progress = 0

        if (wasActive) setStatus("الكاميرا متوقفة")
    }

    // ------------------------------------------------------------
    // Frames -> MediaPipe
    // ------------------------------------------------------------

    private fun analyze(proxy: ImageProxy) {

        val rec = recognizer

        if (rec == null || !running) {
            proxy.close()
            return
        }

        try {

            val bitmap = proxy.toBitmap()

            // rotate upright and mirror (front camera = selfie view)
            val matrix = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }

            val upright = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            imgW = upright.width
            imgH = upright.height

            rec.recognizeAsync(
                BitmapImageBuilder(upright).build(),
                SystemClock.uptimeMillis()
            )

        } catch (_: Exception) {
            // skip this frame
        } finally {
            proxy.close()
        }
    }

    private fun onResult(result: GestureRecognizerResult) {

        val hasHand = result.landmarks().isNotEmpty()

        var name: String? = null
        var score = 0f

        if (hasHand && result.gestures().isNotEmpty() && result.gestures()[0].isNotEmpty()) {
            val top = result.gestures()[0][0]
            name = top.categoryName()
            score = top.score()
        }

        val state = engine.update(name, score, hasHand, SystemClock.uptimeMillis())

        val landmarks = if (hasHand) result.landmarks()[0] else null

        runOnUiThread {
            if (running) {
                overlay.setResult(landmarks, imgW, imgH)
                render(state)
            }
        }
    }

    // ------------------------------------------------------------
    // Actions and UI
    // ------------------------------------------------------------

    private fun handleAction(action: GestureEngine.Action) {

        runOnUiThread {

            when (action) {

                GestureEngine.Action.PLAY -> {
                    actions.play()
                    flash("▶  تشغيل")
                }

                GestureEngine.Action.PAUSE -> {
                    actions.pause()
                    flash("⏸  إيقاف")
                }

                GestureEngine.Action.VOLUME_UP -> {
                    actions.volumeUp()
                    flash("🔊  ${actions.volumePercent()}%")
                }

                GestureEngine.Action.VOLUME_DOWN -> {
                    actions.volumeDown()
                    flash("🔉  ${actions.volumePercent()}%")
                }
            }
        }
    }

    private fun render(s: GestureEngine.State) {

        when {
            !s.handPresent -> {
                titleView.text = "ارفع يدك أمام الكاميرا"
                hintView.text = " "
            }

            s.def != null -> {
                titleView.text = s.def.title
                hintView.text = s.def.hint
            }

            else -> {
                titleView.text = "وضعية غير معروفة"
                hintView.text = "جرّب إحدى الإيماءات المكتوبة بالأسفل"
            }
        }

        holdBar.progress = (s.progress * 100).toInt()
        volumeView.text = "🔊 ${actions.volumePercent()}%"
    }

    private fun flash(text: String) {
        flashView.text = text
        flashView.animate().cancel()
        flashView.alpha = 1f
        flashView.animate().alpha(0f).setStartDelay(500).setDuration(300).start()
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    // ------------------------------------------------------------
    // Picture-in-picture (small floating camera over any app)
    // ------------------------------------------------------------

    private fun pipParams(): PictureInPictureParams {

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(running)
        }

        return builder.build()
    }

    private fun updatePipParams() {
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun enterPip() {
        if (!running) return
        try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {
            setStatus("النافذة الصغيرة غير مدعومة على هذا الجهاز")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically (auto-enter); older versions here
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }
}
