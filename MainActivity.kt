// MainActivity.kt
import android.Manifest
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var vibrator: Vibrator
    private var mediaPlayer: MediaPlayer? = null
    private var isAlarmActive = false
    private lateinit var alarmUri: Uri
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnStop = findViewById(R.id.btn_stop)

        setupAlarmSystem()
        checkPermissions()
        setupStopButton()
    }

    private fun setupAlarmSystem() {
        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    private fun checkPermissions() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupStopButton() {
        btnStop.setOnClickListener {
            stopAlarm()
            resetApplication()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ColorAnalyzer()) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private inner class ColorAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val bitmap = image.toBitmap() ?: return
            val hasGreen = detectGreenColor(bitmap)

            runOnUiThread {
                if (!hasGreen && !isAlarmActive) {
                    startAlarm()
                }
            }
            image.close()
        }

        private fun detectGreenColor(bitmap: Bitmap): Boolean {
            val lowerGreen = floatArrayOf(80f, 0.3f, 0.3f)
            val upperGreen = floatArrayOf(160f, 1f, 1f)
            
            val step = 10
            for (x in 0 until bitmap.width step step) {
                for (y in 0 until bitmap.height step step) {
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                    
                    if (hsv[0] in lowerGreen[0]..upperGreen[0] &&
                        hsv[1] >= lowerGreen[1] &&
                        hsv[2] >= lowerGreen[2]) {
                        return true
                    }
                }
            }
            return false
        }
    }

    private fun startAlarm() {
        isAlarmActive = true
        showStopButton()
        startVibration()
        playAlarmSound()
    }

    private fun showStopButton() {
        runOnUiThread {
            previewView.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
        }
    }

    private fun startVibration() {
        val vibrationPattern = longArrayOf(500, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, 0))
        } else {
            vibrator.vibrate(vibrationPattern, 0)
        }
    }

    private fun playAlarmSound() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                isLooping = true
                start()
            } ?: throw Exception("Failed to create MediaPlayer")
        } catch (e: Exception) {
            Log.e(TAG, "Alarm error: ${e.message}")
            RingtoneManager.getRingtone(this, alarmUri).play()
        }
    }

    private fun stopAlarm() {
        isAlarmActive = false
        vibrator.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun resetApplication() {
        runOnUiThread {
            previewView.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaPlayer?.release()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "GreenAlarmApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
