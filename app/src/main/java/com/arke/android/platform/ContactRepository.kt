package com.arke.android.platform

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.arke.core.Contact
import java.util.Locale

class ContactRepository(private val context: Context) {
    fun load(): List<Contact> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) {
            return emptyList()
        }
        val region = countryCode()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.NORMALIZED_NUMBER)
        val contacts = mutableListOf<Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            var inspected = 0
            while (cursor.moveToNext() && inspected++ < 3000) {
                val name = cursor.getString(1)?.trim().orEmpty()
                val rawNumber = cursor.getString(2).orEmpty()
                val international = internationalNumber(rawNumber, cursor.getString(3), region) ?: continue
                if (name.isEmpty() || name.length > 200) continue
                contacts += Contact("${cursor.getString(0)}:$international", name, international)
            }
        }
        return contacts.distinctBy { it.name.lowercase(Locale.ROOT) to it.phone }
    }

    private fun countryCode(): String? {
        val simCountry = try {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso
        } catch (_: SecurityException) {
            null
        }
        return simCountry?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase(Locale.ROOT)
            ?: context.resources.configuration.locales.get(0)?.country
                ?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase(Locale.ROOT)
    }

    private fun internationalNumber(rawNumber: String, savedNumber: String?, country: String?): String? {
        if (rawNumber.any { it !in '0'..'9' && !it.isWhitespace() && it !in "+-()." }) return null
        val compact = rawNumber.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == '.' }
        if (!compact.matches(Regex("\\+?[0-9]+"))) return null
        val explicit = when {
            compact.startsWith("+") -> compact
            compact.startsWith("00") -> "+${compact.drop(2)}"
            else -> null
        }
        val formatted = when {
            explicit != null -> PhoneNumberUtils.formatNumberToE164(explicit, country ?: "ZZ")
            savedNumber?.matches(Regex("\\+[1-9][0-9]{6,14}")) == true ->
                PhoneNumberUtils.formatNumberToE164(savedNumber, "ZZ")
            country != null -> PhoneNumberUtils.formatNumberToE164(compact, country)
            else -> null
        }
        return formatted?.takeIf { it.matches(Regex("\\+[1-9][0-9]{6,14}")) }
    }
}
