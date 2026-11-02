package com.arke.android.platform

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.arke.core.Contact

object WhatsAppLauncher {
    fun prepare(context: Context, contact: Contact, text: String): String {
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) {
            return "Déverrouille le téléphone avant de préparer un message. Aucun message envoyé."
        }
        if (!contact.phone.matches(Regex("\\+[1-9][0-9]{6,14}"))) {
            return "Le numéro international de ce contact n'est pas utilisable. Aucun message envoyé."
        }
        if (text.isBlank() || text.length > 2000) {
            return "Le message doit contenir entre 1 et 2 000 caractères. Aucun message envoyé."
        }
        val address = Uri.Builder()
            .scheme("https")
            .authority("wa.me")
            .appendPath(contact.phone.drop(1))
            .appendQueryParameter("text", text)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, address).setPackage("com.whatsapp")
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Ouverture de WhatsApp demandée avec le texte préparé. Envoi manuel nécessaire ; aucun envoi confirmé."
        } catch (_: ActivityNotFoundException) {
            "WhatsApp n'est pas disponible pour préparer ce message. Aucun message envoyé."
        } catch (_: SecurityException) {
            "Android a refusé l'ouverture de WhatsApp. Aucun message envoyé."
        } catch (_: IllegalArgumentException) {
            "Impossible de préparer cette conversation WhatsApp. Aucun message envoyé."
        }
    }
}
