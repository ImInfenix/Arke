package com.arke.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import com.arke.android.platform.WhatsAppLauncher
import com.arke.core.Contact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class WhatsAppLauncherTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private val contact = Contact("camille", "Camille", "+33612345678")

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun aLockedDeviceNeverLaunchesWhatsApp() {
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)

        val outcome = WhatsAppLauncher.prepare(activity, contact, "Je serai là demain")

        assertTrue(outcome.contains("Déverrouille"))
        assertTrue(outcome.contains("Aucun message envoyé"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun invalidPhoneNumbersAndMessagesNeverLaunchWhatsApp() {
        val invalidNumbers = listOf("0612345678", "+0123456789", "+336123", "+336123456789012345")
        for (number in invalidNumbers) {
            val outcome = WhatsAppLauncher.prepare(activity, contact.copy(phone = number), "Bonjour")
            assertTrue(outcome.contains("Aucun message envoyé"))
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        for (message in listOf("", " ", "x".repeat(2001))) {
            val outcome = WhatsAppLauncher.prepare(activity, contact, message)
            assertTrue(outcome.contains("Aucun message envoyé"))
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun preparingPreservesTheRecipientAndTextWithoutClaimingSuccessfulDelivery() {
        val message = "J'arrive à 18 h & j'apporte le dossier.\nÀ demain !"

        val outcome = WhatsAppLauncher.prepare(activity, contact, message)
        val launch = shadowOf(activity).nextStartedActivity

        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals("com.whatsapp", launch.`package`)
        assertEquals("https", launch.data?.scheme)
        assertEquals("wa.me", launch.data?.host)
        assertEquals("/33612345678", launch.data?.path)
        assertEquals(message, launch.data?.getQueryParameter("text"))
        assertTrue(outcome.contains("Envoi manuel nécessaire"))
        assertTrue(outcome.contains("aucun envoi confirmé"))
        assertFalse(outcome.contains("Message envoyé"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
