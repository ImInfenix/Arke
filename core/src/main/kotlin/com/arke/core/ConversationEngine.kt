package com.arke.core

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class Contact(val id: String, val name: String, val phone: String)

data class IncomingMessage(
    val id: String,
    val sender: String,
    val text: String,
    val receivedAtMillis: Long,
    val canReply: Boolean,
)

enum class Stage { IDLE, RECIPIENT, DISAMBIGUATION, DICTATION, CONFIRMATION, EXECUTING }

sealed interface Action {
    val token: String

    data class SendWhatsApp(
        val contact: Contact,
        val text: String,
        override val token: String,
    ) : Action

    data class Reply(
        val messageId: String,
        val text: String,
        override val token: String,
    ) : Action
}

data class Turn(val prompt: String, val listen: Boolean = false, val action: Action? = null)

class ConversationEngine {
    var stage: Stage = Stage.IDLE
        private set

    private var targetContact: Contact? = null
    private var targetMessage: IncomingMessage? = null
    private var draftText: String? = null
    private var candidates = emptyList<Contact>()
    private var confirmationAt: Long? = null
    private var confirmationDeliveryPending = false
    private var replyContext: IncomingMessage? = null
    private var contextAt: Long? = null
    private var contextDeliveryPending = false
    private var pendingToken: String? = null

    fun accept(
        input: String,
        contacts: List<Contact>,
        messages: List<IncomingMessage>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Turn {
        if (stage == Stage.EXECUTING) {
            return Turn("L'action est déjà engagée. Attends son résultat.")
        }
        confirmationDeliveryPending = false
        contextDeliveryPending = false
        val text = input.trim()
        if (text.length > MAX_INPUT_LENGTH) {
            return Turn("La demande est trop longue. Répète une phrase plus courte.", true)
        }
        val command = normalize(text)
        if (command in CANCEL_COMMANDS) return cancel()
        if (command in RESTART_COMMANDS) {
            reset()
            return Turn("Que veux-tu faire ?", true)
        }
        if (stage == Stage.CONFIRMATION && !isFresh(confirmationAt, nowMillis, CONFIRMATION_TIMEOUT)) {
            reset()
            return Turn("La confirmation a expiré. Recommence la demande.")
        }
        if (text.isBlank()) return Turn("Je n'ai pas compris. Répète, ou dis « annule ».", true)
        if (command in CHANGE_RECIPIENT_COMMANDS && stage != Stage.IDLE) {
            targetContact = null
            targetMessage = null
            candidates = emptyList()
            confirmationAt = null
            stage = Stage.RECIPIENT
            return Turn("À quel contact veux-tu envoyer un nouveau WhatsApp ?", true)
        }
        if (command in EDIT_COMMANDS && (targetContact != null || targetMessage != null)) {
            draftText = null
            confirmationAt = null
            stage = Stage.DICTATION
            return Turn("Quel nouveau message ?", true)
        }
        return when (stage) {
            Stage.IDLE -> start(text, command, contacts, messages, nowMillis)
            Stage.RECIPIENT -> resolveContact(text, contacts, nowMillis)
            Stage.DISAMBIGUATION -> chooseContact(text, command, contacts, nowMillis)
            Stage.DICTATION -> dictate(text, nowMillis)
            Stage.CONFIRMATION -> confirm(text, command, contacts, messages, nowMillis)
            Stage.EXECUTING -> Turn("L'action est déjà engagée.")
        }
    }

    fun complete(token: String, outcome: String): Turn {
        if (stage != Stage.EXECUTING || token != pendingToken) {
            return Turn("Ce résultat ne correspond plus à une action en cours.")
        }
        clearDraft()
        clearContext()
        stage = Stage.IDLE
        return Turn(outcome.take(MAX_RESULT_LENGTH).ifBlank { "Résultat inconnu. Vérifie dans WhatsApp avant de réessayer." })
    }

    fun promptDelivered(nowMillis: Long = System.currentTimeMillis()) {
        if (stage == Stage.CONFIRMATION && confirmationDeliveryPending) {
            confirmationAt = nowMillis
            confirmationDeliveryPending = false
        } else if (stage == Stage.IDLE && replyContext != null && contextDeliveryPending) {
            contextAt = nowMillis
            contextDeliveryPending = false
        }
    }

    fun cancel(): Turn {
        if (stage == Stage.EXECUTING) {
            return Turn("L'action est déjà engagée. Son annulation n'est pas garantie.")
        }
        reset()
        return Turn("C'est annulé. Aucune nouvelle action n'a été engagée.")
    }

    fun reset() {
        clearDraft()
        clearContext()
        stage = Stage.IDLE
    }

    private fun start(
        text: String,
        command: String,
        contacts: List<Contact>,
        messages: List<IncomingMessage>,
        nowMillis: Long,
    ): Turn {
        if (command.startsWith("lis ") || command == "lis") return readMessages(command, messages, nowMillis)
        val replyMatch = REPLY_PATTERN.matchEntire(text)
        if (replyMatch != null) {
            val context = replyContext
            val available = context != null && isFresh(contextAt, nowMillis, CONTEXT_TIMEOUT) &&
                messages.any { it == context } && context.canReply && context.text.isNotBlank()
            if (!available) {
                clearContext()
                return Turn("Lis d'abord un message accessible avec une action de réponse, pour identifier la bonne conversation.")
            }
            clearDraft()
            targetMessage = context
            val body = replyMatch.groupValues[1].trim()
            return if (body.isEmpty()) {
                stage = Stage.DICTATION
                Turn("Quelle réponse ?", true)
            } else {
                dictate(body, nowMillis)
            }
        }
        val composeMatch = COMPOSE_PATTERN.matchEntire(text)
        if (composeMatch != null) {
            clearDraft()
            clearContext()
            val remainder = composeMatch.groupValues[1].trim()
            val separator = BODY_SEPARATOR.find(remainder)
            val recipient = if (separator == null) remainder else remainder.substring(0, separator.range.first)
            val body = separator?.let { remainder.substring(it.range.last + 1).trim() }
            if (body != null && body.length > MAX_MESSAGE_LENGTH) {
                reset()
                return Turn("Le message est trop long. Recommence avec moins de $MAX_MESSAGE_LENGTH caractères.")
            }
            draftText = body?.takeIf { it.isNotBlank() }
            stage = Stage.RECIPIENT
            return if (recipient.isBlank()) Turn("À quel contact ?", true)
            else resolveContact(recipient, contacts, nowMillis)
        }
        return Turn("Tu peux demander un WhatsApp à un contact, lire tes messages accessibles, ou répondre après une lecture.", true)
    }

    private fun resolveContact(query: String, contacts: List<Contact>, nowMillis: Long): Turn {
        val validContacts = contacts.asSequence()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= MAX_NAME_LENGTH && digits(it.phone).length in 7..15 }
            .distinctBy { it.id to digits(it.phone) }
            .toList()
        val key = normalize(query)
        val matches = validContacts.filter { nameMatches(it.name, key) }
        if (matches.isEmpty()) {
            candidates = emptyList()
            targetContact = null
            stage = Stage.RECIPIENT
            return Turn("Je ne trouve pas ce contact parmi les contacts accessibles. Précise son nom, ou dis « annule ».", true)
        }
        if (matches.size == 1) return setContact(matches.single(), nowMillis)
        candidates = matches.sortedWith(compareBy({ normalize(it.name) }, { digits(it.phone) }, { it.id }))
        stage = Stage.DISAMBIGUATION
        if (candidates.size > MAX_CHOICES) {
            return Turn("Il y a ${candidates.size} correspondances. Précise le nom complet du contact.", true)
        }
        val choices = candidates.mapIndexed { index, contact -> "${index + 1}, ${contactLabel(contact)}" }.joinToString(". ")
        return Turn("Plusieurs contacts correspondent. $choices. Dis le numéro du choix ou le nom complet.", true)
    }

    private fun chooseContact(
        text: String,
        command: String,
        contacts: List<Contact>,
        nowMillis: Long,
    ): Turn {
        val selection = CHOICE_WORDS[command] ?: command.toIntOrNull()
        if (selection != null && candidates.size <= MAX_CHOICES) {
            val selected = candidates.getOrNull(selection - 1)
            if (selected != null) {
                if (contacts.none { it == selected }) return unavailableContact()
                return setContact(selected, nowMillis)
            }
        }
        val suffix = SUFFIX_PATTERN.matchEntire(command)?.groupValues?.get(1)
        if (suffix != null) {
            val matches = candidates.filter { digits(it.phone).endsWith(suffix) }
            if (matches.size == 1) {
                if (contacts.none { it == matches.single() }) return unavailableContact()
                return setContact(matches.single(), nowMillis)
            }
        }
        val currentCandidates = candidates.filter { candidate -> contacts.any { it == candidate } }
        val named = currentCandidates.filter { nameMatches(it.name, command) }
        if (named.size == 1) return setContact(named.single(), nowMillis)
        if (currentCandidates.isEmpty()) return unavailableContact()
        if (named.isEmpty() && selection == null && suffix == null) {
            return resolveContact(text, contacts, nowMillis)
        }
        return Turn("Le choix reste ambigu. Précise le nom complet, ou le numéro proposé.", true)
    }

    private fun setContact(contact: Contact, nowMillis: Long): Turn {
        targetContact = contact
        targetMessage = null
        candidates = emptyList()
        return if (draftText == null) {
            stage = Stage.DICTATION
            Turn("Pour ${contactLabel(contact)}, quel message ?", true)
        } else confirmation(nowMillis)
    }

    private fun dictate(text: String, nowMillis: Long): Turn {
        if (text.length > MAX_MESSAGE_LENGTH) {
            stage = Stage.DICTATION
            return Turn("Le message est trop long. Dicte moins de $MAX_MESSAGE_LENGTH caractères.", true)
        }
        draftText = text
        return confirmation(nowMillis)
    }

    private fun confirmation(nowMillis: Long, prefix: String = "", renew: Boolean = true): Turn {
        val body = draftText
        if (body.isNullOrBlank()) {
            stage = Stage.DICTATION
            return Turn("Quel message ?", true)
        }
        val contact = targetContact
        val message = targetMessage
        if (contact == null && message == null) {
            reset()
            return Turn("Le destinataire n'est plus défini. Recommence la demande.")
        }
        stage = Stage.CONFIRMATION
        if (renew || confirmationAt == null) {
            confirmationAt = nowMillis
            confirmationDeliveryPending = true
        }
        val prompt = if (contact != null) {
            "Pour ${contactLabel(contact)} : « $body ». Tu confirmes l'envoi dans WhatsApp ?"
        } else {
            "Réponse à ${message!!.sender} : « $body ». Tu confirmes la demande de transmission ?"
        }
        return Turn(prefix + prompt, true)
    }

    private fun confirm(
        text: String,
        command: String,
        contacts: List<Contact>,
        messages: List<IncomingMessage>,
        nowMillis: Long,
    ): Turn {
        val correction = CORRECTION_PATTERN.matchEntire(text)
        if (correction != null) {
            val old = correction.groupValues[1].trim().trim('"', '«', '»')
            val replacement = correction.groupValues[2].trim().trim('"', '«', '»')
            val body = draftText.orEmpty()
            if (old.isBlank() || replacement.isBlank()) return confirmation(nowMillis, "Précise les deux textes à remplacer. ", renew = false)
            val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(old)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            val occurrences = pattern.findAll(body).toList()
            if (occurrences.size != 1) {
                return confirmation(nowMillis, "Je ne peux pas appliquer cette correction sans ambiguïté. Dicte à nouveau le message avec « modifie le message ». ", renew = false)
            }
            val match = occurrences.single()
            val updated = body.replaceRange(match.range, replacement)
            if (updated.length > MAX_MESSAGE_LENGTH) return confirmation(nowMillis, "La correction rendrait le texte trop long. ", renew = false)
            draftText = updated
            return confirmation(nowMillis)
        }
        if (command in REJECT_COMMANDS) return cancel()
        if (command !in CONFIRM_COMMANDS) {
            return confirmation(nowMillis, "Je n'ai pas reçu une confirmation explicite. ", renew = false)
        }
        val contact = targetContact
        val message = targetMessage
        if (contact != null && contacts.none { it == contact }) return unavailableContact()
        if (message != null && (messages.none { it == message } || !message.canReply)) {
            reset()
            return Turn("Le message ou son action de réponse n'est plus disponible. Relis les messages avant de répondre.")
        }
        val token = UUID.randomUUID().toString()
        val action = if (contact != null) Action.SendWhatsApp(contact, draftText!!, token)
        else if (message != null) Action.Reply(message.id, draftText!!, token)
        else {
            reset()
            return Turn("Le destinataire n'est plus disponible. Recommence la demande.")
        }
        stage = Stage.EXECUTING
        pendingToken = token
        confirmationAt = null
        confirmationDeliveryPending = false
        return Turn(if (contact != null) "Envoi confirmé. Vérification du destinataire dans WhatsApp." else "Demande de transmission à WhatsApp.", action = action)
    }

    private fun readMessages(command: String, messages: List<IncomingMessage>, nowMillis: Long): Turn {
        clearContext()
        val readMatch = READ_PATTERN.matchEntire(command)
            ?: return Turn("Demande « lis mes derniers messages WhatsApp » ou « lis le dernier WhatsApp de Camille ».", true)
        val sender = readMatch.groupValues[1].takeIf { it.isNotBlank() }
        val available = messages.asSequence()
            .filter { it.id.isNotBlank() && it.sender.isNotBlank() && it.sender.length <= MAX_NAME_LENGTH }
            .distinctBy { it.id }
            .filter { sender == null || nameMatches(it.sender, sender) }
            .sortedWith(compareByDescending<IncomingMessage> { it.receivedAtMillis }.thenBy { it.id })
            .toList()
        if (available.isEmpty()) return Turn("Je n'ai pas de message récent accessible à lire pour cette demande. Je n'ai pas accès à tout l'historique WhatsApp.")
        if (sender != null && available.map { normalize(it.sender) }.distinct().size > 1) {
            return Turn("Plusieurs expéditeurs correspondent. Précise le nom complet avant la lecture.", true)
        }
        val latestOnly = command.contains("le dernier ")
        if (latestOnly && available.count { it.receivedAtMillis == available.first().receivedAtMillis } > 1) {
            return Turn("Plusieurs messages ont la même date. Précise l'expéditeur avant de répondre.", true)
        }
        val selected = if (latestOnly) available.take(1) else available.take(MAX_READ_MESSAGES)
        val header = "Messages accessibles dans les notifications. "
        val limit = if (!latestOnly && available.size > MAX_READ_MESSAGES) " Je lis seulement les $MAX_READ_MESSAGES messages accessibles les plus récents." else ""
        val entryBudget = (MAX_READ_PROMPT_LENGTH - header.length - limit.length - READ_TAIL_RESERVE - selected.size * 2) / selected.size
        var shortened = false
        val content = selected.mapIndexed { index, item ->
            val label = "${index + 1}. ${item.sender} : « "
            val bodyBudget = minOf(MAX_MESSAGE_LENGTH, entryBudget - label.length - 2)
            val body = when {
                item.text.isBlank() -> "Contenu masqué ou inaccessible."
                item.text.length > bodyBudget -> {
                    shortened = true
                    item.text.take(bodyBudget - ABBREVIATION_NOTICE.length) + ABBREVIATION_NOTICE
                }
                else -> item.text
            }
            "$label$body »"
        }.joinToString(". ")
        if (!shortened && selected.size == 1 && selected.single().canReply && selected.single().text.isNotBlank()) {
            replyContext = selected.single()
            contextAt = nowMillis
            contextDeliveryPending = true
        }
        val followUp = when {
            shortened -> " La lecture est abrégée. Demande un seul message entièrement accessible avant de répondre."
            replyContext != null -> " Tu peux demander une réponse à ce message."
            else -> " Précise un seul message avant de demander une réponse."
        }
        return Turn("$header$content.$limit$followUp", replyContext != null)
    }

    private fun unavailableContact(): Turn {
        reset()
        return Turn("Le contact a changé ou n'est plus accessible. Recommence pour vérifier le destinataire.")
    }

    private fun clearDraft() {
        targetContact = null
        targetMessage = null
        draftText = null
        candidates = emptyList()
        confirmationAt = null
        confirmationDeliveryPending = false
        pendingToken = null
    }

    private fun clearContext() {
        replyContext = null
        contextAt = null
        contextDeliveryPending = false
    }

    private fun isFresh(start: Long?, now: Long, timeout: Long): Boolean =
        start != null && now >= start && now - start <= timeout

    private fun contactLabel(contact: Contact): String =
        "${contact.name}, numéro finissant par ${digits(contact.phone).takeLast(4)}"

    private fun nameMatches(name: String, query: String): Boolean {
        val key = normalize(query)
        val candidate = normalize(name)
        return key.isNotBlank() && (candidate == key || candidate.startsWith("$key ") || candidate.startsWith("$key,"))
    }

    private fun digits(phone: String): String = phone.filter { it in '0'..'9' }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .trim()
        .trim('.', ',', '!', '?', ';', ':')
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val MAX_INPUT_LENGTH = 4_000
        const val MAX_MESSAGE_LENGTH = 2_000
        const val MAX_RESULT_LENGTH = 1_000
        const val MAX_NAME_LENGTH = 200
        const val MAX_CHOICES = 5
        const val MAX_READ_MESSAGES = 5
        const val MAX_READ_PROMPT_LENGTH = 3_000
        const val READ_TAIL_RESERVE = 220
        const val ABBREVIATION_NOTICE = ". Lecture abrégée."
        const val CONFIRMATION_TIMEOUT = 60_000L
        const val CONTEXT_TIMEOUT = 120_000L
        val CANCEL_COMMANDS = setOf("annule", "annuler", "stop", "arrete", "laisse tomber", "non annule")
        val RESTART_COMMANDS = setOf("recommence", "recommencer")
        val CHANGE_RECIPIENT_COMMANDS = setOf("change de destinataire", "changer de destinataire", "modifie le destinataire")
        val EDIT_COMMANDS = setOf("modifie le message", "modifier le message", "change le message", "corrige le message")
        val REJECT_COMMANDS = setOf("non", "non merci", "n'envoie pas", "ne l'envoie pas", "ne prepare pas")
        val CONFIRM_COMMANDS = setOf("oui", "oui envoie", "oui, envoie", "envoie", "envoyer", "confirme", "je confirme", "oui je confirme", "oui, je confirme")
        val CHOICE_WORDS = mapOf("un" to 1, "une" to 1, "premier" to 1, "le premier" to 1, "deux" to 2, "le deuxieme" to 2, "trois" to 3, "le troisieme" to 3, "quatre" to 4, "cinq" to 5)
        val COMPOSE_PATTERN = Regex("^(?:(?:tu peux|peux-tu)\\s+)?(?:(?:envoie|envoyer|[eé]cris|[eé]crire|dis|dire)(?:\\s+(?:un\\s+)?(?:message(?:\\s+whatsapp)?|whatsapp))?\\s+[àa]|pr[eé]viens|pr[eé]venir)\\s*(.*)$", RegexOption.IGNORE_CASE)
        val BODY_SEPARATOR = Regex("\\s+(?:pour lui dire que|pour dire que|que)\\s+|\\s*:\\s*", RegexOption.IGNORE_CASE)
        val REPLY_PATTERN = Regex("^r[eé]ponds(?:[- ]lui)?(?:\\s+(?:que|avec)\\s+|\\s*:\\s*|\\s+)?(.*)$", RegexOption.IGNORE_CASE)
        val CORRECTION_PATTERN = Regex("^(?:non[, ]+)?remplace\\s+(.+?)\\s+par\\s+(.+)$", RegexOption.IGNORE_CASE)
        val SUFFIX_PATTERN = Regex("^(?:le )?(?:numero (?:qui )?fini(?:t|ssant) par )?([0-9]{4})$")
        val READ_PATTERN = Regex("^lis(?: moi)? (?:mes (?:derniers )?(?:messages|whatsapps?)(?: whatsapp)?|(?:le dernier|les(?: derniers)?) (?:message(?:s)?(?: whatsapp)?|whatsapps?)(?: de (.+))?)$")
    }
}
