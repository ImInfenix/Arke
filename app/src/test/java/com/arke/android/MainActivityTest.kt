package com.arke.android

import android.Manifest
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.arke.android.platform.send.WhatsAppSendAccessibilityService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var prompt: TextView

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
        )
        ShadowTextToSpeech.addVoice(
            Voice("fr-FR-offline", Locale.FRANCE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        )
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        prompt = views().filterIsInstance<TextView>()
            .single { it.text.toString() == activity.getString(R.string.welcome) }
        val synthesis = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(synthesis).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun openingTheApplicationDoesNotRequestPermissionsOrSpeak() {
        assertEquals(activity.getString(R.string.welcome), prompt.text.toString())
        assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.READ_CONTACTS))
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun composingWithoutContactAccessNeverOpensWhatsApp() {
        submit("Envoie un WhatsApp à Camille que je serai là demain")

        assertTrue(prompt.text.toString().contains("Je ne trouve pas ce contact"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).lastRequestedPermission)

        submit("oui")

        assertTrue(prompt.text.toString().contains("Je ne trouve pas ce contact"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun notificationSettingsRequireAcceptingTheAccessExplanation() {
        button(R.string.setup).performClick()
        button(R.string.notification_access).performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals(activity.getString(R.string.notification_explanation), shadowOf(dialog).message.toString())
        assertNull(shadowOf(activity).nextStartedActivity)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun decliningNotificationAccessLeavesSystemSettingsClosed() {
        button(R.string.setup).performClick()
        button(R.string.notification_access).performClick()

        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test
    fun automaticSendingSettingsRequireAcceptingTheAccessExplanation() {
        button(R.string.setup).performClick()
        button(R.string.auto_send_settings).performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals(activity.getString(R.string.auto_send_explanation), shadowOf(dialog).message.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
    }

    @Test
    fun decliningAutomaticSendingAccessDoesNotOpenSettingsOrArmAnAction() {
        button(R.string.setup).performClick()
        button(R.string.auto_send_settings).performClick()

        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
    }

    @Test
    fun aLockedDeviceRejectsCommandsAndVoiceActivation() {
        val keyguard = shadowOf(activity.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        keyguard.setKeyguardLocked(true)

        submit("Lis mes derniers messages WhatsApp")
        assertEquals(activity.getString(R.string.locked), prompt.text.toString())

        button(R.string.talk).performClick()
        button(R.string.read_messages).performClick()

        assertEquals(activity.getString(R.string.locked), prompt.text.toString())
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
    }

    @Test
    fun pausingClearsPrivateInputAndAbandonsThePendingConversation() {
        submit("Envoie un WhatsApp à Camille")
        assertTrue(prompt.text.toString().contains("Je ne trouve pas ce contact"))
        input().setText("Un texte privé encore non validé")

        controller.pause().resume()

        assertEquals("", input().text.toString())
        assertEquals(activity.getString(R.string.welcome), prompt.text.toString())

        submit("Camille")

        assertTrue(prompt.text.toString().startsWith("Tu peux demander un WhatsApp"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun theMicrophonePermissionIsRequestedOnlyAfterPressingTalk() {
        assertNull(shadowOf(activity).lastRequestedPermission)

        button(R.string.talk).performClick()

        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull(request)
        assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions)
        assertEquals(activity.getString(R.string.mic_required), prompt.text.toString())
        val permissionDialog = shadowOf(activity).nextStartedActivity
        assertNotNull(permissionDialog)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permissionDialog.action)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
    }

    @Test
    fun changingTheContactAfterPreviewPreventsSendingWhatsApp() {
        val provider = installContacts()
        submit("Envoie un WhatsApp à Camille que je serai là demain")
        assertTrue(prompt.text.toString().contains("5678"))
        assertTrue(prompt.text.toString().contains("Tu confirmes l'envoi"))

        provider.phoneNumber = "+33687654321"
        submit("oui")
        awaitUi { prompt.text.toString().contains("Le contact a changé") }

        assertTrue(provider.queryCount.get() >= 2)
        assertEquals(activity.getString(R.string.changed_contact), prompt.text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
    }

    @Test
    fun pausingWhileTheContactIsRecheckedDiscardsTheSendRequest() {
        val provider = installContacts()
        submit("Envoie un WhatsApp à Camille que je serai là demain")
        assertTrue(prompt.text.toString().contains("Tu confirmes l'envoi"))
        val gate = QueryGate()
        provider.nextQueryGate = gate

        try {
            submit("oui")
            assertTrue("The contact recheck did not start", gate.entered.await(3, TimeUnit.SECONDS))
            controller.pause()
            provider.includeSecondContact = true
        } finally {
            gate.release.countDown()
        }
        controller.resume()
        awaitUi {
            provider.queryCount.get() >= 3 && views().filterIsInstance<TextView>()
                .any { it.text.toString().contains("2 numéros utilisables") }
        }

        assertEquals(activity.getString(R.string.welcome), prompt.text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
    }

    @Test
    fun confirmingWithoutAnAccessibilityServiceDoesNotOpenWhatsApp() {
        assertFalse(WhatsAppSendAccessibilityService.isConnected())
        val provider = installContacts()
        submit("Envoie un WhatsApp à Camille que je serai là demain")
        val confirmation = prompt.text.toString()
        assertTrue(confirmation.contains("Tu confirmes l'envoi"))

        submit("oui")
        awaitUi { provider.queryCount.get() >= 2 && button(R.string.talk).isEnabled }

        assertFalse(prompt.text.toString() == confirmation)
        assertFalse(prompt.text.toString().contains("Action Envoyer déclenchée"))
        assertFalse(WhatsAppSendAccessibilityService.hasPendingSend())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun installContacts(): MutableContactsProvider {
        val provider = MutableContactsProvider()
        provider.attachInfo(activity, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS)
        button(R.string.setup).performClick()
        button(R.string.grant_contacts).performClick()
        awaitUi {
            provider.queryCount.get() >= 1 && views().filterIsInstance<TextView>()
                .any { it.text.toString().contains("1 numéros utilisables") }
        }
        return provider
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("The expected UI state was not reached before the deadline", condition())
    }

    private fun submit(command: String) {
        val field = input()
        if (field.parent is View && (field.parent as View).visibility != View.VISIBLE) {
            button(R.string.write_command).performClick()
        }
        field.setText(command)
        button(R.string.submit).performClick()
    }

    private fun input(): EditText = views().filterIsInstance<EditText>().single()

    private fun button(label: Int): Button = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }

    private fun views(): Sequence<View> = descendants(activity.window.decorView)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private class QueryGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class MutableContactsProvider : ContentProvider() {
        @Volatile
        var phoneNumber = "+33612345678"

        @Volatile
        var nextQueryGate: QueryGate? = null

        @Volatile
        var includeSecondContact = false

        val queryCount = AtomicInteger()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            require(uri == Phone.CONTENT_URI)
            queryCount.incrementAndGet()
            val gate = nextQueryGate
            nextQueryGate = null
            if (gate != null) {
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS))
            }
            val columns = projection ?: arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.NORMALIZED_NUMBER)
            val values = mapOf(
                Phone.CONTACT_ID to "1",
                Phone.DISPLAY_NAME_PRIMARY to "Camille",
                Phone.NUMBER to phoneNumber,
                Phone.NORMALIZED_NUMBER to phoneNumber,
            )
            return MatrixCursor(columns).apply {
                addRow(columns.map { values[it] }.toTypedArray())
                if (includeSecondContact) {
                    val secondary = mapOf(
                        Phone.CONTACT_ID to "2",
                        Phone.DISPLAY_NAME_PRIMARY to "Alex",
                        Phone.NUMBER to "+33622334455",
                        Phone.NORMALIZED_NUMBER to "+33622334455",
                    )
                    addRow(columns.map { secondary[it] }.toTypedArray())
                }
            }
        }

        override fun getType(uri: Uri): String = Phone.CONTENT_TYPE

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
