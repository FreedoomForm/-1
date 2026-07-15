package com.example

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.ai.CommandExecutor
import com.example.data.ai.CommandResult
import com.example.data.ai.MistralApiService
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOverdue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Состояние экрана сканера.
 *
 *   Permission     — нет разрешения на камеру, показываем кнопку "Запросить"
 *   Preview        — камера открыта, ждём нажатия кнопки захвата
 *   Captured       — есть хотя бы одно фото, можно добавить ещё или отправить
 *   Processing     — фото отправлено в Mistral, ждём ответ
 *   Done           — все команды выполнены, показываем результат
 *   Error          — что-то пошло не так
 */
private enum class ScannerState {
    Permission, Preview, Captured, Processing, Done, Error
}

/**
 * Одна захваченная (или выбранная из галереи) фотография.
 *
 * @param file      локальный JPEG-файл (отправляется в Mistral OCR)
 * @param bitmap    декодированный bitmap для превью в UI
 * @param source    "camera" или "gallery" — для отображения иконки
 */
private data class CapturedPhoto(
    val file: File,
    val bitmap: Bitmap,
    val source: String
)

/**
 * Экран сканера документов с Mistral OCR.
 *
 * Pipeline:
 *   1. Пользователь делает ОДНО ИЛИ НЕСКОЛЬКО фото документов (или выбирает из галереи)
 *   2. Каждое фото → MistralApiService.performOcr → текст
 *   3. Конкатенированный текст → MistralApiService.generateCommand → JSON-команды
 *   4. JSON-команды → CommandExecutor.execute → создание сущностей в БД
 *   5. Показываем результат каждой команды
 *
 * Особенности UI:
 *   • В превью камеры в левом нижнем углу — миниатюра последней фотографии
 *     из галереи устройства. Тап открывает Photo Picker для выбора фото.
 *   • После первого снимка можно нажать "1. Снять еще фото" — фото добавится
 *     в горизонтальную карусель. Можно снять сколько угодно.
 *   • "Отправить" отправляет ВСЕ снимки (каждый через OCR, тексты склеиваются).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var state by remember { mutableStateOf(ScannerState.Preview) }
    var photos by remember { mutableStateOf<List<CapturedPhoto>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("") }
    var commandResults by remember { mutableStateOf<List<CommandResult>>(emptyList()) }

    // ── Последняя фотография из галереи (для миниатюры в углу превью) ──────
    var latestGalleryBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // ── Проверка разрешения камеры ────────────────────────────────────────
    val hasCameraPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission.value = granted
        if (!granted) {
            state = ScannerState.Permission
            statusMessage = "Kamera ruxsati kerak — skaner ishlashi uchun"
        } else {
            state = ScannerState.Preview
        }
    }

    // ── Разрешение на чтение галереи (для миниатюры последнего фото) ───────
    // На Android 13+ это READ_MEDIA_IMAGES, ниже — READ_EXTERNAL_STORAGE.
    val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val hasGalleryPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, galleryPermission)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasGalleryPermission.value = granted
        if (granted) {
            // Разрешение получено — пробуем ещё раз загрузить миниатюру
            coroutineScope.launch {
                latestGalleryBitmap = withContext(Dispatchers.IO) {
                    loadLatestGalleryThumbnail(context)
                }
            }
        }
    }

    // ── Photo Picker (PickVisualMedia) — системный выбор фото из галереи ──
    // Не требует READ_EXTERNAL_STORAGE на Android 13+. Открывает плавающее
    // окно системной галереи.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val photo = withContext(Dispatchers.IO) {
                    uriToCapturedPhoto(context, uri, source = "gallery")
                }
                if (photo != null) {
                    photos = photos + photo
                    state = ScannerState.Captured
                } else {
                    statusMessage = "Galereyadan rasm yuklab bo'lmadi"
                }
            }
        }
    }

    // ── Первичная инициализация ───────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (!hasCameraPermission.value) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        // Если разрешение на галерею уже есть — сразу грузим миниатюру
        if (hasGalleryPermission.value) {
            latestGalleryBitmap = withContext(Dispatchers.IO) {
                loadLatestGalleryThumbnail(context)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // На Android 13+ тихо запрашиваем READ_MEDIA_IMAGES — если пользователь
            // даст, миниатюра появится. Если нет — превью работает без неё.
            galleryPermissionLauncher.launch(galleryPermission)
        }
    }

    // ── CameraX setup ─────────────────────────────────────────────────────
    val imageCapture: ImageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // ── Обработка кнопки "Зафиксировать кадр" ─────────────────────────────
    fun takePhoto() {
        if (!hasCameraPermission.value) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val photoFile = File(
            context.cacheDir,
            "scan_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    if (bitmap != null) {
                        photos = photos + CapturedPhoto(photoFile, bitmap, "camera")
                        state = ScannerState.Captured
                    } else {
                        statusMessage = "Surat olinmadi (bitmap null)"
                        state = ScannerState.Error
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScannerScreen", "Photo capture failed", exception)
                    statusMessage = "Surat olinmadi: ${exception.message}"
                    state = ScannerState.Error
                }
            }
        )
    }

    // ── Обработка кнопки "1. Снять еще фото" ──────────────────────────────
    // Возвращаемся в режим превью камеры, сохраняя уже сделанные фото.
    fun takeAnotherPhoto() {
        if (!hasCameraPermission.value) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        state = ScannerState.Preview
    }

    // ── Обработка кнопки "Отправить" ──────────────────────────────────────
    fun sendToMistral() {
        if (photos.isEmpty()) return
        state = ScannerState.Processing
        statusMessage = "Mistral OCR'ga yuborilmoqda..."
        commandResults = emptyList()

        coroutineScope.launch {
            try {
                val mistral = MistralApiService()
                val executor = CommandExecutor(context)

                // Шаг 1: OCR для каждого фото, тексты склеиваем
                statusMessage = "1/3 OCR — matn tanilmoqda... (${photos.size} ta rasm)"
                val imageFiles = photos.map { it.file }
                val ocrText = withContext(Dispatchers.IO) {
                    mistral.performOcrMultiple(imageFiles)
                }
                if (ocrText.isBlank()) {
                    statusMessage = "OCR hech narsa topa olmadi. Surat sifatini tekshiring."
                    state = ScannerState.Error
                    return@launch
                }

                // Шаг 2: генерация JSON-команд
                statusMessage = "2/3 Mistral Large — komandalar tuzilmoqda..."
                val mistralResponse = withContext(Dispatchers.IO) {
                    mistral.generateCommand(ocrText)
                }
                if (mistralResponse.isBlank()) {
                    statusMessage = "Mistral javob bermadi. Qayta urinib ko'ring."
                    state = ScannerState.Error
                    return@launch
                }

                // Шаг 3: выполнение команд
                statusMessage = "3/3 Komandalar bajarilmoqda..."
                val (success, results) = executor.execute(mistralResponse)
                commandResults = results
                state = ScannerState.Done
                statusMessage = if (success) "Bajarildi" else "Qisman bajarildi"
            } catch (e: Exception) {
                Log.e("ScannerScreen", "Mistral pipeline failed", e)
                statusMessage = "Xato: ${e.message}"
                state = ScannerState.Error
            }
        }
    }

    fun resetToPreview() {
        photos = emptyList()
        statusMessage = ""
        commandResults = emptyList()
        state = if (hasCameraPermission.value) ScannerState.Preview else ScannerState.Permission
    }

    // Удалить конкретное фото из списка (тап по крестику на превью)
    fun removePhoto(index: Int) {
        photos = photos.toMutableList().also { it.removeAt(index) }
        if (photos.isEmpty()) {
            state = if (hasCameraPermission.value) ScannerState.Preview else ScannerState.Permission
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = { Text("Skaner", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClaudeBackground,
                    titleContentColor = ClaudeText,
                    navigationIconContentColor = ClaudeText
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                ScannerState.Permission -> {
                    PermissionView(
                        message = statusMessage.ifEmpty {
                            "Skaner ishlashi uchun kamera ruxsati kerak"
                        },
                        onRequestPermission = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }

                ScannerState.Preview -> {
                    if (hasCameraPermission.value) {
                        CameraPreviewView(
                            imageCapture = imageCapture,
                            lifecycleOwner = lifecycleOwner,
                            latestGalleryThumbnail = latestGalleryBitmap,
                            onCaptureClick = { takePhoto() },
                            onGalleryClick = {
                                // Открываем плавающее окно системной галереи
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                    } else {
                        PermissionView(
                            message = "Kamera ruxsati kerak",
                            onRequestPermission = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                }

                ScannerState.Captured -> {
                    CapturedPhotoView(
                        photos = photos,
                        onTakeAnother = { takeAnotherPhoto() },
                        onAddFromGallery = {
                            // Открываем плавающее окно системной галереи для
                            // добавления дополнительных фото к уже сделанным.
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = { idx -> removePhoto(idx) },
                        onSend = { sendToMistral() }
                    )
                }

                ScannerState.Processing -> {
                    ProcessingView(statusMessage = statusMessage)
                }

                ScannerState.Done -> {
                    ResultView(
                        results = commandResults,
                        statusMessage = statusMessage,
                        onNewScan = { resetToPreview() },
                        onBack = onBack
                    )
                }

                ScannerState.Error -> {
                    ErrorView(
                        message = statusMessage,
                        onRetry = { resetToPreview() },
                        onBack = onBack
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// УТИЛИТЫ ДЛЯ ГАЛЕРЕИ
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Загрузить миниатюру последней фотографии из галереи устройства.
 *
 * Использует MediaStore.Images.Media — самую последнюю запись.
 * Возвращает Bitmap или null, если галерея пуста / нет разрешения.
 *
 * Должно вызываться в фоновом потоке (Dispatchers.IO).
 */
private fun loadLatestGalleryThumbnail(context: Context): Bitmap? {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.ORIENTATION
    )

    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    var cursor: Cursor? = null
    try {
        cursor = context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        ) ?: return null

        if (!cursor.moveToFirst()) return null

        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val orientCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
        val id = cursor.getLong(idCol)
        val orientation = cursor.getInt(orientCol)

        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id
        )

        // Декодируем с уменьшенным размером (для миниатюры достаточно 200px)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 4  // ~1/4 разрешения
        }
        val bmp = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            ?: return null
        return bmp
    } catch (e: SecurityException) {
        Log.w("GalleryThumb", "No permission to read gallery: ${e.message}")
        return null
    } catch (e: Exception) {
        Log.w("GalleryThumb", "Failed to load latest photo: ${e.message}")
        return null
    } finally {
        cursor?.close()
    }
}

/**
 * Скопировать Uri-фото из галереи во временный файл кэша приложения.
 *
 * Mistral OCR требует файл (читаем байты → base64). Поэтому выбранное
 * из галереи фото сначала копируется в cacheDir, а потом отправляется
 * в [MistralApiService.performOcr].
 *
 * Должно вызываться в фоновом потоке (Dispatchers.IO).
 *
 * @return [CapturedPhoto] или null при ошибке декодирования
 */
private fun uriToCapturedPhoto(
    context: Context,
    uri: Uri,
    source: String
): CapturedPhoto? {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap: Bitmap = input.use { BitmapFactory.decodeStream(it) } ?: return null

        val outFile = File(
            context.cacheDir,
            "scan_gallery_${System.currentTimeMillis()}.jpg"
        )
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        CapturedPhoto(outFile, bitmap, source)
    } catch (e: Exception) {
        Log.e("ScannerScreen", "Failed to copy gallery uri to file", e)
        null
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// COMPOSABLES
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Превью камеры с круглой кнопкой захвата внизу и миниатюрой последней
 * фотографии галереи в левом нижнем углу.
 */
@Composable
private fun CameraPreviewView(
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    latestGalleryThumbnail: Bitmap?,
    modifier: Modifier = Modifier,
    onCaptureClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        Log.e("CameraPreviewView", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Инструкция сверху
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
        ) {
            Text(
                text = "Hujjatni kadrga soling va pastdagi tugmani bosing",
                color = Color.White,
                modifier = Modifier.padding(12.dp),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        // Миниатюра последней фотографии из галереи — левый нижний угол
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 64.dp)
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable { onGalleryClick() }
        ) {
            if (latestGalleryThumbnail != null) {
                Image(
                    bitmap = latestGalleryThumbnail.asImageBitmap(),
                    contentDescription = "Galereyadan tanlash",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Нет разрешения или галерея пуста — показываем иконку
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = "Galereya",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            // Маленький бейдж сверху справа — подсказка что можно открыть галерею
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(ClaudeAccent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Круглая кнопка захвата внизу по центру
        IconButton(
            onClick = onCaptureClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(80.dp)
                .background(Color.White, CircleShape)
                .border(4.dp, ClaudeAccent, CircleShape)
        ) {
            Icon(
                Icons.Default.Camera,
                contentDescription = "Surat olish",
                tint = ClaudeAccent,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

// ── Permission view ────────────────────────────────────────────────────────
@Composable
private fun PermissionView(
    message: String,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = ClaudeAccent
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = ClaudeText,
            textAlign = TextAlign.Center,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ruxsat so'rash")
        }
    }
}

/**
 * Превью одного или нескольких сделанных/выбранных фото.
 *
 * Если фото одно — показываем его большим.
 * Если несколько — сверху карусель (горизонтальный скролл), под ней кнопки.
 *
 * Над "Отправить" две кнопки:
 *   1. "Yana suratga olish" — вернуться в камеру, сохраняя уже сделанные фото
 *   2. "Yana surat qo'shish" — открыть галерею и добавить ещё фото к списку
 */
@Composable
private fun CapturedPhotoView(
    photos: List<CapturedPhoto>,
    onTakeAnother: () -> Unit,
    onAddFromGallery: () -> Unit,
    onRemove: (Int) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Основное превью: если фото несколько — карусель, иначе одно большое
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ClaudeCard)
                .border(1.dp, ClaudeDivider, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (photos.isEmpty()) {
                Text("Surat yuklanmoqda...", color = ClaudeTextSecondary)
            } else {
                // Горизонтальная карусель со всеми фото
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    photos.forEachIndexed { index, photo ->
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(240.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black)
                        ) {
                            Image(
                                bitmap = photo.bitmap.asImageBitmap(),
                                contentDescription = "Photo ${index + 1}",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Fit
                            )
                            // Бейдж с номером фото
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}/${photos.size}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Бейдж источника (камера/галерея)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (photo.source == "camera") "Kamera" else "Galereya",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                            // Кнопка удаления (крестик)
                            IconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "O'chirish",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Счётчик фото
        if (photos.size > 1) {
            Text(
                text = "${photos.size} ta rasm tanlandi",
                color = ClaudeTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // ── Кнопка "2. Yana surat qo'shish" — открыть галерею ────────────
        // Самая верхняя из двух дополнительных кнопок. Открывает системный
        // Photo Picker — пользователь может выбрать ещё одно (или несколько)
        // фото из галереи, они добавятся к списку уже сделанных.
        OutlinedButton(
            onClick = onAddFromGallery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeAccent),
            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeAccent)
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("2. Yana surat qo'shish")
        }

        // ── Кнопка "1. Yana suratga olish" — вернуться в камеру ──────────
        OutlinedButton(
            onClick = onTakeAnother,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeAccent),
            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeAccent)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("1. Yana suratga olish")
        }

        // ── Кнопка "Отправить" ────────────────────────────────────────────
        Button(
            onClick = onSend,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent),
            enabled = photos.isNotEmpty()
        ) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Yuborish (${photos.size})")
        }
    }
}

// ── Processing view ────────────────────────────────────────────────────────
@Composable
private fun ProcessingView(statusMessage: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = ClaudeAccent, strokeWidth = 4.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = statusMessage,
            color = ClaudeText,
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Bu bir necha soniya davom etishi mumkin...",
            color = ClaudeTextSecondary,
            textAlign = TextAlign.Center,
            fontSize = 14.sp
        )
    }
}

// ── Result view ────────────────────────────────────────────────────────────
@Composable
private fun ResultView(
    results: List<CommandResult>,
    statusMessage: String,
    onNewScan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Заголовок результата
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ClaudeCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = null,
                    tint = StatusOk,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = statusMessage,
                        color = ClaudeText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "${results.size} ta amal bajarildi",
                        color = ClaudeTextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Список результатов
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success) ClaudeAccentBg.copy(alpha = 0.4f)
                        else Color(0xFFFEE2E2)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = result.message,
                        color = if (result.success) ClaudeText else StatusOverdue,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Кнопки внизу
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeText)
            ) {
                Text("Tugatish")
            }
            Button(
                onClick = onNewScan,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) {
                Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Yangi skan")
            }
        }
    }
}

// ── Error view ─────────────────────────────────────────────────────────────
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = StatusOverdue
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Xato yuz berdi",
            color = ClaudeText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = ClaudeTextSecondary,
            textAlign = TextAlign.Center,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeText)
            ) {
                Text("Yopish")
            }
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) {
                Text("Qayta urinish")
            }
        }
    }
}
