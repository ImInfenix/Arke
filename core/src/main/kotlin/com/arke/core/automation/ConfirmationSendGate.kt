package com.arke.core.automation

data class SendRequest(val token: String, val phone: String, val text: String, val createdAtMillis: Long)

enum class Screen { CHAT, CONTACT_INFO, OTHER }

data class UiSnapshot(
    val packageName: String,
    val screen: Screen,
    val windowId: Int = -1,
    val title: String? = null,
    val composerText: String? = null,
    val phoneNumbers: Set<String> = emptySet(),
    val hasUniqueHeader: Boolean = false,
    val hasUniqueSendButton: Boolean = false,
)

sealed interface Decision {
    data object Wait : Decision
    data object OpenContactInfo : Decision
    data object ReturnToChat : Decision
    data class ClickSend(val expected: UiSnapshot, val expiresAtMillis: Long) : Decision
    data class Stop(val reason: String) : Decision
}

class ConfirmationSendGate {
    private enum class Phase { IDLE, OPEN_CHAT, VERIFY_PROFILE, RETURN_TO_CHAT, CLICK_ATTEMPTED, FINISHED }

    private var phase = Phase.IDLE
    private var request: SendRequest? = null
    private val usedTokens = HashSet<String>()
    private var firstChat: UiSnapshot? = null
    private var stableSince: Long? = null
    private var verifiedAt: Long? = null
    private var seenWhatsApp = false
    private var lastObservedAt = 0L
    private var stoppedReason = "Aucun envoi confirmé en attente."

    fun armConfirmed(request: SendRequest, nowMillis: Long): Decision {
        if (request.token.isBlank() || request.token.length > 200 || request.token in usedTokens) {
            return stop("Cette confirmation n'est plus utilisable. Aucun nouvel appui sur Envoyer.")
        }
        if (usedTokens.size >= MAX_TOKENS) return stop("La limite de cette session est atteinte. Aucun envoi engagé.")
        usedTokens += request.token
        if (phase !in setOf(Phase.IDLE, Phase.FINISHED)) {
            return stop("Une autre demande était déjà en cours. Recommence après vérification ; aucun nouvel appui sur Envoyer.")
        }
        if (!PHONE_PATTERN.matches(request.phone) || request.text.isBlank() || request.text.length > MAX_TEXT_LENGTH) {
            return stop("Le numéro ou le message confirmé n'est pas utilisable. Aucun envoi engagé.")
        }
        if (request.createdAtMillis < 0 || nowMillis < request.createdAtMillis || nowMillis - request.createdAtMillis > REQUEST_TTL) {
            return stop("La confirmation d'envoi a expiré. Aucun envoi engagé.")
        }
        this.request = request
        phase = Phase.OPEN_CHAT
        firstChat = null
        stableSince = null
        verifiedAt = null
        seenWhatsApp = false
        lastObservedAt = nowMillis
        return Decision.Wait
    }

    fun observe(
        snapshot: UiSnapshot?,
        nowMillis: Long,
        unlocked: Boolean = true,
        connected: Boolean = true,
    ): Decision {
        if (phase == Phase.CLICK_ATTEMPTED) return stop("Un appui sur Envoyer a déjà été autorisé. Résultat à vérifier dans WhatsApp ; aucun nouvel essai automatique.")
        val active = request ?: return Decision.Stop(stoppedReason)
        if (!connected) return stop("Le service d'envoi est interrompu. Aucun nouvel appui sur Envoyer.")
        if (!unlocked) return stop("Le téléphone est verrouillé. Aucun nouvel appui sur Envoyer.")
        if (nowMillis < lastObservedAt || nowMillis < active.createdAtMillis) {
            return stop("Le délai de vérification est devenu incohérent. Aucun envoi engagé.")
        }
        lastObservedAt = nowMillis
        if (nowMillis - active.createdAtMillis > REQUEST_TTL) {
            return stop("Le délai d'envoi a expiré. Aucun nouvel appui sur Envoyer.")
        }
        if (verifiedAt?.let { nowMillis - it > PHONE_PROOF_TTL } == true) {
            return stop("La vérification du destinataire a expiré. Aucun envoi engagé.")
        }
        if (snapshot == null) return Decision.Wait
        if (snapshot.packageName != WHATSAPP_PACKAGE) {
            if (!seenWhatsApp && snapshot.packageName == ARKE_PACKAGE && nowMillis - active.createdAtMillis <= LAUNCH_GRACE) {
                return Decision.Wait
            }
            return stop("WhatsApp n'est plus au premier plan. Aucun nouvel appui sur Envoyer.")
        }
        seenWhatsApp = true
        val frozen = snapshot.copy(phoneNumbers = snapshot.phoneNumbers.toSet())
        return when (phase) {
            Phase.OPEN_CHAT -> openProfile(frozen, active, nowMillis)
            Phase.VERIFY_PROFILE -> verifyProfile(frozen, active, nowMillis)
            Phase.RETURN_TO_CHAT -> authorizeClick(frozen, active, nowMillis)
            else -> Decision.Stop(stoppedReason)
        }
    }

    fun cancel(reason: String = "Envoi annulé. Aucun nouvel appui sur Envoyer."): Decision = stop(reason)

    fun completeClick(success: Boolean): Decision {
        if (phase != Phase.CLICK_ATTEMPTED) return Decision.Stop(stoppedReason)
        return stop(if (success) {
            "Action Envoyer déclenchée dans WhatsApp. Livraison non confirmée."
        } else {
            "L'action d'envoi n'a pas été confirmée. Vérifie WhatsApp avant de réessayer ; aucun nouvel essai automatique."
        })
    }

    private fun openProfile(snapshot: UiSnapshot, active: SendRequest, nowMillis: Long): Decision {
        if (snapshot.screen != Screen.CHAT) {
            stableSince = null
            return Decision.Wait
        }
        if (snapshot.windowId < 0 || snapshot.title.isNullOrBlank() || snapshot.composerText == null) return Decision.Wait
        if (snapshot.composerText != active.text) {
            if (firstChat == null) return Decision.Wait
            return stop("Le texte affiché ne correspond plus au message confirmé. Aucun envoi engagé.")
        }
        val first = firstChat
        if (first != null && !sameChat(first, snapshot)) {
            return stop("La conversation affichée a changé. Aucun envoi engagé.")
        }
        if (!snapshot.hasUniqueHeader) {
            stableSince = null
            return Decision.Wait
        }
        if (first == null) firstChat = snapshot
        val since = stableSince
        if (since == null) {
            stableSince = nowMillis
            return Decision.Wait
        }
        if (nowMillis - since < STABILITY_DELAY) return Decision.Wait
        stableSince = null
        phase = Phase.VERIFY_PROFILE
        return Decision.OpenContactInfo
    }

    private fun verifyProfile(snapshot: UiSnapshot, active: SendRequest, nowMillis: Long): Decision {
        if (snapshot.screen == Screen.CHAT) {
            val original = firstChat ?: return stop("La conversation n'est plus vérifiable. Aucun envoi engagé.")
            if (!sameChat(original, snapshot) || snapshot.composerText != active.text) {
                return stop("La conversation ou le texte a changé avant vérification. Aucun envoi engagé.")
            }
            return Decision.Wait
        }
        if (snapshot.screen != Screen.CONTACT_INFO) return Decision.Wait
        if (snapshot.windowId < 0 || snapshot.phoneNumbers.isEmpty()) return Decision.Wait
        if (snapshot.phoneNumbers != setOf(active.phone)) {
            return stop("Le numéro affiché ne confirme pas le destinataire choisi. Aucun envoi engagé.")
        }
        verifiedAt = nowMillis
        stableSince = null
        phase = Phase.RETURN_TO_CHAT
        return Decision.ReturnToChat
    }

    private fun authorizeClick(snapshot: UiSnapshot, active: SendRequest, nowMillis: Long): Decision {
        if (snapshot.screen == Screen.CONTACT_INFO) {
            if (snapshot.phoneNumbers.isNotEmpty() && snapshot.phoneNumbers != setOf(active.phone)) {
                return stop("Le destinataire a changé après sa vérification. Aucun envoi engagé.")
            }
            stableSince = null
            return Decision.Wait
        }
        if (snapshot.screen != Screen.CHAT) {
            stableSince = null
            return Decision.Wait
        }
        val original = firstChat ?: return stop("La conversation n'est plus vérifiable. Aucun envoi engagé.")
        if (!sameChat(original, snapshot) || snapshot.composerText != active.text) {
            return stop("Le destinataire ou le texte ne correspond plus à la confirmation. Aucun envoi engagé.")
        }
        if (!snapshot.hasUniqueSendButton) {
            stableSince = null
            return Decision.Wait
        }
        val since = stableSince
        if (since == null) {
            stableSince = nowMillis
            return Decision.Wait
        }
        if (nowMillis - since < STABILITY_DELAY) return Decision.Wait
        phase = Phase.CLICK_ATTEMPTED
        request = null
        val proofAt = verifiedAt ?: return stop("Le destinataire n'est plus vérifiable. Aucun envoi engagé.")
        return Decision.ClickSend(snapshot, minOf(deadline(active.createdAtMillis, REQUEST_TTL), deadline(proofAt, PHONE_PROOF_TTL)))
    }

    private fun sameChat(first: UiSnapshot, second: UiSnapshot): Boolean =
        first.windowId == second.windowId && first.title == second.title && first.composerText == second.composerText

    private fun deadline(start: Long, duration: Long): Long =
        if (start > Long.MAX_VALUE - duration) Long.MAX_VALUE else start + duration

    private fun stop(reason: String): Decision {
        phase = Phase.FINISHED
        request = null
        firstChat = null
        stableSince = null
        verifiedAt = null
        stoppedReason = reason.take(500).ifBlank { "Envoi interrompu. Aucun nouvel appui sur Envoyer." }
        return Decision.Stop(stoppedReason)
    }

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val ARKE_PACKAGE = "com.arke.android"
        const val REQUEST_TTL = 30_000L
        const val PHONE_PROOF_TTL = 5_000L
        const val STABILITY_DELAY = 150L
        const val LAUNCH_GRACE = 2_500L
        const val MAX_TOKENS = 2048
        const val MAX_TEXT_LENGTH = 2000
        val PHONE_PATTERN = Regex("\\+[1-9][0-9]{6,14}")
    }
}
