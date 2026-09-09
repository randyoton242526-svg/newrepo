package com.example.voiceassistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity  (FINAL — with inline download progress)
 * -------------------------------------------------------
 * No separate setup screen needed. The download progress is shown
 * directly in this activity's existing UI:
 *   - tvStatus shows "Downloading model (42%)"
 *   - progressBar shows the actual byte-level percentage
 *   - Chat log shows milestone messages (25%, 50%, 75%, 100%)
 * Once the download finishes the UI transitions automatically to IDLE.
 */
class MainActivity : AppCompatActivity() {

    // ---- Views --------------------------------------------------------------
    private lateinit var tvStatus       : TextView
    private lateinit var waveformView   : WaveformView
    private lateinit var btnMic         : ImageButton
    private lateinit var progressBar    : ProgressBar
    private lateinit var tvDebugOverlay : TextView
    private lateinit var rvConversation : RecyclerView

    private val adapter   = ConversationAdapter()
    private val pulseAnim by lazy { AnimationUtils.loadAnimation(this, R.anim.pulse) }

    // ---- Service binding ----------------------------------------------------
    private var service: VoiceAssistantService? = null
    private var bound   = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceAssistantService.LocalBinder).getService()
            bound   = true

            service?.uiCallbacks = object : VoiceAssistantService.UiCallbacks {

                override fun onTranscript(text: String) = runOnUiThread {
                    addMessage(text, ConversationAdapter.MessageType.USER)
                }

                override fun onResponse(text: String) = runOnUiThread {
                    addMessage(text, ConversationAdapter.MessageType.ASSISTANT)
                }

                override fun onSystemMessage(text: String) = runOnUiThread {
                    addMessage(text, ConversationAdapter.MessageType.SYSTEM)
                }

                override fun onAmplitude(value: Float) {
                    waveformView.addAmplitude(value)
                }

                /**
                 * Called during model download with 0-100 progress.
                 * Updates the progress bar and status text directly —
                 * no separate download screen required.
                 */
                override fun onDownloadProgress(pct: Int) = runOnUiThread {
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = false
                    progressBar.max      = 100
                    progressBar.progress = pct
                    tvStatus.text        = "📥 Downloading model… $pct%"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound   = false
            service = null
        }
    }

    // ---- Permission launcher ------------------------------------------------
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAndBindService()
            else {
                btnMic.isEnabled = false
                tvStatus.text    = "⚠️ Mic permission denied — open Settings to grant it"
            }
        }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupRecyclerView()
        setupMicButton()
        setupDebugOverlay()

        // Observe state machine — drives all UI changes
        lifecycleScope.launch {
            AssistantStateManager.state.collectLatest { state ->
                applyState(state)
            }
        }

        checkAndRequestAudioPermission()
    }

    override fun onStart() {
        super.onStart()
        if (!bound && hasAudioPermission()) startAndBindService()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.uiCallbacks = null
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        waveformView.stopAnimation()
    }

    // =========================================================================
    // UI Setup
    // =========================================================================

    private fun bindViews() {
        tvStatus       = findViewById(R.id.tv_status)
        waveformView   = findViewById(R.id.waveform_view)
        btnMic         = findViewById(R.id.btn_mic)
        progressBar    = findViewById(R.id.progress_bar)
        rvConversation = findViewById(R.id.rv_conversation)
        tvDebugOverlay = findViewById(R.id.tv_debug_overlay)
    }

    private fun setupRecyclerView() {
        rvConversation.layoutManager =
            LinearLayoutManager(this).also { it.stackFromEnd = true }
        rvConversation.adapter = adapter
    }

    private fun setupMicButton() {
        btnMic.setOnClickListener {
            when (AssistantStateManager.current) {
                AssistantStateManager.State.IDLE      -> service?.startListening()
                AssistantStateManager.State.LISTENING -> service?.stopListening()
                AssistantStateManager.State.ERROR     -> retryInit()
                else -> { /* ignore during LOADING / PROCESSING / EXECUTING / DOWNLOAD */ }
            }
        }
    }

    private fun setupDebugOverlay() {
        tvDebugOverlay.visibility = View.GONE
        tvStatus.setOnLongClickListener {
            if (tvDebugOverlay.visibility == View.VISIBLE) {
                tvDebugOverlay.visibility = View.GONE
            } else {
                tvDebugOverlay.text       = PipelineLogger.tail(30)
                tvDebugOverlay.visibility = View.VISIBLE
            }
            true
        }
    }

    // =========================================================================
    // State-Driven UI
    // =========================================================================

    private fun applyState(state: AssistantStateManager.State) {
        // Don’t override the download progress text while downloading
        val isDownloading = state == AssistantStateManager.State.LOADING_LLM &&
            progressBar.progress in 1..99

        if (!isDownloading) {
            tvStatus.text = state.displayLabel
        }

        when (state) {
            AssistantStateManager.State.UNINITIALISED,
            AssistantStateManager.State.LOADING_STT,
            AssistantStateManager.State.LOADING_LLM -> {
                btnMic.isEnabled           = false
                progressBar.visibility     = View.VISIBLE
                progressBar.isIndeterminate = !isDownloading  // spinner while loading, bar while downloading
                btnMic.clearAnimation()
                waveformView.startIdleAnimation()
                waveformView.alpha = 0.5f
            }

            AssistantStateManager.State.IDLE -> {
                btnMic.isEnabled            = true
                progressBar.visibility      = View.GONE
                progressBar.isIndeterminate = true   // reset for next time
                progressBar.progress        = 0
                btnMic.clearAnimation()
                waveformView.stopAnimation()
                waveformView.reset()
                waveformView.alpha = 1f
                btnMic.setImageResource(R.drawable.ic_mic)
            }

            AssistantStateManager.State.LISTENING -> {
                btnMic.isEnabled       = true
                progressBar.visibility = View.GONE
                btnMic.startAnimation(pulseAnim)
                waveformView.alpha     = 1f
                btnMic.setImageResource(R.drawable.ic_mic_active)
            }

            AssistantStateManager.State.PROCESSING,
            AssistantStateManager.State.EXECUTING -> {
                btnMic.isEnabled            = false
                progressBar.visibility      = View.VISIBLE
                progressBar.isIndeterminate = true
                btnMic.clearAnimation()
                waveformView.startIdleAnimation()
                waveformView.alpha = 0.7f
            }

            AssistantStateManager.State.ERROR -> {
                btnMic.isEnabled       = true
                progressBar.visibility = View.GONE
                btnMic.clearAnimation()
                waveformView.stopAnimation()
                tvStatus.text = "⚠️ ${AssistantStateManager.errorMessage ?: "Error"}"
                btnMic.setImageResource(R.drawable.ic_mic_error)
                showErrorDialog(AssistantStateManager.errorMessage ?: "Unknown error")
            }
        }
    }

    // =========================================================================
    // Permission
    // =========================================================================

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestAudioPermission() {
        when {
            hasAudioPermission() -> startAndBindService()
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                AlertDialog.Builder(this)
                    .setTitle("Microphone Required")
                    .setMessage(
                        "This assistant processes your voice entirely on-device. " +
                        "Audio is never sent to any server.\n\n" +
                        "Please grant the microphone permission to continue."
                    )
                    .setPositiveButton("Grant") { _, _ ->
                        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            else -> requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // =========================================================================
    // Service
    // =========================================================================

    private fun startAndBindService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun retryInit() {
        AssistantStateManager.forceReset()
        PipelineLogger.clear()
        val intent = Intent(this, VoiceAssistantService::class.java)
            .putExtra(VoiceAssistantService.EXTRA_RETRY, true)
        ContextCompat.startForegroundService(this, intent)
        if (!bound) bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun addMessage(text: String, type: ConversationAdapter.MessageType) {
        adapter.addMessage(ConversationAdapter.Message(text, type))
        rvConversation.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Assistant Error")
            .setMessage(message)
            .setPositiveButton("Retry")  { _, _ -> retryInit() }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
