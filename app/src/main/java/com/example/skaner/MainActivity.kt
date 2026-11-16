package com.example.skaner

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var photoView: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var btnPick: Button
    private lateinit var btnCamera: Button
    private lateinit var btnSave: Button
    private lateinit var btnTorch: Button

    private val handler = Handler(Looper.getMainLooper())

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchOn = false

    @Volatile
    private var recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var scriptHint: String? = null

    // Режим файла
    @Volatile
    private var photoMode = false
    private var currentBitmap: Bitmap? = null
    private var photoGen = 0
    private var tStart = 0L
    private var tOcr = 0L

    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.3f).build()
    )
    private val translators = HashMap<String, Translator>()
    private val modelReady = HashSet<String>()
    private val cache = HashMap<String, String>()

    private val busy = AtomicBoolean(false)

    @Volatile
    private var lastRun = 0L

    // ---------- Разрешения и выбор файла ----------

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else status.text = "Камера недоступна. Можно выбрать файл"
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onSaveClicked()
            else toast("Нужно разрешение для сохранения в галерею")
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadPhoto(uri)
        }

    // ---------- onCreate ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        photoView = findViewById(R.id.photoView)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        spinner = findViewById(R.id.scriptSpinner)
        btnPick = findViewById(R.id.btnPick)
        btnCamera = findViewById(R.id.btnCamera)
        btnSave = findViewById(R.id.btnSave)
        btnTorch = findViewById(R.id.btnTorch)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        btnPick.setOnClickListener { pickImage.launch("image/*") }
        btnCamera.setOnClickListener { exitPhotoMode() }
        btnSave.setOnClickListener { onSaveClicked() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnTorch.alpha = 0.6f

        val names = listOf("Латиница (EN, DE, FR, ES…)", "中文", "日本語", "한국어")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val newRecognizer = when (pos) {
                    1 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    2 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    3 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }
                val old = recognizer
                recognizer = newRecognizer
                old.close()

                scriptHint = when (pos) {
                    1 -> "zh"
                    2 -> "ja"
                    3 -> "ko"
                    else -> null
                }
                cache.clear()
                warmUpRecognizer()
                warmUp(scriptHint ?: "en")
                if (photoMode) recognizePhoto()
                else overlay.setItems(emptyList(), 1, 1)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        ensureCamera()
    }

    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Прогрев распознавателя: первая загрузка модели в память занимает секунды
    private fun warmUpRecognizer() {
        try {
            val tiny = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            recognizer.process(InputImage.fromBitmap(tiny, 0))
        } catch (e: Exception) {
            // не критично
        }
    }

    // ---------- Камера ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .build()

            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1024, 768),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { analyze(it) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        camera = null
        torchOn = false
        btnTorch.alpha = 0.6f
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            toast("Фонарик недоступен")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnTorch.alpha = if (torchOn) 1f else 0.6f
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val now = SystemClock.elapsedRealtime()
        if (photoMode || media == null || now - lastRun < 500 ||
            !busy.compareAndSet(false, true)
        ) {
            proxy.close()
            return
        }
        lastRun = now

        val rot = proxy.imageInfo.rotationDegrees
        val w = if (rot == 90 || rot == 270) proxy.height else proxy.width
        val h = if (rot == 90 || rot == 270) proxy.width else proxy.height

        recognizer.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener { handle(it, w, h, -1) }
            .addOnFailureListener { busy.set(false) }
            .addOnCompleteListener { proxy.close() }
    }

    // ---------- Режим файла ----------

    private fun loadPhoto(uri: Uri) {
        status.text = "Загрузка изображения…"
        Thread {
            val bmp = decodeBitmap(uri)
            runOnUiThread {
                if (bmp == null) {
                    status.text = "Не удалось открыть файл"
                    return@runOnUiThread
                }
                photoMode = true
                stopCamera()   // камера не нужна и только грузит процессор
                currentBitmap = bmp
                cache.clear()
                photoView.setImageBitmap(bmp)
                photoView.visibility = View.VISIBLE
                overlay.fitMode = true
                btnCamera.visibility = View.VISIBLE
                btnTorch.visibility = View.GONE
                recognizePhoto()
            }
        }.start()
    }

    private fun recognizePhoto() {
        val bmp = currentBitmap ?: return
        val gen = ++photoGen
        overlay.setItems(emptyList(), bmp.width, bmp.height)
        status.text = "Распознавание…"
        tStart = SystemClock.elapsedRealtime()
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener {
                tOcr = SystemClock.elapsedRealtime()
                handle(it, bmp.width, bmp.height, gen)
            }
            .addOnFailureListener {
                if (gen == photoGen) status.text = "Ошибка распознавания: ${it.message}"
            }
    }

    private fun exitPhotoMode() {
        photoMode = false
        photoGen++
        currentBitmap = null
        photoView.visibility = View.GONE
        photoView.setImageDrawable(null)
        overlay.fitMode = false
        overlay.setItems(emptyList(), 1, 1)
        btnCamera.visibility = View.GONE
        btnTorch.visibility = View.VISIBLE
        status.text = "Наведите камеру на текст"
        ensureCamera()
    }

    // Картинка уменьшается до 1600 px по длинной стороне — для OCR этого достаточно
    private fun decodeBitmap(uri: Uri): Bitmap? {
        try {
            val maxSide = 1600
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // точная подгонка до maxSide
            val longest = max(bmp.width, bmp.height)
            if (longest > maxSide) {
                val k = maxSide / longest.toFloat()
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true
                )
            }

            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bmp

            val m = Matrix().apply { postRotate(degrees) }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) {
            return null
        }
    }

    // ---------- Сохранение в галерею ----------

    private fun onSaveClicked() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val bmp = buildResultBitmap()
        if (bmp == null) {
            toast("Пока нечего сохранять")
            return
        }
        saveToGallery(bmp)
    }

    private fun buildResultBitmap(): Bitmap? {
        val density = resources.displayMetrics.density
        return try {
            if (photoMode) {
                val src = currentBitmap ?: return null
                val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
                val viewScale = min(
                    overlay.width / src.width.toFloat(),
                    overlay.height / src.height.toFloat()
                )
                overlay.drawInto(
                    Canvas(out), out.width.toFloat(), out.height.toFloat(),
                    true, density / viewScale
                )
                out
            } else {
                val pv = previewView.bitmap ?: return null
                val out = pv.copy(Bitmap.Config.ARGB_8888, true) ?: return null
                overlay.drawInto(
                    Canvas(out), out.width.toFloat(), out.height.toFloat(),
                    false, density
                )
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToGallery(bmp: Bitmap) {
        Thread {
            val name = "translate_${System.currentTimeMillis()}.jpg"
            val ok = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Skaner"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri == null) false
                    else {
                        contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.insertImage(
                        contentResolver, bmp, name, "Переводчик"
                    ) != null
                }
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                toast(if (ok) "Сохранено в галерею (Pictures/Skaner)" else "Не удалось сохранить")
            }
        }.start()
    }

    // ---------- Модели и перевод ----------

    private fun translatorFor(lang: String): Translator =
        translators.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.RUSSIAN)
                    .build()
            )
        }

    // Качаем модель одного языка и сразу загружаем её в память пробным переводом
    private fun warmUp(tag: String) {
        val lang = TranslateLanguage.fromLanguageTag(tag) ?: return
        if (lang == TranslateLanguage.RUSSIAN) return
        if (lang in modelReady) {
            translatorFor(lang).translate("a")
            return
        }
        status.text = "Подготовка языка ($tag)… нужен интернет при первом запуске"
        val tr = translatorFor(lang)
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady.add(lang)
                tr.translate("hello")   // прогрев: модель загружается в память
                status.text = "Готово. Наведите камеру на текст"
                if (photoMode) recognizePhoto()
            }
            .addOnFailureListener { e ->
                status.text = "Модель не скачалась: ${e.message}"
            }
    }

    // gen < 0 — результат камеры, gen >= 0 — результат для файла
    private fun handle(text: Text, w: Int, h: Int, gen: Int) {
        val isPhoto = gen >= 0
        if (!isPhoto && photoMode) {
            busy.set(false)
            return
        }
        if (isPhoto && gen != photoGen) return

        val blocks = text.textBlocks
            .filter {
                it.boundingBox != null &&
                        it.text.trim().length >= 2 &&
                        it.text.any { c -> c.isLetter() }
            }
            .sortedByDescending { it.boundingBox!!.width() * it.boundingBox!!.height() }
            .take(if (isPhoto) 15 else 8)

        if (blocks.isEmpty()) {
            overlay.setItems(emptyList(), w, h)
            if (isPhoto) status.text = "Текст не найден. Проверьте язык в списке сверху"
            else busy.set(false)
            return
        }

        if (isPhoto) status.text = "Перевод…"

        val sep = if (scriptHint == "zh" || scriptHint == "ja") "" else " "
        val results = arrayOfNulls<OverlayView.Item>(blocks.size)
        val pending = AtomicInteger(blocks.size)

        blocks.forEachIndexed { i, block ->
            val src = block.text.replace("\n", sep).trim()
            translate(src) { tr ->
                if (tr != null && !tr.equals(src, ignoreCase = true)) {
                    results[i] = OverlayView.Item(block.boundingBox!!, tr)
                }
                val done = pending.decrementAndGet() == 0
                val list = results.filterNotNull()

                if (isPhoto) {
                    if (gen == photoGen) {
                        // фото: показываем блоки по мере готовности
                        overlay.setItems(list, w, h)
                        if (done) {
                            val now = SystemClock.elapsedRealtime()
                            status.text = "Готово: ${list.size} бл. • распознавание " +
                                    "${tOcr - tStart} мс • перевод ${now - tOcr} мс"
                        }
                    }
                } else if (done) {
                    if (!photoMode) {
                        overlay.setItems(list, w, h)
                        if (list.isNotEmpty()) status.text = "Переведено блоков: ${list.size}"
                    }
                    busy.set(false)
                }
            }
        }
    }

    private fun keyOf(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun translate(src: String, cb: (String?) -> Unit) {
        val key = keyOf(src)
        cache[key]?.let {
            cb(it.ifEmpty { null })
            return
        }

        val hint = scriptHint
        if (hint != null) {
            translateWith(src, key, hint, cb)
        } else {
            langId.identifyLanguage(src)
                .addOnSuccessListener { code ->
                    translateWith(src, key, if (code == "und") "en" else code, cb)
                }
                .addOnFailureListener { cb(null) }
        }
    }

    private fun translateWith(src: String, key: String, tag: String, cb: (String?) -> Unit) {
        val lang = TranslateLanguage.fromLanguageTag(tag)
        if (lang == null || lang == TranslateLanguage.RUSSIAN) {
            cache[key] = ""
            cb(null)
            return
        }

        var finished = false
        val finish: (String?) -> Unit = { r ->
            if (!finished) {
                finished = true
                cb(r)
            }
        }

        val translator = translatorFor(lang)

        val doTranslate = {
            translator.translate(src)
                .addOnSuccessListener { r ->
                    cache[key] = r
                    finish(r)
                }
                .addOnFailureListener { e ->
                    status.text = "Ошибка перевода: ${e.message}"
                    finish(null)
                }
            Unit
        }

        if (lang in modelReady) {
            doTranslate()
            return
        }

        status.text = "Скачивание языковой модели ($tag)…"
        handler.postDelayed({
            if (!finished) {
                status.text = "Модель не скачалась. Проверьте интернет и Google Play Services"
                finish(null)
            }
        }, 30000)

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady.add(lang)
                doTranslate()
            }
            .addOnFailureListener { e ->
                status.text = "Модель не скачалась: ${e.message}"
                finish(null)
            }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        recognizer.close()
        langId.close()
        translators.values.forEach { it.close() }
    }
}