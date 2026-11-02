package com.arke.android.platform.send

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.arke.android.LocalVoice
import com.arke.core.Contact
import com.arke.core.automation.ConfirmationSendGate
import com.arke.core.automation.Decision
import com.arke.core.automation.SendRequest

class WhatsAppSendAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var pending = false
    private var requestToken: String? = null
    private var requestText: String? = null
    private var expectedHeaderEvent: String? = null
    private var expectedHeaderUntil = 0L
    private var returnedFromProfile = false
    private var requestExpiresAt = 0L
    private var activityClass: String? = null
    private val poll = Runnable { inspect() }
    private val deadline = Runnable {
        if (pending) finish("Le délai d'envoi a expiré. Aucun nouvel appui sur Envoyer.")
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService?.takeIf { it !== this }?.finish("Le service a été reconnecté. Recommence la demande.")
        connectedService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pending || event == null || event.packageName?.toString() != WhatsAppScreenCapture.PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activityClass = event.className?.toString()
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val expected = expectedHeaderEvent
                val sourceId = event.source?.viewIdResourceName
                if (expected != null && sourceId == expected && SystemClock.elapsedRealtime() <= expectedHeaderUntil) {
                    expectedHeaderEvent = null
                } else {
                    finish("Une interaction dans WhatsApp a interrompu l'envoi automatique. Aucun nouvel appui sur Envoyer.")
                    return
                }
            }
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                if (returnedFromProfile) {
                    finish("L'interface WhatsApp a été manipulée. Envoi automatique arrêté sans nouvel appui sur Envoyer.")
                    return
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (returnedFromProfile && event.source?.viewIdResourceName == WhatsAppScreenCapture.COMPOSER_ID &&
                    event.source?.text?.toString() != requestText) {
                    finish("Le message a changé dans WhatsApp. Aucun nouvel appui sur Envoyer.")
                    return
                }
            }
        }
        scheduleInspection()
    }

    override fun onInterrupt() {
        if (pending) finish("Le service d'envoi a été interrompu. Aucun nouvel appui sur Envoyer.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (pending) finish("Le service d'envoi est devenu indisponible. Aucun nouvel appui sur Envoyer.")
        if (connectedService === this) connectedService = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleInspection() {
        if (!pending) return
        if (!handler.hasCallbacks(poll)) handler.postDelayed(poll, 200)
    }

    private fun inspect() {
        if (!pending) return
        val activeGeneration = generation
        try {
            val capture = WhatsAppScreenCapture.read(rootInActiveWindow, activityClass)
            val decision = gate.observe(capture?.snapshot, SystemClock.elapsedRealtime(), unlocked(this), connectedService === this)
            if (!pending || generation != activeGeneration) return
            when (decision) {
                Decision.Wait -> scheduleInspection()
                Decision.OpenContactInfo -> {
                    val fresh = WhatsAppScreenCapture.read(rootInActiveWindow, activityClass)
                    val header = fresh?.header
                    if (fresh?.snapshot != capture?.snapshot || header == null || !header.refresh() || !unlocked(this) || SystemClock.elapsedRealtime() > requestExpiresAt) {
                        finish("Impossible de vérifier la fiche du destinataire. Aucun appui sur Envoyer.")
                        return
                    }
                    expectedHeaderEvent = header.viewIdResourceName
                    expectedHeaderUntil = SystemClock.elapsedRealtime() + 1_000
                    if (!header.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        finish("WhatsApp n'a pas ouvert la fiche du destinataire. Aucun appui sur Envoyer.")
                        return
                    }
                    scheduleInspection()
                }
                Decision.ReturnToChat -> {
                    val fresh = WhatsAppScreenCapture.read(rootInActiveWindow, activityClass)
                    returnedFromProfile = true
                    if (fresh?.snapshot != capture?.snapshot || !unlocked(this) || SystemClock.elapsedRealtime() > requestExpiresAt || !performGlobalAction(GLOBAL_ACTION_BACK)) {
                        finish("Impossible de revenir à la conversation vérifiée. Aucun appui sur Envoyer.")
                        return
                    }
                    scheduleInspection()
                }
                is Decision.ClickSend -> clickOnce(decision, activeGeneration)
                is Decision.Stop -> finish(decision.reason)
            }
        } catch (_: RuntimeException) {
            finish("L'interface WhatsApp n'a pas pu être vérifiée. Aucun nouvel appui sur Envoyer.")
        }
    }

    private fun clickOnce(decision: Decision.ClickSend, activeGeneration: Long) {
        val fresh = WhatsAppScreenCapture.read(rootInActiveWindow, activityClass)
        val send = fresh?.send
        if (fresh?.snapshot != decision.expected || send == null || !send.refresh() ||
            !WhatsAppScreenCapture.clickableSend(send) || !pending || generation != activeGeneration ||
            connectedService !== this || !unlocked(this) || SystemClock.elapsedRealtime() > decision.expiresAtMillis) {
            finish("La conversation ou le message a changé avant l'envoi. Aucun nouvel appui sur Envoyer.")
            return
        }
        pending = false
        requestToken = null
        requestText = null
        generation++
        handler.removeCallbacks(poll)
        handler.removeCallbacks(deadline)
        val attempted = try {
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: RuntimeException) {
            publish("Le résultat de l'appui sur Envoyer est inconnu. Aucun nouvel essai automatique.")
            gate.completeClick(false)
            return
        }
        val result = gate.completeClick(attempted)
        publish(if (result is Decision.Stop) result.reason else
            "Appui sur Envoyer effectué dans WhatsApp. Envoi et livraison non confirmés.")
    }

    private fun finish(reason: String, announce: Boolean = true) {
        if (!pending) return
        pending = false
        requestToken = null
        requestText = null
        expectedHeaderEvent = null
        returnedFromProfile = false
        generation++
        handler.removeCallbacks(poll)
        handler.removeCallbacks(deadline)
        gate.cancel(reason)
        if (announce) publish(reason)
    }

    private fun begin(context: Context, contact: Contact, text: String, token: String, confirmedAtMillis: Long): String {
        if (pending) return "Un envoi confirmé est déjà en cours. Aucun second envoi engagé."
        if (!unlocked(context)) return "Déverrouille le téléphone avant l'envoi. Aucun appui sur Envoyer."
        val decision = gate.armConfirmed(SendRequest(token, contact.phone, text, confirmedAtMillis), SystemClock.elapsedRealtime())
        if (decision is Decision.Stop) return decision.reason
        stopFeedback()
        lastOutcome = null
        generation++
        pending = true
        requestToken = token
        requestText = text
        expectedHeaderEvent = null
        returnedFromProfile = false
        requestExpiresAt = confirmedAtMillis + 30_000
        activityClass = null
        val address = Uri.Builder().scheme("https").authority("wa.me").appendPath(contact.phone.drop(1))
            .appendQueryParameter("text", text).build()
        val intent = Intent(Intent.ACTION_VIEW, address).setPackage(WhatsAppScreenCapture.PACKAGE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            handler.postDelayed(deadline, (requestExpiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            scheduleInspection()
            "WhatsApp va être ouvert. Je vérifie le destinataire et le texte avant l'appui automatique sur Envoyer."
        } catch (_: ActivityNotFoundException) {
            val reason = "WhatsApp est indisponible. Aucun appui sur Envoyer."
            finish(reason, announce = false)
            reason
        } catch (_: RuntimeException) {
            val reason = "Android n'a pas pu ouvrir WhatsApp. Aucun appui sur Envoyer."
            finish(reason, announce = false)
            reason
        }
    }

    private fun publish(status: String) {
        lastOutcome = status
        speakOutcome(applicationContext, status)
    }

    companion object {
        private val gate = ConfirmationSendGate()
        private val feedbackHandler = Handler(Looper.getMainLooper())
        private var feedbackGeneration = 0L
        private var feedbackVoice: LocalVoice? = null
        private val closeFeedback = Runnable { stopFeedback() }

        @Volatile
        private var connectedService: WhatsAppSendAccessibilityService? = null

        @Volatile
        private var lastOutcome: String? = null

        fun isConnected(): Boolean = connectedService != null

        fun hasPendingSend(): Boolean = connectedService?.pending == true

        fun startConfirmedSend(context: Context, contact: Contact, text: String, token: String,
            confirmedAtMillis: Long = SystemClock.elapsedRealtime()): String {
            if (Looper.myLooper() != Looper.getMainLooper()) return "La demande d'envoi n'a pas pu être engagée. Aucun appui sur Envoyer."
            val service = connectedService
                ?: return "Active le service d'envoi Arke dans les paramètres d'accessibilité. Aucun appui sur Envoyer."
            return service.begin(context, contact, text, token, confirmedAtMillis)
        }

        fun consumeOutcome(): String? {
            stopFeedback()
            val outcome = lastOutcome
            lastOutcome = null
            return outcome
        }

        fun cancelPending(reason: String) {
            val service = connectedService ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.finish(reason.take(300))
            } else {
                service.handler.post { service.finish(reason.take(300)) }
            }
        }

        private fun unlocked(context: Context): Boolean {
            val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
            return !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
        }

        private fun speakOutcome(context: Context, status: String) {
            stopFeedback()
            val currentGeneration = feedbackGeneration
            val voice = LocalVoice(context, object : LocalVoice.Listener {
                override fun onVoiceReady() {
                    if (feedbackGeneration == currentGeneration) feedbackVoice?.speak(status, false)
                }
                override fun onVoiceStatus(status: String) = Unit
                override fun onTranscript(text: String, confidence: Float?) = Unit
                override fun onVoiceFailure(message: String) {
                    if (feedbackGeneration == currentGeneration) stopFeedback()
                }
                override fun onPromptFinished(expectInput: Boolean) {
                    if (feedbackGeneration == currentGeneration) stopFeedback()
                }
            })
            feedbackVoice = voice
            feedbackHandler.postDelayed(closeFeedback, 10_000)
            voice.initialize()
        }

        private fun stopFeedback() {
            feedbackGeneration++
            feedbackHandler.removeCallbacks(closeFeedback)
            feedbackVoice?.close()
            feedbackVoice = null
        }
    }
}
