package com.arke.android.platform

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowNotificationListenerService
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppNotificationServiceTest {
    private lateinit var application: Application
    private lateinit var controller: ServiceController<WhatsAppNotificationService>
    private lateinit var service: WhatsAppNotificationService
    private lateinit var notifications: ShadowNotificationListenerService
    private lateinit var keyguard: ShadowKeyguardManager
    private lateinit var localUser: Person
    private lateinit var sender: Person
    private var notificationId = 0

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        keyguard = shadowOf(application.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(false)
        notificationId = UUID.randomUUID().hashCode() and Int.MAX_VALUE
        localUser = Person.Builder().setName("Moi").setKey("self").build()
        sender = Person.Builder().setName("Camille").setKey("camille").setUri("tel:+33612345678").build()
        controller = Robolectric.buildService(WhatsAppNotificationService::class.java).create()
        service = controller.get()
        notifications = Shadow.extract(service)
        service.onListenerConnected()
        shadowOf(application).clearBroadcastIntents()
    }

    @After
    fun tearDown() {
        service.onListenerDisconnected()
        controller.destroy()
    }

    @Test
    fun disconnectedServiceExposesNoMessages() {
        post(messageNotification())
        assertEquals(1, WhatsAppNotificationService.snapshot(application).size)

        service.onListenerDisconnected()

        assertFalse(WhatsAppNotificationService.isConnected())
        assertTrue(WhatsAppNotificationService.snapshot(application).isEmpty())
        assertTrue(WhatsAppNotificationService.reply(application, "missing", "Test").contains("indisponible"))
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun lockedDeviceDoesNotExposeOrReplyToPreviouslyVisibleMessages() {
        post(messageNotification())
        val message = WhatsAppNotificationService.snapshot(application).single()

        keyguard.setIsDeviceLocked(true)

        assertTrue(WhatsAppNotificationService.snapshot(application).isEmpty())
        assertTrue(WhatsAppNotificationService.reply(application, message.id, "Test").contains("Déverrouille"))
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun structuredMessageRemainsPassiveUntilAnExplicitSingleReplyAttempt() {
        post(messageNotification(body = "Tu arrives quand ?"))

        val message = WhatsAppNotificationService.snapshot(application).single()

        assertEquals("Camille", message.sender)
        assertEquals("Tu arrives quand ?", message.text)
        assertTrue(message.canReply)
        assertTrue(replyBroadcasts().isEmpty())

        val result = WhatsAppNotificationService.reply(application, message.id, "Dans dix minutes.")

        assertTrue(result.contains("Livraison non confirmée"))
        val deliveredIntent = replyBroadcasts().single()
        assertEquals("Dans dix minutes.", RemoteInput.getResultsFromIntent(deliveredIntent).getCharSequence(REPLY_KEY))
        assertFalse(WhatsAppNotificationService.snapshot(application).single().canReply)

        val repeated = WhatsAppNotificationService.reply(application, message.id, "Dans dix minutes.")

        assertTrue(repeated.contains("déjà été tentée"))
        assertEquals(1, replyBroadcasts().size)
    }

    @Test
    fun groupsSummariesOtherPackagesAndUnstructuredNotificationsAreExcluded() {
        post(messageNotification(group = true))
        post(messageNotification(summary = true))
        post(messageNotification(), packageName = "com.other.messenger")
        post(Notification.Builder(application, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Camille")
            .setContentText("Texte sans identité structurée")
            .build())

        assertTrue(WhatsAppNotificationService.snapshot(application).isEmpty())
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun staleMessagesAndOutgoingMessagesAreExcluded() {
        val staleTime = System.currentTimeMillis() - 3 * 60 * 60 * 1000L
        post(messageNotification(timestamp = staleTime), postedAt = staleTime)
        post(messageNotification(timestamp = staleTime))
        post(messageNotification(messageSender = localUser))

        assertTrue(WhatsAppNotificationService.snapshot(application).isEmpty())
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun maskedMessageContentIsNeverPresentedAsReadableText() {
        post(messageNotification(body = "Contenu masqué"))
        post(messageNotification(body = "Sensitive notification content hidden"))

        assertTrue(WhatsAppNotificationService.snapshot(application).isEmpty())
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun distinctConversationsWithTheSameSenderNameCannotBeRepliedTo() {
        post(messageNotification())
        val otherSender = Person.Builder().setName("Camille").setKey("other-camille")
            .setUri("tel:+33687654321").build()
        post(messageNotification(messageSender = otherSender))

        val messages = WhatsAppNotificationService.snapshot(application)

        assertEquals(2, messages.size)
        assertTrue(messages.all { it.sender == "Camille" && !it.canReply })
        assertTrue(WhatsAppNotificationService.reply(application, messages.first().id, "Test").contains("non ambiguë"))
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun removedOrChangedMessagesRejectAnEarlierReplyTarget() {
        val threadId = notificationId++
        val key = post(messageNotification(body = "Première version"), id = threadId, tag = "changing-thread")
        val oldMessage = WhatsAppNotificationService.snapshot(application).single()
        service.cancelNotification(key)

        assertTrue(WhatsAppNotificationService.reply(application, oldMessage.id, "Test").contains("n'est plus accessible"))

        val replacedKey = post(messageNotification(body = "Nouvelle version"), id = threadId, tag = "changing-thread")

        assertEquals(key, replacedKey)
        assertFalse(oldMessage.id == WhatsAppNotificationService.snapshot(application).single().id)

        val result = WhatsAppNotificationService.reply(application, oldMessage.id, "Test")

        assertTrue(result.contains("n'est plus accessible ou a changé"))
        assertTrue(WhatsAppNotificationService.reply(application, "missing", "Test").contains("n'est plus accessible"))
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun foreignImmutableAndAmbiguousActionsCannotEnableReply() {
        val foreignAction = replyAction(creatorPackage = "com.other.app")
        post(messageNotification(messageSender = Person.Builder().setName("Alice").setKey("alice").build(),
            actions = listOf(foreignAction)))
        post(messageNotification(messageSender = Person.Builder().setName("Bob").setKey("bob").build(),
            actions = listOf(replyAction(mutable = false))))
        post(messageNotification(messageSender = Person.Builder().setName("Charlie").setKey("charlie").build(),
            actions = listOf(replyAction(), replyAction())))

        val messages = WhatsAppNotificationService.snapshot(application)

        assertEquals(3, messages.size)
        assertTrue(messages.all { !it.canReply })
        messages.forEach { message ->
            assertTrue(WhatsAppNotificationService.reply(application, message.id, "Test").contains("Aucune réponse transmise"))
        }
        assertTrue(replyBroadcasts().isEmpty())
    }

    @Test
    fun canceledReplyActionIsAttemptedOnceAndNeverReportedAsDelivered() {
        val action = replyAction()
        post(messageNotification(actions = listOf(action)))
        val message = WhatsAppNotificationService.snapshot(application).single()
        action.actionIntent.cancel()

        val result = WhatsAppNotificationService.reply(application, message.id, "Test")
        val repeated = WhatsAppNotificationService.reply(application, message.id, "Test")

        assertTrue(result.contains("refusé"))
        assertTrue(result.contains("Aucun nouvel essai automatique"))
        assertTrue(repeated.contains("déjà été tentée"))
        assertFalse(WhatsAppNotificationService.snapshot(application).single().canReply)
        assertTrue(replyBroadcasts().isEmpty())
    }

    private fun messageNotification(
        body: String = "Bonjour",
        messageSender: Person = sender,
        timestamp: Long = System.currentTimeMillis(),
        group: Boolean = false,
        summary: Boolean = false,
        actions: List<Notification.Action> = listOf(replyAction())
    ): Notification {
        val style = Notification.MessagingStyle(localUser)
            .setGroupConversation(group)
            .addMessage(body, timestamp, messageSender)
        val builder = Notification.Builder(application, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (group) style.setConversationTitle("Groupe de test")
        if (summary) builder.setGroup("summary").setGroupSummary(true)
        actions.forEach(builder::addAction)
        return builder.build()
    }

    private fun replyAction(creatorPackage: String = WHATSAPP, mutable: Boolean = true): Notification.Action {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val intent = PendingIntent.getBroadcast(application, notificationId++,
            Intent(REPLY_ACTION).setPackage(application.packageName), flags)
        shadowOf(intent).setCreatorPackage(creatorPackage)
        return Notification.Action.Builder(null, "Répondre", intent)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setAllowFreeFormInput(true).build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun post(
        notification: Notification,
        packageName: String = WHATSAPP,
        postedAt: Long = System.currentTimeMillis(),
        id: Int = notificationId++,
        tag: String = "thread-${UUID.randomUUID()}"
    ): String {
        val posted = StatusBarNotification(packageName, packageName, id, tag,
            Process.myUid(), Process.myPid(), 0, notification, Process.myUserHandle(), postedAt)
        return notifications.addActiveNotification(posted)
    }

    private fun replyBroadcasts(): List<Intent> =
        shadowOf(application).broadcastIntents.filter { it.action == REPLY_ACTION }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val CHANNEL = "test-messages"
        const val REPLY_ACTION = "com.arke.android.TEST_REPLY"
        const val REPLY_KEY = "reply_text"
    }
}
