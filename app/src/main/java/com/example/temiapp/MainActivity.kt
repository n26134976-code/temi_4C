package com.example.temiapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.robotemi.sdk.Robot
import com.robotemi.sdk.Robot.WakeupWordListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.voice.WakeupOrigin
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(),
    OnRobotReadyListener,
    WakeupWordListener {

    private lateinit var robot: Robot
    private lateinit var recorder: AudioRecorderHelper
    private val asrClient = HospitalAsrClient()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var voiceOverlay: View
    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvVoiceTranscript: TextView

    @Volatile
    private var isCapturingVoiceCommand = false
    private var pendingAudioFile: File? = null
    private var stopRecordingRunnable: Runnable? = null
    private var pendingNavigateRunnable: Runnable? = null
    private var autoHideVoiceUiRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        robot = Robot.getInstance()
        recorder = AudioRecorderHelper(this)

        voiceOverlay = findViewById(R.id.voice_overlay)
        tvVoiceStatus = findViewById(R.id.tv_voice_status)
        tvVoiceTranscript = findViewById(R.id.tv_voice_transcript)
        hideVoiceUi()

        findViewById<Button>(R.id.btn_to_navigation).setOnClickListener {
            startActivity(Intent(this, NavigationActivity::class.java))
        }

        findViewById<Button>(R.id.btn_to_video).setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java))
        }

        findViewById<Button>(R.id.btn_to_ward_guide).setOnClickListener {
            startActivity(Intent(this, WardGuideActivity::class.java))
        }

        findViewById<Button>(R.id.btn_to_broadcast).setOnClickListener {
            startActivity(Intent(this, BroadcastActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
        robot.addWakeupWordListener(this)
    }

    override fun onStop() {
        robot.removeWakeupWordListener(this)
        robot.removeOnRobotReadyListener(this)
        cancelVoiceCapture()
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRobotReady(isReady: Boolean) {
        if (isReady) robot.hideTopBar()
    }

    override fun onWakeupWord(wakeupWord: String, direction: Int, wakeupOrigin: WakeupOrigin) {
        Log.d(TAG, "onWakeupWord: wakeupWord=$wakeupWord, direction=$direction, origin=$wakeupOrigin")
        startHospitalAsrFlow()
    }

    private fun startHospitalAsrFlow() {
        if (isCapturingVoiceCommand) return

        pendingNavigateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingNavigateRunnable = null

        if (!hasRecordAudioPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            showVoiceUi("等待錄音權限")
            hideVoiceUiDelayed(1500L)
            Toast.makeText(this, "請先允許錄音權限", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            showVoiceUi("正在聆聽...")
            pendingAudioFile = recorder.startRecording()
            isCapturingVoiceCommand = true

            stopRecordingRunnable?.let { mainHandler.removeCallbacks(it) }
            stopRecordingRunnable = Runnable { stopAndSendToHospitalAsr() }
            mainHandler.postDelayed(
                stopRecordingRunnable!!,
                HospitalAsrConfig.RECORDING_MILLIS
            )
        } catch (e: Exception) {
            isCapturingVoiceCommand = false
            pendingAudioFile = null
            Log.e(TAG, "startHospitalAsrFlow failed", e)
            showVoiceUi("無法開始錄音")
            hideVoiceUiDelayed(2000L)
            Toast.makeText(this, "無法開始錄音：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAndSendToHospitalAsr() {
        stopRecordingRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRecordingRunnable = null

        val audioFile = recorder.stopRecording(deleteBrokenFile = true)
        isCapturingVoiceCommand = false
        pendingAudioFile = null

        if (audioFile == null || !audioFile.exists() || audioFile.length() <= 0L) {
            showVoiceUi("錄音失敗，請再說一次")
            hideVoiceUiDelayed(2000L)
            Toast.makeText(this, "錄音失敗，請再說一次", Toast.LENGTH_SHORT).show()
            return
        }

        showVoiceUi("辨識中...")

        ioExecutor.execute {
            try {
                val result = asrClient.transcribe(audioFile)
                val transcript = result.transcript
                Log.d(TAG, "ASR transcript=$transcript")
                runOnUiThread {
                    handleAsrTranscript(transcript)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hospital ASR failed", e)
                runOnUiThread {
                    showVoiceUi("ASR 失敗")
                    hideVoiceUiDelayed(2500L)
                    Toast.makeText(this, "ASR 失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runCatching { audioFile.delete() }
            }
        }
    }

    private fun handleAsrTranscript(transcript: String) {
        val spoken = transcript.trim()

        if (spoken.isBlank()) {
            showVoiceUi("沒有辨識到語音內容")
            hideVoiceUiDelayed(2000L)
            Toast.makeText(this, "沒有辨識到語音內容", Toast.LENGTH_SHORT).show()
            return
        }

        showVoiceUi("辨識結果", spoken)

        val target = SpeechTargetParser.parseTargetLocation(spoken)
        if (target == null) {
            hideVoiceUiDelayed(3000L)
            Toast.makeText(this, "辨識結果：$spoken", Toast.LENGTH_LONG).show()
            return
        }

        pendingNavigateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingNavigateRunnable = Runnable {
            hideVoiceUi()
            startActivity(
                Intent(this, NavigationActivity::class.java).apply {
                    putExtra(NavigationActivity.EXTRA_TARGET_LOCATION, target)
                    putExtra(NavigationActivity.EXTRA_SOURCE_QUERY, spoken)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
        mainHandler.postDelayed(pendingNavigateRunnable!!, NAVIGATE_DELAY_MILLIS)
    }

    private fun cancelVoiceCapture() {
        stopRecordingRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRecordingRunnable = null

        pendingNavigateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingNavigateRunnable = null

        autoHideVoiceUiRunnable?.let { mainHandler.removeCallbacks(it) }
        autoHideVoiceUiRunnable = null

        pendingAudioFile?.let { runCatching { it.delete() } }
        pendingAudioFile = null

        recorder.cancelRecording()
        isCapturingVoiceCommand = false
        hideVoiceUi()
    }

    private fun showVoiceUi(status: String, transcript: String = "") {
        autoHideVoiceUiRunnable?.let { mainHandler.removeCallbacks(it) }
        voiceOverlay.visibility = View.VISIBLE
        tvVoiceStatus.text = status
        tvVoiceTranscript.text = transcript
    }

    private fun hideVoiceUi() {
        autoHideVoiceUiRunnable?.let { mainHandler.removeCallbacks(it) }
        autoHideVoiceUiRunnable = null
        voiceOverlay.visibility = View.GONE
        tvVoiceStatus.text = ""
        tvVoiceTranscript.text = ""
    }

    private fun hideVoiceUiDelayed(delayMillis: Long = 2000L) {
        autoHideVoiceUiRunnable?.let { mainHandler.removeCallbacks(it) }
        autoHideVoiceUiRunnable = Runnable { hideVoiceUi() }
        mainHandler.postDelayed(autoHideVoiceUiRunnable!!, delayMillis)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startHospitalAsrFlow()
            } else {
                showVoiceUi("未授權錄音")
                hideVoiceUiDelayed(2000L)
                Toast.makeText(this, "未授權錄音，無法使用 hey temi 語音導覽", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val NAVIGATE_DELAY_MILLIS = 1200L
    }
}