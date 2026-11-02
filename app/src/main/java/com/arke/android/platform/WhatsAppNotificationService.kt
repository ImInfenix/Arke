package com.arke.android.platform

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.arke.core.IncomingMessage
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class WhatsAppNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun activeEntries(context: Context): List<Entry> {
        if (!unlocked(context)) return emptyList()
        return try {
            val entries = activeNotifications.orEmpty()
                .asSequence()
                .filter { it.packageName == WHATSAPP_PACKAGE }
                .flatMap { notification -> entries(context, notification).asSequence() }
                .toList()
            val ambiguousSenders = entries.groupBy { normalizedName(it.message.sender) }
                .filterValues { candidates -> candidates.map { it.conversationKey }.distinct().size > 1 }
                .keys
            entries.map { entry ->
                if (normalizedName(entry.message.sender) in ambiguousSenders) {
                    entry.copy(message = entry.message.copy(canReply = false), replyAction = null)
                } else {
                    entry
                }
            }.sortedByDescending { it.message.receivedAtMillis }
                .take(MAX_MESSAGES)
                .toList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun entries(context: Context, posted: StatusBarNotification): List<Entry> {
        val notification = posted.notification
        val now = System.currentTimeMillis()
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        if (!recent(posted.postTime, now)) return emptyList()
        return try {
            val style = Notification.Builder.recoverBuilder(context, notification).style
                as? Notification.MessagingStyle ?: return emptyList()
            if (style.isGroupConversation) return emptyList()
            val messages = style.messages.filter { message ->
                val sender = message.senderPerson
                sender != null && !isSamePerson(sender, style.user)
                    && !sender.name.isNullOrBlank() && recent(message.timestamp, now)
            }
            if (messages.map { personIdentity(it.senderPerson) }.distinct().size != 1) return emptyList()
            val action = replyAction(notification)
            val revision = fingerprint(buildString {
                append(posted.key)
                append('\u0000')
                append(posted.postTime)
                style.messages.forEach { message ->
                    append('\u0000')
                    append(personIdentity(message.senderPerson))
                    append('\u0000')
                    append(message.timestamp)
                    append('\u0000')
                    append(message.text)
                    append('\u0000')
                    append(message.dataMimeType)
                    append('\u0000')
                    append(message.dataUri)
                }
            })
            messages.takeLast(MAX_MESSAGES).mapIndexedNotNull { index, message ->
                val sender = message.senderPerson?.name?.toString()?.trim().orEmpty()
                if (sender.isEmpty() || sender.length > 200) return@mapIndexedNotNull null
                val body = accessibleBody(message) ?: return@mapIndexedNotNull null
                val id = "$revision:$index:${message.timestamp}"
                val available = action != null && synchronized(replyLock) { id !in attemptedReplies }
                Entry(IncomingMessage(id, sender, body, message.timestamp, available), action, posted.key)
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private data class Entry(
        val message: IncomingMessage,
        val replyAction: Notification.Action?,
        val conversationKey: String
    )

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val MAX_MESSAGES = 20
        private const val MAX_REPLY_ATTEMPTS = 2048
        private const val MAX_MESSAGE_AGE_MILLIS = 2 * 60 * 60 * 1000L
        private val replyLock = Any()
        private val attemptedReplies = LinkedHashSet<String>()

        @Volatile
        private var connectedService: WhatsAppNotificationService? = null

        fun isConnected(): Boolean = connectedService != null

        fun snapshot(context: Context): List<IncomingMessage> =
            connectedService?.activeEntries(context)?.map { it.message }.orEmpty()

        fun reply(context: Context, messageId: String, text: String): String {
            if (!unlocked(context)) {
                return "Déverrouille le téléphone avant de répondre. Aucune réponse transmise."
            }
            if (text.isBlank() || text.length > 2000) {
                return "La réponse doit contenir entre 1 et 2 000 caractères. Aucune réponse transmise."
            }
            val service = connectedService
                ?: return "L'accès aux notifications est indisponible. Aucune réponse transmise."
            val entry = service.activeEntries(context).singleOrNull { it.message.id == messageId }
                ?: return "Le message n'est plus accessible ou a changé. Relis-le avant de répondre."
            val action = entry.replyAction
                ?: return "Aucune action de réponse directe non ambiguë n'est disponible. Aucune réponse transmise."
            val remoteInput = action.remoteInputs?.singleOrNull { it.allowFreeFormInput }
                ?: return "La réponse directe n'est plus disponible. Aucune réponse transmise."
            val pendingIntent = action.actionIntent
                ?: return "La réponse directe n'est plus disponible. Aucune réponse transmise."
            if (pendingIntent.creatorPackage != WHATSAPP_PACKAGE || pendingIntent.isImmutable) {
                return "L'action de réponse n'est pas utilisable. Aucune réponse transmise."
            }
            return synchronized(replyLock) {
                if (messageId in attemptedReplies) {
                    return "Une réponse a déjà été tentée pour ce message. Aucun nouvel essai automatique."
                }
                if (attemptedReplies.size >= MAX_REPLY_ATTEMPTS) {
                    return "La limite des tentatives de cette session est atteinte. Aucune réponse transmise."
                }
                if (!unlocked(context) || connectedService !== service) {
                    return "Le téléphone ou l'accès aux notifications n'est plus disponible. Aucune réponse transmise."
                }
                val currentEntry = service.activeEntries(context).singleOrNull { it.message.id == messageId }
                if (currentEntry == null || !currentEntry.message.canReply
                    || currentEntry.replyAction?.actionIntent != pendingIntent) {
                    return "Le message ou son action a changé. Relis-le avant de répondre. Aucune réponse transmise."
                }
                try {
                    val results = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
                    val replyIntent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, results)
                    RemoteInput.setResultsSource(replyIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                    attemptedReplies += messageId
                    pendingIntent.send(context, 0, replyIntent)
                    "Demande de réponse transmise à WhatsApp. Livraison non confirmée."
                } catch (_: PendingIntent.CanceledException) {
                    "WhatsApp a refusé une action devenue indisponible. Aucun nouvel essai automatique."
                } catch (_: RuntimeException) {
                    "Le résultat de la tentative de réponse est inconnu. Aucun nouvel essai automatique."
                }
            }
        }

        private fun replyAction(notification: Notification): Notification.Action? =
            notification.actions.orEmpty().filter { action ->
                val intent = action.actionIntent
                intent != null && intent.creatorPackage == WHATSAPP_PACKAGE && !intent.isImmutable
                    && action.remoteInputs?.count { it.allowFreeFormInput } == 1
                    && action.semanticAction in setOf(
                        Notification.Action.SEMANTIC_ACTION_NONE,
                        Notification.Action.SEMANTIC_ACTION_REPLY
                    )
            }.singleOrNull()

        private fun accessibleBody(message: Notification.MessagingStyle.Message): String? {
            val text = message.text?.toString()?.trim().orEmpty()
            val normalized = text.lowercase(Locale.ROOT)
            if (text.isEmpty() && message.dataMimeType == null) return null
            if (normalized in setOf(
                    "nouveau message", "new message", "contenu masqué", "content hidden",
                    "notification sensible", "sensitive notification content hidden"
                ) || normalized.matches(Regex("\\d+ (nouveaux? messages?|new messages?)"))) {
                return null
            }
            return when {
                message.dataMimeType != null -> {
                    val caption = text.take(1800)
                    if (caption.isBlank()) "Pièce jointe disponible, contenu non lu."
                    else "$caption. Pièce jointe non lue${if (text.length > 1800) ", texte partiel" else ""}."
                }
                text.length > 2000 -> "${text.take(1900)}. Message partiel : la fin n'est pas lue."
                else -> text
            }
        }

        private fun unlocked(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

        private fun recent(timestamp: Long, now: Long): Boolean =
            timestamp > 0 && timestamp >= now - MAX_MESSAGE_AGE_MILLIS && timestamp <= now + 5 * 60 * 1000

        private fun isSamePerson(first: Person, second: Person): Boolean =
            personIdentity(first) == personIdentity(second)

        private fun normalizedName(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .trim()
                .replace(Regex("\\s+"), " ")

        private fun personIdentity(person: Person?): String =
            person?.key?.takeIf { it.isNotBlank() }
                ?: person?.uri?.takeIf { it.isNotBlank() }
                ?: person?.name?.toString().orEmpty()

        private fun fingerprint(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
