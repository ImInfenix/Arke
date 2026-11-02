package com.arke.android.platform.send

import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.arke.core.Contact
import com.arke.core.automation.Screen
import java.time.Duration
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class WhatsAppSendServiceTest {
    private lateinit var service: WhatsAppSendAccessibilityService
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val contact = Contact("camille", "Camille", "+33612345678")

    @Before
    fun setUp() {
        service = Robolectric.buildService(WhatsAppSendAccessibilityService::class.java).create().get()
        WhatsAppSendAccessibilityService.consumeOutcome()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        WhatsAppSendAccessibilityService.consumeOutcome()
    }

    @Test
    fun disconnectedAndLockedServicesNeverOpenWhatsApp() {
        assertTrue(start().contains("Active le service"))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        service.onServiceConnected()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        assertTrue(start().contains("Déverrouille"))
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun confirmedRequestPreservesExactTextAndCannotBeReplayedAfterCancellation() {
        service.onServiceConnected()
        val token = UUID.randomUUID().toString()
        val text = "Été & demain\nOui !"
        WhatsAppSendAccessibilityService.startConfirmedSend(context, contact, text, token)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("com.whatsapp", intent.`package`)
        assertEquals("33612345678", intent.data?.lastPathSegment)
        assertEquals(text, intent.data?.getQueryParameter("text"))
        assertTrue(WhatsAppSendAccessibilityService.hasPendingSend())
        WhatsAppSendAccessibilityService.cancelPending("Annulé")
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
        assertEquals("Annulé", WhatsAppSendAccessibilityService.consumeOutcome())
        WhatsAppSendAccessibilityService.startConfirmedSend(context, contact, text, token)
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun frequentEventsCannotPostponeTheAbsoluteTimeout() {
        service.onServiceConnected()
        start()
        repeat(610) {
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = "com.whatsapp"
            })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
        assertTrue(WhatsAppSendAccessibilityService.consumeOutcome().orEmpty().contains("expiré"))
    }

    @Test
    fun interruptStopsPendingRequestAndIdleEventsDoNothing() {
        service.onServiceConnected()
        start()
        service.onInterrupt()
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
        val outcome = WhatsAppSendAccessibilityService.consumeOutcome()
        assertNotNull(outcome)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.whatsapp"
        })
        assertNull(WhatsAppSendAccessibilityService.consumeOutcome())
    }

    @Test
    fun aPhoneInAnUnknownScreenDoesNotProveTheRecipient() {
        val root = node("root")
        shadowOf(root).addChild(node("phone_number", "+33 6 12 34 56 78"))
        assertEquals(Screen.OTHER, WhatsAppScreenCapture.read(root)?.snapshot?.screen)
        val profile = WhatsAppScreenCapture.read(root, "com.whatsapp.ContactInfo")
        assertEquals(Screen.CONTACT_INFO, profile?.snapshot?.screen)
        assertEquals(setOf(contact.phone), profile?.snapshot?.phoneNumbers)
    }

    @Test
    fun aPhoneWrittenInsideAMessageCannotProveTheRecipient() {
        val root = node("root")
        shadowOf(root).addChild(node("message_text", contact.phone))
        assertEquals(Screen.OTHER, WhatsAppScreenCapture.read(root, "com.whatsapp.ContactInfo")?.snapshot?.screen)
    }

    private fun start(): String = WhatsAppSendAccessibilityService.startConfirmedSend(
        context, contact, "Bonjour", UUID.randomUUID().toString())

    @Suppress("DEPRECATION")
    private fun node(id: String, value: String? = null): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.whatsapp"
        viewIdResourceName = "com.whatsapp:id/$id"
        text = value
        isVisibleToUser = true
        isEnabled = true
    }
}
