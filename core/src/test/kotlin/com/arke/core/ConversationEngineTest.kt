package com.arke.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {
    private val camille = Contact("camille", "Camille", "+33612345678")
    private val alexMartin = Contact("alex-martin", "Alex Martin", "+33611223344")
    private val alexDupont = Contact("alex-dupont", "Alex Dupont", "+33622334455")
    private val contacts = listOf(camille, alexMartin, alexDupont)
    private val message = IncomingMessage("message-1", "Camille", "Tu arrives quand ?", 100L, true)

    @Test
    fun newMessageRequiresRecipientDictationAndSeparateConfirmation() {
        val engine = ConversationEngine()
        val first = input(engine, "Envoie un WhatsApp à Camille")
        assertEquals(Stage.DICTATION, engine.stage)
        assertNull(first.action)
        val draft = input(engine, "J'arrive dans dix minutes.")
        assertEquals(Stage.CONFIRMATION, engine.stage)
        assertTrue(draft.prompt.contains("J'arrive dans dix minutes."))
        assertTrue(draft.prompt.contains("Tu confirmes l'envoi dans WhatsApp ?"))
        assertNull(draft.action)
        val effect = input(engine, "Oui, envoie.").action as Action.SendWhatsApp
        assertEquals(camille, effect.contact)
        assertEquals("J'arrive dans dix minutes.", effect.text)
    }

    @Test
    fun sendingWordInsideDictationNeverTriggersAction() {
        val engine = ConversationEngine()
        input(engine, "Envoie à Camille")
        val draft = input(engine, "N'oublie pas d'envoyer le dossier ce soir.")
        assertNull(draft.action)
        assertEquals(Stage.CONFIRMATION, engine.stage)
        assertTrue(draft.prompt.contains("N'oublie pas d'envoyer le dossier ce soir."))
    }

    @Test
    fun yesAndSendAreAlsoValidMessageBodiesDuringDictation() {
        for (body in listOf("oui", "envoyer", "envoie")) {
            val engine = ConversationEngine()
            input(engine, "Envoie à Camille")
            assertNull(input(engine, body).action)
            val effect = input(engine, "oui").action as Action.SendWhatsApp
            assertEquals(body, effect.text)
        }
    }

    @Test
    fun strayConfirmationDoesNotCreateAnAction() {
        val engine = ConversationEngine()
        assertNull(input(engine, "oui envoie").action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun preparationOnlyWordingCannotAuthorizeAutomaticSending() {
        for (phrase in listOf("prépare", "oui prépare", "oui, prépare", "prépare le message")) {
            val engine = ConversationEngine()
            input(engine, "Envoie à Camille que bonjour")
            assertNull(input(engine, phrase).action)
            assertEquals(Stage.CONFIRMATION, engine.stage)
            assertTrue(input(engine, "oui envoie").action is Action.SendWhatsApp)
        }
    }

    @Test
    fun naturalInlineRequestPreservesMessageText() {
        val engine = ConversationEngine()
        val first = input(engine, "Préviens Camille que j'aurai quinze minutes de retard.")
        assertEquals(Stage.CONFIRMATION, engine.stage)
        assertNull(first.action)
        val effect = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("j'aurai quinze minutes de retard.", effect.text)
    }

    @Test
    fun courtesyRequestUsesSameSafeFlow() {
        val engine = ConversationEngine()
        input(engine, "Tu peux prévenir Camille que j'arrive bientôt")
        assertEquals(Stage.CONFIRMATION, engine.stage)
        val effect = input(engine, "je confirme").action as Action.SendWhatsApp
        assertEquals("j'arrive bientôt", effect.text)
    }

    @Test
    fun bodyAfterLongDelimiterIsNotRewritten() {
        val engine = ConversationEngine()
        input(engine, "Envoie un WhatsApp à Camille pour lui dire que je suis en retard")
        val effect = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("je suis en retard", effect.text)
    }

    @Test
    fun ambiguousFirstNameRequiresChoiceAndKeepsInlineText() {
        val engine = ConversationEngine()
        val choices = input(engine, "Écris à Alex que je passe demain")
        assertEquals(Stage.DISAMBIGUATION, engine.stage)
        assertTrue(choices.prompt.contains("Alex Dupont"))
        assertTrue(choices.prompt.contains("Alex Martin"))
        assertNull(input(engine, "oui").action)
        input(engine, "Alex Martin")
        assertEquals(Stage.CONFIRMATION, engine.stage)
        val effect = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals(alexMartin, effect.contact)
        assertEquals("je passe demain", effect.text)
    }

    @Test
    fun homonymsWithDifferentNumbersRemainSeparateChoices() {
        val otherNumber = camille.copy(id = "camille-work", phone = "+33687654321")
        val directory = listOf(camille, otherNumber)
        val engine = ConversationEngine()
        val choices = engine.accept("Envoie à Camille que bonjour", directory, emptyList(), 0L)
        assertEquals(Stage.DISAMBIGUATION, engine.stage)
        assertTrue(choices.prompt.contains("5678"))
        assertTrue(choices.prompt.contains("4321"))
        assertNull(engine.accept("Camille", directory, emptyList(), 0L).action)
        engine.accept("numéro qui finit par 4321", directory, emptyList(), 0L)
        val action = engine.accept("oui", directory, emptyList(), 0L).action as Action.SendWhatsApp
        assertEquals(otherNumber, action.contact)
    }

    @Test
    fun shortFragmentsNeverSilentlySelectAName() {
        val engine = ConversationEngine()
        input(engine, "Envoie à Cam que bonjour")
        assertEquals(Stage.RECIPIENT, engine.stage)
        assertNull(input(engine, "oui").action)
    }

    @Test
    fun accentsAndCaseDoNotPreventExactContactMatch() {
        val directory = listOf(Contact("lea", "Léa Martin", "+33612345678"))
        val engine = ConversationEngine()
        engine.accept("Envoie à LEA MARTIN que bonjour", directory, emptyList(), 0L)
        val action = engine.accept("oui", directory, emptyList(), 0L).action as Action.SendWhatsApp
        assertEquals(directory.single(), action.contact)
    }

    @Test
    fun cancelClearsDraftAndLaterYesDoesNothing() {
        val engine = readyDraft()
        input(engine, "annule")
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "oui").action)
    }

    @Test
    fun negativeAnswerCancelsInsteadOfSendingOrRetainingConsent() {
        val engine = readyDraft()
        input(engine, "non")
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "oui").action)
    }

    @Test
    fun ambientSentenceContainingYesDoesNotConfirm() {
        val engine = readyDraft()
        val answer = input(engine, "oui peut-être mais attends")
        assertNull(answer.action)
        assertEquals(Stage.CONFIRMATION, engine.stage)
        assertTrue(answer.prompt.contains("confirmation explicite"))
    }

    @Test
    fun messageCorrectionIsReadBackBeforeAnotherConfirmation() {
        val engine = readyDraft("Je passe demain matin.")
        val corrected = input(engine, "Non, remplace demain par vendredi")
        assertNull(corrected.action)
        assertTrue(corrected.prompt.contains("Je passe vendredi matin."))
        val action = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("Je passe vendredi matin.", action.text)
    }

    @Test
    fun ambiguousCorrectionNeverChangesMultipleOccurrences() {
        val engine = readyDraft("demain ou après demain")
        val corrected = input(engine, "remplace demain par vendredi")
        assertTrue(corrected.prompt.contains("sans ambiguïté"))
        val action = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("demain ou après demain", action.text)
    }

    @Test
    fun correctionDoesNotReplaceAnUnrelatedWordSuffix() {
        val engine = readyDraft("le lendemain matin")
        input(engine, "remplace demain par vendredi")
        val action = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("le lendemain matin", action.text)
    }

    @Test
    fun completeRedictationDoesNotTreatBodyAsConfirmation() {
        val engine = readyDraft()
        input(engine, "modifie le message")
        assertEquals(Stage.DICTATION, engine.stage)
        assertNull(input(engine, "oui").action)
        val action = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("oui", action.text)
    }

    @Test
    fun changingRecipientKeepsBodyButRequiresFreshReview() {
        val engine = readyDraft("bonjour")
        input(engine, "change de destinataire")
        assertEquals(Stage.RECIPIENT, engine.stage)
        assertNull(input(engine, "Alex Martin").action)
        val action = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals(alexMartin, action.contact)
        assertEquals("bonjour", action.text)
    }

    @Test
    fun confirmationExpiresAndCannotSend() {
        val engine = readyDraft()
        val expired = input(engine, "oui", now = 60_001L)
        assertNull(expired.action)
        assertEquals(Stage.IDLE, engine.stage)
        assertTrue(expired.prompt.contains("expiré"))
    }

    @Test
    fun backwardsClockInvalidatesConfirmation() {
        val engine = ConversationEngine()
        input(engine, "Envoie à Camille que bonjour", now = 100L)
        assertNull(input(engine, "oui", now = 99L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun removedOrChangedContactMustBeResolvedAgain() {
        val engine = readyDraft()
        val changed = camille.copy(phone = "+33600000000")
        val result = engine.accept("oui", listOf(changed), emptyList(), 0L)
        assertNull(result.action)
        assertEquals(Stage.IDLE, engine.stage)
        assertTrue(result.prompt.contains("contact a changé"))
    }

    @Test
    fun duplicateConfirmationCannotEmitSecondEffect() {
        val engine = readyDraft()
        val action = input(engine, "oui").action!!
        assertEquals(Stage.EXECUTING, engine.stage)
        assertNull(input(engine, "oui").action)
        assertNull(input(engine, "Envoie à Camille que deuxième message").action)
        engine.complete(action.token, "Action Envoyer déclenchée. Livraison non confirmée.")
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "oui").action)
    }

    @Test
    fun staleCompletionCannotConsumeCurrentAction() {
        val engine = readyDraft()
        val action = input(engine, "oui").action!!
        engine.complete("wrong-token", "Unexpected")
        assertEquals(Stage.EXECUTING, engine.stage)
        val valid = engine.complete(action.token, "Vérification terminée.")
        assertEquals("Vérification terminée.", valid.prompt)
        assertEquals(Stage.IDLE, engine.stage)
        assertFalse(engine.complete(action.token, "Repeated").prompt.contains("Repeated"))
    }

    @Test
    fun cancelAfterExecutionDoesNotClaimToUndoTransmission() {
        val engine = readyDraft()
        input(engine, "oui")
        val result = engine.cancel()
        assertEquals(Stage.EXECUTING, engine.stage)
        assertTrue(result.prompt.contains("annulation n'est pas garantie"))
    }

    @Test
    fun independentActionsHaveDifferentTokens() {
        val engine = readyDraft()
        val first = input(engine, "oui").action!!
        engine.complete(first.token, "Action engagée.")
        input(engine, "Envoie à Camille que bonjour")
        val second = input(engine, "oui").action!!
        assertNotEquals(first.token, second.token)
    }

    @Test
    fun incomingContentIsNeverReadSpontaneously() {
        val engine = ConversationEngine()
        val response = input(engine, "bonjour", listOf(message))
        assertFalse(response.prompt.contains(message.text))
        assertNull(response.action)
    }

    @Test
    fun explicitReadingCreatesReplyContextButDoesNotTransmit() {
        val engine = ConversationEngine()
        val read = input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        assertTrue(read.prompt.contains(message.text))
        assertNull(read.action)
        assertEquals(Stage.IDLE, engine.stage)
        val reply = input(engine, "Réponds-lui que j'arrive bientôt", listOf(message))
        assertNull(reply.action)
        assertTrue(reply.prompt.contains("j'arrive bientôt"))
        val action = input(engine, "oui", listOf(message)).action as Action.Reply
        assertEquals(message.id, action.messageId)
        assertEquals("j'arrive bientôt", action.text)
    }

    @Test
    fun replyingWithoutReadContextDoesNothing() {
        val engine = ConversationEngine()
        assertNull(input(engine, "Réponds que bonjour", listOf(message)).action)
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "oui", listOf(message)).action)
    }

    @Test
    fun readingMultipleConversationsNeverChoosesOneForReply() {
        val other = message.copy(id = "other", sender = "Alex Martin", receivedAtMillis = 200L)
        val engine = ConversationEngine()
        val reading = input(engine, "Lis mes derniers messages WhatsApp", listOf(message, other))
        assertTrue(reading.prompt.contains("Camille"))
        assertTrue(reading.prompt.contains("Alex Martin"))
        assertNull(input(engine, "Réponds que bonjour", listOf(message, other)).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun latestMessageRequestDoesNotChooseBetweenEqualTimestamps() {
        val other = message.copy(id = "other", sender = "Alex Martin")
        val engine = ConversationEngine()
        val result = input(engine, "Lis le dernier WhatsApp", listOf(message, other))
        assertFalse(result.prompt.contains(message.text))
        assertTrue(result.prompt.contains("même date"))
        input(engine, "Réponds que bonjour", listOf(message, other))
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun replyContextExpiresAfterTwoMinutes() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        val result = input(engine, "Réponds que bonjour", listOf(message), 120_001L)
        assertNull(result.action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun removedNotificationCannotBeRepliedTo() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        input(engine, "Réponds que bonjour", listOf(message))
        val result = input(engine, "oui", emptyList())
        assertNull(result.action)
        assertTrue(result.prompt.contains("plus disponible"))
    }

    @Test
    fun updatedNotificationInvalidatesPreviouslyReviewedReply() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        input(engine, "Réponds que bonjour", listOf(message))
        val update = message.copy(text = "Ne viens plus.", receivedAtMillis = 200L)
        assertNull(input(engine, "oui", listOf(update)).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun maskedMessageAndMissingReplyActionDoNotCreateReplyContext() {
        for (unavailable in listOf(message.copy(text = ""), message.copy(canReply = false))) {
            val engine = ConversationEngine()
            input(engine, "Lis le dernier WhatsApp de Camille", listOf(unavailable))
            input(engine, "Réponds que bonjour", listOf(unavailable))
            assertEquals(Stage.IDLE, engine.stage)
        }
    }

    @Test
    fun noAccessibleMessagesDoesNotClaimToHaveCheckedHistory() {
        val engine = ConversationEngine()
        val response = input(engine, "Lis mes derniers messages WhatsApp")
        assertTrue(response.prompt.contains("pas accès à tout l'historique"))
        assertNull(response.action)
    }

    @Test
    fun readingIsBoundedToFiveMessages() {
        val incoming = (1..8).map { message.copy(id = "message-$it", receivedAtMillis = it.toLong(), text = "Unique text $it") }
        val engine = ConversationEngine()
        val response = input(engine, "Lis mes derniers messages WhatsApp", incoming)
        assertTrue(response.prompt.contains("Unique text 8"))
        assertTrue(response.prompt.contains("Unique text 4"))
        assertFalse(response.prompt.contains("Unique text 3"))
        assertTrue(response.prompt.contains("seulement les 5"))
    }

    @Test
    fun cancelAlsoClearsReplyContext() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        input(engine, "annule", listOf(message))
        input(engine, "Réponds que bonjour", listOf(message))
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun overlongInputCannotConfirmOrReplaceTheDraft() {
        val engine = readyDraft("bonjour")
        assertNull(input(engine, "oui " + "a".repeat(4_000)).action)
        val effect = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("bonjour", effect.text)
    }

    @Test
    fun overlongDictationRequiresNewContentAndNoEffect() {
        val engine = ConversationEngine()
        input(engine, "Envoie à Camille")
        assertNull(input(engine, "a".repeat(2_001)).action)
        assertEquals(Stage.DICTATION, engine.stage)
        input(engine, "Court message")
        val effect = input(engine, "oui").action as Action.SendWhatsApp
        assertEquals("Court message", effect.text)
    }

    @Test
    fun fiveLongMessagesKeepWholePromptWithinSpeechBudget() {
        val incoming = (1..5).map {
            message.copy(
                id = "long-message-$it",
                sender = "Expéditeur $it " + "n".repeat(180),
                text = "Long message $it " + "x".repeat(3_000),
                receivedAtMillis = it.toLong(),
            )
        }
        val engine = ConversationEngine()
        val response = input(engine, "Lis mes derniers messages WhatsApp", incoming)
        assertTrue(response.prompt.length <= 3_000)
        assertTrue(response.prompt.contains("La lecture est abrégée"))
        for (index in 1..5) assertTrue(response.prompt.contains("Long message $index"))
        assertFalse(response.listen)
        input(engine, "Réponds que bonjour", incoming)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun abridgedSingleMessageCannotBecomeReplyContext() {
        val incoming = listOf(message.copy(text = "x".repeat(3_000)))
        val engine = ConversationEngine()
        val response = input(engine, "Lis le dernier WhatsApp de Camille", incoming)
        assertTrue(response.prompt.length <= 3_000)
        assertTrue(response.prompt.contains("Lecture abrégée"))
        assertFalse(response.listen)
        assertNull(input(engine, "Réponds que bonjour", incoming).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun boundedCompleteSingleMessageStillAllowsReply() {
        val incoming = listOf(message.copy(text = "x".repeat(2_000)))
        val engine = ConversationEngine()
        val response = input(engine, "Lis le dernier WhatsApp de Camille", incoming)
        assertTrue(response.prompt.length <= 3_000)
        assertFalse(response.prompt.contains("abrégée"))
        input(engine, "Réponds que bonjour", incoming)
        assertEquals(Stage.CONFIRMATION, engine.stage)
    }

    @Test
    fun namesWithCommaSuffixStillRequirePhoneDisambiguation() {
        val first = Contact("alex-home", "Alex, numéro finissant par 3344", "+33611223344")
        val second = Contact("alex-work", "Alex, numéro finissant par 4455", "+33622334455")
        val directory = listOf(first, second)
        val engine = ConversationEngine()
        engine.accept("Envoie à Alex que bonjour", directory, emptyList(), 0L)
        assertEquals(Stage.DISAMBIGUATION, engine.stage)
        engine.accept("numéro qui finit par 4455", directory, emptyList(), 0L)
        val effect = engine.accept("oui", directory, emptyList(), 0L).action as Action.SendWhatsApp
        assertEquals(second, effect.contact)
    }

    @Test
    fun unrecognizedAnswersDoNotExtendConfirmationLifetime() {
        val engine = readyDraft()
        assertNull(input(engine, "peut-être", now = 59_000L).action)
        val expired = input(engine, "oui", now = 60_001L)
        assertNull(expired.action)
        assertEquals(Stage.IDLE, engine.stage)
        assertTrue(expired.prompt.contains("expiré"))
    }

    @Test
    fun rejectedCorrectionDoesNotExtendConfirmationLifetime() {
        val engine = readyDraft("demain ou après demain")
        assertNull(input(engine, "remplace demain par vendredi", now = 59_000L).action)
        assertNull(input(engine, "oui", now = 60_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun confirmationWindowStartsAfterLongReadbackDelivery() {
        val engine = readyDraft()
        engine.promptDelivered(90_000L)
        assertEquals(Stage.CONFIRMATION, engine.stage)
        val action = input(engine, "oui", now = 149_999L).action as Action.SendWhatsApp
        assertEquals("bonjour", action.text)
    }

    @Test
    fun deliveredPromptDoesNotCreateAnActionOrSurviveReset() {
        val engine = readyDraft()
        engine.promptDelivered(90_000L)
        assertEquals(Stage.CONFIRMATION, engine.stage)
        engine.reset()
        engine.promptDelivered(200_000L)
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "oui", now = 200_001L).action)
    }

    @Test
    fun duplicateDeliveryCannotKeepConfirmationAlive() {
        val engine = readyDraft()
        engine.promptDelivered(90_000L)
        input(engine, "peut-être", now = 149_000L)
        engine.promptDelivered(149_999L)
        assertNull(input(engine, "oui", now = 150_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun longExplicitReadingStartsReplyWindowAfterDelivery() {
        val incoming = listOf(message.copy(text = "x".repeat(2_000)))
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", incoming)
        engine.promptDelivered(180_000L)
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "Réponds que bonjour", incoming, 299_999L).action)
        assertEquals(Stage.CONFIRMATION, engine.stage)
    }

    @Test
    fun replyContextExpiresTwoMinutesAfterReadingDelivery() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        engine.promptDelivered(180_000L)
        assertNull(input(engine, "Réponds que bonjour", listOf(message), 300_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
        assertNull(input(engine, "oui", listOf(message), 300_002L).action)
    }

    @Test
    fun duplicateReadingDeliveryDoesNotExtendReplyContext() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        engine.promptDelivered(180_000L)
        engine.promptDelivered(299_999L)
        assertNull(input(engine, "Réponds que bonjour", listOf(message), 300_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun readingDeliveryAfterResetCannotRestoreReplyContext() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        engine.reset()
        engine.promptDelivered(180_000L)
        assertNull(input(engine, "Réponds que bonjour", listOf(message), 180_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun unrelatedPromptDeliveryCannotRenewAnInterruptedReading() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        input(engine, "bonjour", listOf(message), 1_000L)
        engine.promptDelivered(180_000L)
        assertNull(input(engine, "Réponds que bonjour", listOf(message), 180_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun longReplyReadbackUsesConfirmationWindowAfterContextWasValidated() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        input(engine, "Réponds que " + "x".repeat(2_000), listOf(message), 1_000L)
        assertEquals(Stage.CONFIRMATION, engine.stage)
        engine.promptDelivered(180_000L)
        val action = input(engine, "oui", listOf(message), 180_001L).action as Action.Reply
        assertEquals(message.id, action.messageId)
        assertEquals("x".repeat(2_000), action.text)
    }

    @Test
    fun notificationChangedDuringLongReplyReadbackStillPreventsTransmission() {
        val engine = ConversationEngine()
        input(engine, "Lis le dernier WhatsApp de Camille", listOf(message))
        input(engine, "Réponds que " + "x".repeat(2_000), listOf(message), 1_000L)
        engine.promptDelivered(180_000L)
        val updated = message.copy(text = "Nouvelle demande", receivedAtMillis = 180_000L)
        assertNull(input(engine, "oui", listOf(updated), 180_001L).action)
        assertEquals(Stage.IDLE, engine.stage)
    }

    @Test
    fun exactShortNameDoesNotHideOtherMatchingContacts() {
        val alex = Contact("alex", "Alex", "+33612345678")
        val directory = listOf(alex, alexMartin)
        val engine = ConversationEngine()
        val choices = engine.accept("Envoie à Alex que bonjour", directory, emptyList(), 0L)
        assertEquals(Stage.DISAMBIGUATION, engine.stage)
        assertNull(choices.action)
        assertTrue(choices.prompt.contains("Alex Martin"))
        assertTrue(choices.prompt.contains("5678"))
    }

    @Test
    fun repeatingAmbiguousExactShortNameStillRequiresExplicitChoice() {
        val alex = Contact("alex", "Alex", "+33612345678")
        val directory = listOf(alex, alexMartin)
        val engine = ConversationEngine()
        engine.accept("Envoie à Alex que bonjour", directory, emptyList(), 0L)
        assertNull(engine.accept("Alex", directory, emptyList(), 0L).action)
        assertEquals(Stage.DISAMBIGUATION, engine.stage)
        engine.accept("deux", directory, emptyList(), 0L)
        assertEquals(Stage.CONFIRMATION, engine.stage)
        val action = engine.accept("oui", directory, emptyList(), 0L).action as Action.SendWhatsApp
        assertEquals(alexMartin, action.contact)
        assertEquals("bonjour", action.text)
    }

    private fun readyDraft(body: String = "bonjour"): ConversationEngine = ConversationEngine().also {
        input(it, "Envoie à Camille que $body")
        assertEquals(Stage.CONFIRMATION, it.stage)
    }

    private fun input(
        engine: ConversationEngine,
        text: String,
        incoming: List<IncomingMessage> = emptyList(),
        now: Long = 0L,
    ): Turn = engine.accept(text, contacts, incoming, now)
}
