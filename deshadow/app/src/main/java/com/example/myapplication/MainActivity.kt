package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.util.Size // <--- ДОБАВИТЬ ИМПОРТ
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import androidx.camera.core.ExperimentalGetImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min // <--- ДОБАВИТЬ ИМПОРТ

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraPreview: PreviewView
    private lateinit var processedImageView: ImageView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        processedImageView = findViewById(R.id.processedImageView)
        statusText = findViewById(R.id.statusText)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                // Попробуем установить целевое соотношение сторон, как у PreviewView
                // .setTargetAspectRatio(AspectRatio.RATIO_4_3) // или RATIO_16_9, если нужно
                .build().also {
                    it.setSurfaceProvider(cameraPreview.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Можно установить целевое разрешение или соотношение сторон для ImageAnalysis,
                // чтобы попытаться согласовать его с превью.
                // .setTargetResolution(Size(640, 480)) // Пример
                // .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ShadowRemovalAnalyzer { bitmap ->
                        runOnUiThread {
                            if (bitmap != null) {
                                processedImageView.setImageBitmap(bitmap)
                                // statusText.text = "Processing: ${bitmap.width}x${bitmap.height}" // Для отладки размеров
                            } else {
                                statusText.text = "Bitmap conversion failed"
                            }
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis)
                runOnUiThread { statusText.text = "Camera Started" }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                runOnUiThread { statusText.text = "Camera bind failed" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private class ShadowRemovalAnalyzer(private val listener: (Bitmap?) -> Unit) : ImageAnalysis.Analyzer {

        private val SHADOW_VALUE_THRESHOLD = 0.3f
        private val SHADOW_SATURATION_MIN_THRESHOLD = 0.05f
        private val BRIGHTENING_FACTOR = 1.5f

        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            // Получаем Bitmap УЖЕ С УЧЕТОМ ПОВОРОТА
            val bitmap = imageProxy.toBitmapWithRotation()

            if (bitmap != null) {
                val processedBitmap = processImageForShadows(bitmap)
                listener(processedBitmap)
            } else {
                listener(null)
            }
            imageProxy.close()
        }

        private fun processImageForShadows(originalBitmap: Bitmap): Bitmap {
            // Теперь originalBitmap уже должен быть в правильной ориентации
            val width = originalBitmap.width
            val height = originalBitmap.height
            val processedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)

            val pixels = IntArray(width * height)
            processedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val hsv = FloatArray(3)

            for (i in pixels.indices) {
                val color = pixels[i]
                Color.colorToHSV(color, hsv)

                if (hsv[2] < SHADOW_VALUE_THRESHOLD && hsv[1] > SHADOW_SATURATION_MIN_THRESHOLD) {
                    hsv[2] = min(1.0f, hsv[2] * BRIGHTENING_FACTOR) // Используем kotlin.math.min
                    pixels[i] = Color.HSVToColor(hsv)
                }
            }

            processedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return processedBitmap
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ShadowRemoverApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

// Новая функция-расширение, которая также поворачивает Bitmap
@ExperimentalGetImage
fun ImageProxy.toBitmapWithRotation(): Bitmap? {
    val bitmap = this.toBitmap()
    return bitmap?.let {
        val rotationDegrees = this.imageInfo.rotationDegrees
        if (rotationDegrees == 0) {
            it // Если поворот не нужен, возвращаем как есть
        } else {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(
                it, 0, 0, it.width, it.height, matrix, true
            )
            rotatedBitmap
        }
    }
}


// Ваша существующая функция toBitmap() без изменений
@ExperimentalGetImage
fun ImageProxy.toBitmap(): Bitmap? {
    val image = this.image ?: return null
    // val width = this.width // Эти width/height могут быть до поворота
    // val height = this.height
    // Для YuvImage лучше использовать width и height из imageProxy напрямую,
    // так как они соответствуют данным в буферах
    val yuvImageWidth = this.width
    val yuvImageHeight = this.height


    if (image.format != ImageFormat.YUV_420_888) {
        Log.e("ImageProxyToBitmap", "Unsupported image format: ${image.format}")
        return null
    }

    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)

    val vPlane = image.planes[2]
    val uPlane = image.planes[1]

    if (vPlane.pixelStride == 1 && uPlane.pixelStride == 1) {
        val vByteArray = ByteArray(vSize)
        vPlane.buffer.get(vByteArray)
        val uByteArray = ByteArray(uSize)
        uPlane.buffer.get(uByteArray)

        var vuIndexInNv21 = 0
        for (i in 0 until vSize) {
            nv21[ySize + vuIndexInNv21++] = vByteArray[i]
            nv21[ySize + vuIndexInNv21++] = uByteArray[i]
        }
    } else {
        vBuffer.rewind()
        uBuffer.rewind()
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        Log.w("ImageProxyToBitmap", "Using YV12 order for V/U planes due to pixelStride > 1 or non-I420. YuvImage might handle this.")
    }

    return try {
        // Используем yuvImageWidth и yuvImageHeight, которые соответствуют данным в nv21
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, yuvImageWidth, yuvImageHeight, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImageWidth, yuvImageHeight), 90, out)
        val imageBytes = out.toByteArray()
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (e: Exception) {
        Log.e("ImageProxyToBitmap", "Error converting YUV to Bitmap: ${e.message}", e)
        null
    }
}