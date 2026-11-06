package com.example.miafotocamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.miafotocamera.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (haPermessoCamera()) {
            avviaCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE)
        }

        binding.btnScatta.setOnClickListener { scattaFoto() }
        binding.btnCambiaCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            avviaCamera()
        }
    }

    private fun haPermessoCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun avviaCamera() {
        val futureProvider = ProcessCameraProvider.getInstance(this)
        futureProvider.addListener({
            val provider = futureProvider.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                .build()

            provider.unbindAll()
            try {
                provider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Errore avvio camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scattaFoto() {
        val capture = imageCapture ?: return

        // 1. Scatta in un file temporaneo interno
        val fileTemp = File(cacheDir, "foto_temp.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(fileTemp).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        // 2. Sfondo bianco applicato in automatico
                        val originale = BitmapFactory.decodeFile(fileTemp.absolutePath)
                        val conSfondoBianco = applicaSfondoBianco(originale)

                        withContext(Dispatchers.Main) {
                            salvaInGalleria(conSfondoBianco)
                        }
                        fileTemp.delete()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Errore: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * Riconosce la persona nella foto e la ricompone su sfondo bianco puro.
     */
    private fun applicaSfondoBianco(bitmap: Bitmap?): Bitmap {
        if (bitmap == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )

        // Esegui la segmentazione in modo sincrono attendendo il task
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val task = segmenter.process(inputImage)

        // Attendi il risultato (bloccante, siamo già su Dispatchers.Default)
        while (!task.isComplete) { Thread.sleep(50) }

        val risultato = task.result ?: return bitmap
        val mask = risultato.buffer // FloatBuffer: valore >= 0.5 = primo piano
        val w = risultato.width
        val h = risultato.height

        // Adatta la foto alle dimensioni della maschera
        val ridimensionata = if (bitmap.width != w || bitmap.height != h)
            Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap

        val pixels = IntArray(w * h)
        ridimensionata.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            if (mask.get(i) < 0.5f) pixels[i] = Color.WHITE // sfondo = bianco
        }

        val finale = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        finale.setPixels(pixels, 0, w, 0, 0, w, h)
        segmenter.close()
        return finale
    }

    private fun salvaInGalleria(bitmap: Bitmap) {
        val nomeFile = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ITALY)
            .format(System.currentTimeMillis()) + ".jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nomeFile)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MiaFotocamera")
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )

        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
            binding.ivUltimaFoto.setImageBitmap(bitmap)
            Toast.makeText(this, "Foto salvata con sfondo bianco!", Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(this, "Errore salvataggio", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            avviaCamera()
        } else {
            Toast.makeText(this, "Permesso camera negato", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object { private const val REQUEST_CODE = 10 }
}
