package com.arke.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arke.android.platform.ContactRepository
import com.arke.android.platform.WhatsAppLauncher
import com.arke.android.platform.WhatsAppNotificationService
import com.arke.core.Action
import com.arke.core.Contact
import com.arke.core.ConversationEngine
import com.arke.core.Stage
import com.arke.core.Turn
import java.util.concurrent.Executors

class MainActivity : Activity(), LocalVoice.Listener {
    private val engine = ConversationEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var voice: LocalVoice
    private lateinit var statusView: TextView
    private lateinit var promptView: TextView
    private lateinit var diagnosticView: TextView
    private lateinit var commandInput: EditText
    private lateinit var talkButton: Button
    private var contacts: List<Contact> = emptyList()
    private var loadingContacts = false
    private var contactGeneration = 0
    private var voiceSession = false
    private var resumed = false
    private var voiceReady = false
    private var pendingAssist = false
    private var expectsInput = false
    private var currentPrompt = ""
    private var pendingPreparation: String? = null
    private val ink = Color.rgb(26, 45, 40)
    private val green = Color.rgb(23, 105, 87)
    private val muted = Color.rgb(77, 96, 88)
    private val sessionTimeout = Runnable {
        stopSession()
        promptView.setText(R.string.session_expired)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildInterface()
        voice = LocalVoice(this, this)
        voice.initialize()
        pendingAssist = intent.action == Intent.ACTION_ASSIST
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAssist = intent.action == Intent.ACTION_ASSIST
        maybeStartAssist()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshDiagnostics()
        loadContacts()
        maybeStartAssist()
    }

    override fun onPause() {
        resumed = false
        pendingAssist = false
        stopSession()
        promptView.setText(R.string.welcome)
        commandInput.text.clear()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(commandInput.windowToken, 0)
        super.onPause()
    }

    override fun onDestroy() {
        voice.close()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onVoiceReady() {
        voiceReady = true
        refreshDiagnostics()
        maybeStartAssist()
    }

    override fun onVoiceStatus(status: String) {
        if (resumed) statusView.text = status
    }

    override fun onTranscript(text: String, confidence: Float?) {
        if (!resumed || !voiceSession || !checkUnlocked()) return
        if (confidence != null && confidence < 0.65f) {
            render(Turn("Je ne suis pas assez sûr de ce que j'ai entendu. Répète ta réponse.", listen = true))
            return
        }
        submit(text)
    }

    override fun onVoiceFailure(message: String) {
        if (!resumed) return
        voiceSession = false
        voice.stop()
        engine.reset()
        expectsInput = false
        handler.removeCallbacks(sessionTimeout)
        statusView.setText(R.string.voice_stopped)
        promptView.text = message
        talkButton.setText(R.string.talk)
    }

    override fun onPromptFinished(expectInput: Boolean) {
        if (!resumed || !voiceSession || !checkUnlocked()) return
        if (expectInput && expectsInput) {
            engine.promptDelivered()
            scheduleTimeout()
            if (!hasPermission(Manifest.permission.RECORD_AUDIO) || !voice.hasLocalRecognizer) {
                voiceSession = false
                statusView.setText(R.string.reading_finished)
                talkButton.setText(R.string.talk)
                return
            }
            voice.listen()
        } else {
            voiceSession = false
            statusView.setText(R.string.ready)
            talkButton.setText(R.string.talk)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDiagnostics()
        if (requestCode == 11) loadContacts()
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        }
    }

    private fun startVoice() {
        if (!checkUnlocked()) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            promptView.setText(R.string.mic_required)
            return
        }
        if (!voiceReady || !voice.hasFrenchVoice || !voice.hasLocalRecognizer) {
            promptView.setText(R.string.local_voice_required)
            return
        }
        if (loadingContacts) {
            promptView.setText(R.string.contacts_loading)
            return
        }
        voiceSession = true
        talkButton.setText(R.string.resume_listening)
        if (engine.stage == Stage.IDLE) {
            render(Turn(getString(R.string.welcome), listen = true))
        } else {
            render(Turn(currentPrompt.ifBlank { getString(R.string.welcome) }, listen = true))
        }
    }

    private fun submit(text: String) {
        if (text.isBlank() || !checkUnlocked()) return
        if (loadingContacts) {
            render(Turn("Les contacts sont en cours de chargement. Réessaie dans un instant."))
            return
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) contacts = emptyList()
        val messages = WhatsAppNotificationService.snapshot(this)
        render(engine.accept(text, contacts, messages))
    }

    private fun render(turn: Turn) {
        if (!resumed || !checkUnlocked()) return
        handler.removeCallbacks(sessionTimeout)
        promptView.text = turn.prompt
        currentPrompt = turn.prompt
        expectsInput = turn.listen
        statusView.text = if (turn.listen) "À votre écoute" else getString(R.string.ready)
        val action = turn.action
        if (action != null) {
            voice.stop()
            expectsInput = false
            when (action) {
                is Action.PrepareWhatsApp -> prepareWhatsApp(action)
                is Action.Reply -> render(engine.complete(action.token,
                    WhatsAppNotificationService.reply(this, action.messageId, action.text)))
            }
            return
        }
        if (turn.listen && !voiceSession) scheduleTimeout()
        if (voiceSession) voice.speak(turn.prompt, turn.listen)
    }

    private fun stopSession() {
        pendingPreparation = null
        talkButton.isEnabled = true
        voiceSession = false
        expectsInput = false
        handler.removeCallbacks(sessionTimeout)
        if (::voice.isInitialized) voice.stop()
        engine.reset()
        statusView.setText(R.string.ready)
        talkButton.setText(R.string.talk)
    }

    private fun prepareWhatsApp(action: Action.PrepareWhatsApp) {
        pendingPreparation = action.token
        talkButton.isEnabled = false
        executor.execute {
            val freshContacts = runCatching { ContactRepository(this).load() }.getOrDefault(emptyList())
            handler.post {
                if (!resumed || pendingPreparation != action.token || engine.stage != Stage.EXECUTING) return@post
                if (!checkUnlocked()) return@post
                pendingPreparation = null
                talkButton.isEnabled = true
                val outcome = if (freshContacts.any { it == action.contact }) {
                    WhatsAppLauncher.prepare(this, action.contact, action.text)
                } else {
                    "Le contact a changé ou n'est plus accessible. Aucun message préparé. Recommence pour vérifier le destinataire."
                }
                render(engine.complete(action.token, outcome))
            }
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(sessionTimeout)
        handler.postDelayed(sessionTimeout, 60_000)
    }

    private fun checkUnlocked(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked || keyguard.isKeyguardLocked) {
            stopSession()
            promptView.setText(R.string.locked)
            return false
        }
        return true
    }

    private fun loadContacts() {
        val generation = ++contactGeneration
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            contacts = emptyList()
            loadingContacts = false
            refreshDiagnostics()
            return
        }
        loadingContacts = true
        executor.execute {
            val result = runCatching { ContactRepository(this).load() }
            handler.post {
                if (isDestroyed || generation != contactGeneration) return@post
                loadingContacts = false
                contacts = result.getOrDefault(emptyList())
                if (result.isFailure && resumed) promptView.setText(R.string.contacts_unavailable)
                refreshDiagnostics()
                maybeStartAssist()
            }
        }
    }

    private fun maybeStartAssist() {
        if (resumed && pendingAssist && voiceReady && !loadingContacts) {
            pendingAssist = false
            startVoice()
        }
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshDiagnostics() {
        if (!::diagnosticView.isInitialized) return
        val localRecognition = if (::voice.isInitialized && voice.hasLocalRecognizer) "disponible" else "indisponible"
        val localSynthesis = if (::voice.isInitialized && voice.hasFrenchVoice) "disponible" else "indisponible ou en chargement"
        val whatsapp = runCatching { packageManager.getPackageInfo("com.whatsapp", 0) }.isSuccess
        val contactsStatus = if (!hasPermission(Manifest.permission.READ_CONTACTS)) "autorisation nécessaire"
            else if (loadingContacts) "chargement" else "${contacts.size} numéros utilisables"
        diagnosticView.text = listOf(
            "Reconnaissance locale : $localRecognition",
            "Voix française locale : $localSynthesis",
            "Microphone : ${if (hasPermission(Manifest.permission.RECORD_AUDIO)) "autorisé" else "autorisation nécessaire"}",
            "Contacts : $contactsStatus",
            "WhatsApp : ${if (whatsapp) "installé" else "absent"}",
            "Notifications : ${if (WhatsAppNotificationService.isConnected()) "accès connecté" else "accès non connecté"}",
            "Téléphone verrouillé : dialogue désactivé"
        ).joinToString("\n")
    }

    private fun openSettings(action: String) {
        stopSession()
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            promptView.setText(R.string.settings_unavailable)
        } catch (_: SecurityException) {
            promptView.setText(R.string.settings_unavailable)
        }
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(245, 245, 240))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            root.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(28) + bars.bottom)
            insets
        }
        root.addView(label(getString(R.string.eyebrow), 12f, green).apply { letterSpacing = 0.17f })
        root.addView(label(getString(R.string.app_name), 52f, ink).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(label(getString(R.string.tagline), 19f, muted))
        space(root, 22)
        root.addView(label(getString(R.string.prototype_notice), 14f, ink).apply {
            background = rounded(Color.rgb(232, 237, 224), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        space(root, 22)
        val conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        statusView = label(getString(R.string.ready), 13f, green).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        conversation.addView(statusView)
        space(conversation, 12)
        promptView = label(getString(R.string.welcome), 23f, ink).apply {
            minHeight = dp(100)
            setTextIsSelectable(true)
        }
        conversation.addView(promptView)
        talkButton = button(getString(R.string.talk), true) { startVoice() }
        conversation.addView(talkButton)
        conversation.addView(button(getString(R.string.cancel)) {
            val turn = engine.cancel()
            stopSession()
            render(turn)
        })
        root.addView(conversation)
        space(root, 12)
        root.addView(button(getString(R.string.read_messages)) {
            stopSession()
            if (!checkUnlocked()) return@button
            voiceSession = voiceReady && voice.hasFrenchVoice
            submit("Lis mes derniers messages WhatsApp")
            if (!voiceSession) statusView.setText(R.string.text_only)
        })
        root.addView(label(getString(R.string.examples), 14f, muted))
        space(root, 12)
        val textPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        commandInput = EditText(this).apply {
            hint = getString(R.string.command_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minHeight = dp(56)
            maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink)
            setHintTextColor(muted)
        }
        textPanel.addView(commandInput)
        textPanel.addView(button(getString(R.string.submit)) {
            voiceSession = false
            voice.stop()
            val text = commandInput.text.toString()
            commandInput.text.clear()
            submit(text)
        })
        root.addView(button(getString(R.string.write_command)) {
            textPanel.visibility = if (textPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        root.addView(textPanel)
        val setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        diagnosticView = label("", 14f, muted)
        setupPanel.addView(diagnosticView)
        setupPanel.addView(button(getString(R.string.grant_contacts)) {
            if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 11)
            } else loadContacts()
        })
        setupPanel.addView(button(getString(R.string.notification_access)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.notification_access)
                .setMessage(R.string.notification_explanation)
                .setPositiveButton(R.string.continue_action) { _, _ -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                .setNegativeButton(R.string.close, null)
                .show()
        })
        setupPanel.addView(button(getString(R.string.assistant_settings)) { openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) })
        setupPanel.addView(button(getString(R.string.voice_settings)) { openSettings("com.android.settings.TTS_SETTINGS") })
        setupPanel.addView(button(getString(R.string.refresh)) { refreshDiagnostics(); loadContacts() })
        root.addView(button(getString(R.string.setup)) {
            setupPanel.visibility = if (setupPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshDiagnostics()
        })
        root.addView(setupPanel)
        space(root, 12)
        root.addView(label(getString(R.string.privacy), 12f, muted))
        setContentView(scroll)
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(52)
        minimumHeight = dp(52)
        setTextColor(if (primary) Color.WHITE else green)
        if (primary) backgroundTintList = android.content.res.ColorStateList.valueOf(green)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
