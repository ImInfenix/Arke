package com.arke.core.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationSendGateTest {
    private val request = SendRequest("confirmation-1", "+33612345678", "J'arrive bientôt.\nÀ tout à l'heure !", 0L)
    private val chat = UiSnapshot("com.whatsapp", Screen.CHAT, 42, "Camille", request.text,
        hasUniqueHeader = true, hasUniqueSendButton = true)
    private val profile = UiSnapshot("com.whatsapp", Screen.CONTACT_INFO, 43, "Camille", phoneNumbers = setOf(request.phone))

    @Test
    fun anUnarmedGateNeverRequestsAnAction() {
        val gate = ConfirmationSendGate()
        assertTrue(gate.observe(chat, 0L) is Decision.Stop)
        assertTrue(gate.observe(profile, 1L) is Decision.Stop)
        assertTrue(gate.completeClick(true) is Decision.Stop)
    }

    @Test
    fun exactRecipientAndTextRequireTheWholeVerificationSequence() {
        val gate = armed()
        assertEquals(Decision.Wait, gate.observe(chat, 10L))
        assertEquals(Decision.Wait, gate.observe(chat, 159L))
        assertEquals(Decision.OpenContactInfo, gate.observe(chat, 160L))
        assertEquals(Decision.ReturnToChat, gate.observe(profile, 300L))
        assertEquals(Decision.Wait, gate.observe(chat, 400L))
        assertEquals(Decision.Wait, gate.observe(chat, 549L))
        val decision = gate.observe(chat, 550L) as Decision.ClickSend
        assertEquals(chat, decision.expected)
        assertEquals(5300L, decision.expiresAtMillis)
        val result = gate.completeClick(true) as Decision.Stop
        assertTrue(result.reason.contains("Action Envoyer déclenchée"))
        assertTrue(result.reason.contains("Livraison non confirmée"))
    }

    @Test
    fun noPhoneVerificationMeansNoSendAuthorization() {
        val gate = waitingForProfile()
        repeat(5) { assertEquals(Decision.Wait, gate.observe(chat, 300L + it * 200L)) }
        assertTrue(gate.observe(chat, 30_001L) is Decision.Stop)
    }

    @Test
    fun aWrongProfilePhoneStopsTheRequest() {
        val gate = waitingForProfile()
        assertTrue(gate.observe(profile.copy(phoneNumbers = setOf("+33622334455")), 300L) is Decision.Stop)
        assertTrue(gate.observe(profile, 301L) is Decision.Stop)
        assertTrue(gate.observe(chat, 500L) is Decision.Stop)
    }

    @Test
    fun multipleDifferentPhonesAreAmbiguousEvenIfOneMatches() {
        val gate = waitingForProfile()
        assertTrue(gate.observe(profile.copy(phoneNumbers = setOf(request.phone, "+33622334455")), 300L) is Decision.Stop)
    }

    @Test
    fun aNameOrMessageContainingThePhoneDoesNotVerifyTheRecipient() {
        val gate = waitingForProfile()
        val counterfeit = profile.copy(title = request.phone, composerText = request.phone, phoneNumbers = emptySet())
        assertEquals(Decision.Wait, gate.observe(counterfeit, 300L))
        assertTrue(gate.observe(chat, 30_001L) is Decision.Stop)
    }

    @Test
    fun unnormalizedPhonesCannotVerifyTheRecipient() {
        val gate = waitingForProfile()
        assertTrue(gate.observe(profile.copy(phoneNumbers = setOf("06 12 34 56 78")), 300L) is Decision.Stop)
    }

    @Test
    fun aLoadingComposerMaySettleButNeverTriggersAnActionBeforeItMatches() {
        val gate = armed()
        assertEquals(Decision.Wait, gate.observe(chat.copy(composerText = "Un ancien brouillon"), 10L))
        assertEquals(Decision.Wait, gate.observe(chat.copy(composerText = null), 200L))
        assertEquals(Decision.Wait, gate.observe(chat, 400L))
        assertEquals(Decision.OpenContactInfo, gate.observe(chat, 550L))
    }

    @Test
    fun changedTextAfterTheFirstMatchingCaptureStopsTheRequest() {
        val gate = armed()
        gate.observe(chat, 10L)
        assertTrue(gate.observe(chat.copy(composerText = request.text + " oui"), 200L) is Decision.Stop)
    }

    @Test
    fun textWhitespaceAndCaseMustRemainExactAfterVerification() {
        for (changed in listOf(request.text.lowercase(), request.text.replace("\n", " "), request.text + " ")) {
            val gate = returningToChat()
            assertTrue(gate.observe(chat.copy(composerText = changed), 400L) is Decision.Stop)
        }
    }

    @Test
    fun changingTheWindowOrTitleAfterVerificationStopsTheRequest() {
        for (changed in listOf(chat.copy(windowId = 44), chat.copy(title = "Alex"))) {
            val gate = returningToChat()
            assertTrue(gate.observe(changed, 400L) is Decision.Stop)
        }
    }

    @Test
    fun aMissingOrAmbiguousSendButtonNeverAuthorizesTheClick() {
        val gate = returningToChat()
        repeat(5) { assertEquals(Decision.Wait, gate.observe(chat.copy(hasUniqueSendButton = false), 400L + it * 200L)) }
        assertTrue(gate.observe(chat, 5301L) is Decision.Stop)
    }

    @Test
    fun aMissingOrAmbiguousHeaderNeverOpensTheProfile() {
        val gate = armed()
        repeat(5) { assertEquals(Decision.Wait, gate.observe(chat.copy(hasUniqueHeader = false), it * 200L)) }
        assertTrue(gate.observe(chat, 30_001L) is Decision.Stop)
    }

    @Test
    fun anUnknownWindowOrTitleCannotStartVerification() {
        for (unknown in listOf(chat.copy(windowId = -1), chat.copy(title = null), chat.copy(title = " "))) {
            val gate = armed()
            assertEquals(Decision.Wait, gate.observe(unknown, 0L))
            assertEquals(Decision.Wait, gate.observe(unknown, 200L))
        }
    }

    @Test
    fun foreignForegroundCancelsAfterWhatsAppAppears() {
        for (foreign in listOf("com.arke.android", "com.android.launcher", "com.whatsapp.w4b")) {
            val gate = armed()
            gate.observe(chat, 10L)
            assertTrue(gate.observe(chat.copy(packageName = foreign), 200L) is Decision.Stop)
            assertTrue(gate.observe(chat, 400L) is Decision.Stop)
        }
    }

    @Test
    fun onlyArkeGetsABoundedGraceBeforeWhatsAppAppears() {
        val gate = armed()
        val arke = UiSnapshot("com.arke.android", Screen.OTHER)
        assertEquals(Decision.Wait, gate.observe(arke, 2500L))
        assertTrue(gate.observe(arke, 2501L) is Decision.Stop)
        val otherGate = armed()
        assertTrue(otherGate.observe(arke.copy(packageName = "com.android.launcher"), 0L) is Decision.Stop)
    }

    @Test
    fun aNullRootWaitsOnlyUntilTheRequestDeadline() {
        val gate = armed()
        assertEquals(Decision.Wait, gate.observe(null, 500L))
        assertTrue(gate.observe(null, 30_001L) is Decision.Stop)
    }

    @Test
    fun lockingOrDisconnectingCancelsAtEveryStep() {
        for (phase in 0..2) {
            for (locked in listOf(true, false)) {
                val gate = when (phase) {
                    0 -> armed()
                    1 -> waitingForProfile()
                    else -> returningToChat()
                }
                assertTrue(gate.observe(chat, 400L, unlocked = !locked, connected = locked) is Decision.Stop)
                assertTrue(gate.observe(chat, 600L) is Decision.Stop)
            }
        }
    }

    @Test
    fun theWholeRequestDeadlineIsNotRenewedByObservations() {
        val gate = armed()
        for (time in 1_000L..29_000L step 1000L) assertEquals(Decision.Wait, gate.observe(null, time))
        assertTrue(gate.observe(chat, 30_001L) is Decision.Stop)
    }

    @Test
    fun phoneProofExpiresEvenWhenTheRequestItselfIsFresh() {
        val gate = returningToChat()
        assertTrue(gate.observe(chat, 5301L) is Decision.Stop)
    }

    @Test
    fun clockRollbackCancelsRatherThanExtendingTheWindow() {
        val gate = armed()
        gate.observe(chat, 100L)
        assertTrue(gate.observe(chat, 99L) is Decision.Stop)
    }

    @Test
    fun profileVerificationAndBackNavigationAreEachAuthorizedOnlyOnce() {
        val gate = waitingForProfile()
        assertEquals(Decision.Wait, gate.observe(chat, 250L))
        assertEquals(Decision.ReturnToChat, gate.observe(profile, 300L))
        assertEquals(Decision.Wait, gate.observe(profile, 301L))
        assertEquals(Decision.Wait, gate.observe(profile, 500L))
        assertEquals(Decision.Wait, gate.observe(chat, 600L))
        assertTrue(gate.observe(chat, 750L) is Decision.ClickSend)
    }

    @Test
    fun modifyingTheProfileBeforeReturningCancelsTheRequest() {
        val gate = returningToChat()
        assertTrue(gate.observe(profile.copy(phoneNumbers = setOf("+33622334455")), 400L) is Decision.Stop)
    }

    @Test
    fun stableCaptureDelayMustRestartAfterALoadingScreen() {
        val gate = returningToChat()
        assertEquals(Decision.Wait, gate.observe(chat, 400L))
        assertEquals(Decision.Wait, gate.observe(UiSnapshot("com.whatsapp", Screen.OTHER), 500L))
        assertEquals(Decision.Wait, gate.observe(chat, 550L))
        assertEquals(Decision.Wait, gate.observe(chat, 699L))
        assertTrue(gate.observe(chat, 700L) is Decision.ClickSend)
    }

    @Test
    fun duplicateEventsAfterClickAuthorizationNeverAuthorizeAnotherClick() {
        val gate = readyToClick()
        assertTrue(gate.observe(chat, 550L) is Decision.ClickSend)
        repeat(5) { assertTrue(gate.observe(chat, 600L + it) is Decision.Stop) }
        assertTrue(gate.armConfirmed(request, 700L) is Decision.Stop)
    }

    @Test
    fun bothSuccessfulAndFailedClicksConsumeTheConfirmationPermanently() {
        for (success in listOf(true, false)) {
            val gate = readyToClick()
            assertTrue(gate.observe(chat, 550L) is Decision.ClickSend)
            val result = gate.completeClick(success) as Decision.Stop
            assertFalse(result.reason.contains("Message envoyé"))
            assertTrue(gate.observe(chat, 600L) is Decision.Stop)
            assertTrue(gate.armConfirmed(request, 700L) is Decision.Stop)
        }
    }

    @Test
    fun canceledTokensCannotResurrectARequestButANewConfirmationCanStart() {
        val gate = armed()
        assertTrue(gate.cancel() is Decision.Stop)
        assertTrue(gate.armConfirmed(request, 100L) is Decision.Stop)
        assertEquals(Decision.Wait, gate.armConfirmed(request.copy(token = "new-confirmation", createdAtMillis = 200L), 200L))
        assertEquals(Decision.Wait, gate.observe(chat, 201L))
    }

    @Test
    fun aConcurrentNewConfirmationCancelsTheOldRequestAndCannotBeReplayed() {
        val gate = armed()
        val replacement = request.copy(token = "another-confirmation")
        assertTrue(gate.armConfirmed(replacement, 100L) is Decision.Stop)
        assertTrue(gate.observe(chat, 200L) is Decision.Stop)
        assertTrue(gate.armConfirmed(replacement, 300L) is Decision.Stop)
        assertTrue(gate.armConfirmed(request, 400L) is Decision.Stop)
    }

    @Test
    fun invalidPhoneTextTokenAndTimestampAreRejectedBeforeAnyAction() {
        val invalid = listOf(
            request.copy(phone = "0612345678"), request.copy(phone = "+330612345678".repeat(3)),
            request.copy(text = " "), request.copy(text = "a".repeat(2001)),
            request.copy(token = ""), request.copy(token = "a".repeat(201)),
            request.copy(createdAtMillis = -1L), request.copy(createdAtMillis = 1L),
        )
        for (candidate in invalid) {
            val gate = ConfirmationSendGate()
            assertTrue(gate.armConfirmed(candidate, 0L) is Decision.Stop)
            assertTrue(gate.observe(chat, 200L) is Decision.Stop)
        }
        assertTrue(ConfirmationSendGate().armConfirmed(request, 30_001L) is Decision.Stop)
    }

    @Test
    fun sendAuthorizationDeadlineIsTheEarlierRequestOrPhoneProofDeadline() {
        val gate = armed()
        gate.observe(chat, 28_000L)
        gate.observe(chat, 28_150L)
        gate.observe(profile, 29_000L)
        gate.observe(chat, 29_100L)
        val decision = gate.observe(chat, 29_250L) as Decision.ClickSend
        assertEquals(30_000L, decision.expiresAtMillis)
    }

    @Test
    fun theTokenLedgerStopsAtItsLimitInsteadOfForgettingOldConfirmations() {
        val gate = ConfirmationSendGate()
        repeat(2048) { index ->
            assertEquals(Decision.Wait, gate.armConfirmed(request.copy(token = "token-$index"), 0L))
            gate.cancel()
        }
        assertTrue(gate.armConfirmed(request.copy(token = "overflow"), 0L) is Decision.Stop)
        assertTrue(gate.armConfirmed(request.copy(token = "token-0"), 0L) is Decision.Stop)
    }

    private fun armed(): ConfirmationSendGate = ConfirmationSendGate().also {
        assertEquals(Decision.Wait, it.armConfirmed(request, 0L))
    }

    private fun waitingForProfile(): ConfirmationSendGate = armed().also {
        assertEquals(Decision.Wait, it.observe(chat, 10L))
        assertEquals(Decision.OpenContactInfo, it.observe(chat, 160L))
    }

    private fun returningToChat(): ConfirmationSendGate = waitingForProfile().also {
        assertEquals(Decision.ReturnToChat, it.observe(profile, 300L))
    }

    private fun readyToClick(): ConfirmationSendGate = returningToChat().also {
        assertEquals(Decision.Wait, it.observe(chat, 400L))
    }
}
