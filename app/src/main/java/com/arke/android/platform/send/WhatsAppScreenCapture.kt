package com.arke.android.platform.send

import android.telephony.PhoneNumberUtils
import android.view.accessibility.AccessibilityNodeInfo
import com.arke.core.automation.Screen
import com.arke.core.automation.UiSnapshot
import java.util.ArrayDeque

internal data class CapturedScreen(
    val snapshot: UiSnapshot,
    val header: AccessibilityNodeInfo? = null,
    val send: AccessibilityNodeInfo? = null,
)

internal object WhatsAppScreenCapture {
    const val PACKAGE = "com.whatsapp"
    const val COMPOSER_ID = "$PACKAGE:id/entry"
    const val SEND_ID = "$PACKAGE:id/send"
    private const val HEADER_NAME_ID = "$PACKAGE:id/conversation_contact_name"
    private const val HEADER_CONTAINER_ID = "$PACKAGE:id/conversation_contact"
    private val phoneIds = setOf(
        "$PACKAGE:id/phone_number",
        "$PACKAGE:id/contact_info_phone_number",
    )
    private val profileMarkers = setOf(
        "$PACKAGE:id/contact_info",
        "$PACKAGE:id/contact_info_container",
        "$PACKAGE:id/contact_info_header",
        "$PACKAGE:id/contact_info_name",
    )

    fun read(root: AccessibilityNodeInfo?, activityClass: String? = null): CapturedScreen? {
        root ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != PACKAGE) return CapturedScreen(UiSnapshot(packageName, Screen.OTHER, root.windowId))
        val nodes = collect(root) ?: return CapturedScreen(UiSnapshot(PACKAGE, Screen.OTHER, root.windowId))
        val visible = nodes.filter { it.isVisibleToUser && it.packageName?.toString() == PACKAGE }
        val composers = visible.filter { it.viewIdResourceName == COMPOSER_ID }
        val sendNodes = visible.filter { it.viewIdResourceName == SEND_ID }
        val namedHeaders = visible.filter { it.viewIdResourceName == HEADER_NAME_ID }
        val titles = namedHeaders.ifEmpty {
            visible.filter { it.viewIdResourceName == HEADER_CONTAINER_ID && !it.text.isNullOrBlank() }
        }
        val titleNode = titles.singleOrNull()?.takeIf { it.text.toString().trim().length in 1..200 }
        val header = titleNode?.let { clickableHeader(it) }
            ?.takeIf { visible.count { node -> node.viewIdResourceName == HEADER_CONTAINER_ID } <= 1 }
        if (composers.size == 1) {
            val composer = composers.single()
            val editable = composer.isEditable && composer.isEnabled &&
                composer.className?.toString() == "android.widget.EditText"
            val send = sendNodes.singleOrNull()?.takeIf(::clickableSend)
            return CapturedScreen(
                UiSnapshot(
                    packageName = PACKAGE,
                    screen = Screen.CHAT,
                    windowId = root.windowId,
                    title = titleNode?.text?.toString()?.trim(),
                    composerText = composer.text?.toString().takeIf { editable },
                    hasUniqueHeader = header != null,
                    hasUniqueSendButton = send != null,
                ),
                header = header,
                send = send,
            )
        }
        val phoneFields = visible.filter { it.viewIdResourceName in phoneIds }
        val knownProfile = visible.any { it.viewIdResourceName in profileMarkers ||
            it.viewIdResourceName == "$PACKAGE:id/contact_info_phone_number" } ||
            activityClass in setOf("com.whatsapp.ContactInfo", "com.whatsapp.ContactInfoActivity")
        if (composers.isEmpty() && sendNodes.isEmpty() && phoneFields.isNotEmpty() && knownProfile) {
            val numbers = phoneFields.map { internationalNumber(it.text?.toString().orEmpty()) }
            val validNumbers = if (numbers.any { it == null }) emptySet() else numbers.filterNotNull().toSet()
            return CapturedScreen(UiSnapshot(PACKAGE, Screen.CONTACT_INFO, root.windowId, phoneNumbers = validNumbers))
        }
        return CapturedScreen(UiSnapshot(PACKAGE, Screen.OTHER, root.windowId))
    }

    fun clickableSend(node: AccessibilityNodeInfo): Boolean =
        node.packageName?.toString() == PACKAGE && node.viewIdResourceName == SEND_ID &&
            actionable(node) && node.className?.toString() in setOf("android.widget.ImageButton", "android.widget.Button")

    private fun clickableHeader(title: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = title
        repeat(4) {
            val current = node ?: return null
            if (current.packageName?.toString() != PACKAGE) return null
            if (current.viewIdResourceName in setOf(HEADER_NAME_ID, HEADER_CONTAINER_ID) && actionable(current)) return current
            node = current.parent
        }
        return null
    }

    private fun actionable(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun internationalNumber(text: String): String? {
        if (text.any { it !in '0'..'9' && !it.isWhitespace() && it !in "+-()." }) return null
        val compact = text.filterNot { it.isWhitespace() || it in "-()." }
        val explicit = when {
            compact.startsWith("+") -> compact
            compact.startsWith("00") -> "+${compact.drop(2)}"
            else -> return null
        }
        if (!explicit.matches(Regex("\\+[1-9][0-9]{6,14}"))) return null
        return PhoneNumberUtils.formatNumberToE164(explicit, "ZZ")
            ?.takeIf { it.matches(Regex("\\+[1-9][0-9]{6,14}")) }
    }

    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (nodes.size >= 600 || depth > 32 || node.childCount > 100) return null
            nodes += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it to depth + 1) }
            }
        }
        return nodes
    }
}
