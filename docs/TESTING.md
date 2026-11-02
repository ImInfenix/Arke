# Installer et essayer Arke

## Validation du prototype 0.1.0

Le 2 novembre 2026, la compilation de l'APK, les **58 tests du moteur**, les **22 tests Android simulés sous API 36** et l'analyse Android ont réussi. L'analyse ne relève aucune erreur ; elle signale seulement une version plus récente de Robolectric disponible. La signature de débogage de l'APK a été vérifiée.

Aucun essai sur Fairphone ni dans le véritable WhatsApp n'a encore été réalisé. Les tests Android utilisent Robolectric et des contacts/notifications fictifs ; ils ne constituent pas des tests d'envoi réel.

## Préparer le Fairphone

1. Compiler avec `./scripts/build.ps1`, puis transférer `app/build/outputs/apk/debug/app-debug.apk` sur le Fairphone et ouvrir le fichier pour installer ce prototype de débogage.
2. Ouvrir Arke. Dans **Configurer Arke**, autoriser les contacts si le parcours de préparation est souhaité.
3. Installer une voix française de synthèse locale et les ressources de reconnaissance françaises hors ligne dans les paramètres vocaux Android. Le diagnostic distingue la présence du service local de la disponibilité réelle du français, vérifiée lors de l'écoute.
4. Appuyer sur **Parler**, puis autoriser le microphone. Le parcours **Écrire une demande** permet les essais sans microphone.
5. Pour lire et répondre, ouvrir **Accès aux notifications**, prendre connaissance de l'explication et activer Arke dans Android. Cet accès est facultatif pour préparer un nouveau message.

Les permissions peuvent être révoquées dans Android à tout moment. Le diagnostic se met à jour au retour dans l'application.

Le prototype ne propose pas de contacts ou de messages factices dans le parcours réel. Choisir un contact de test consentant pour toute transmission, et effectuer les essais à l'arrêt.

## Dialogues à vérifier

| Essai | Attente |
| --- | --- |
| « Envoie un WhatsApp à Camille » puis dictée | Bon contact, relecture complète et confirmation de préparation ; envoi manuel annoncé |
| « Envoie à Camille que j'arrive dans dix minutes » | Texte conservé et relu sans seconde dictée |
| Deux Alex, ou deux numéros pour un nom | Choix explicite, aucun choix silencieux |
| Texte « N'oublie pas d'envoyer le dossier » | Le mot envoyer reste du contenu |
| « Modifie le message » ou « Non, remplace demain par vendredi » | Nouveau texte relu, aucune action sur l'ancien |
| « Annule », « Non », retour à l'accueil ou mise en arrière-plan | Aucun nouvel envoi ; dialogue et brouillon abandonnés |
| Silence ou confirmation tardive | Dialogue expiré, nouvelle demande nécessaire |
| « Lis mes derniers messages WhatsApp » | Uniquement notifications accessibles, provenance et limites annoncées |
| « Lis le dernier WhatsApp de Camille », puis « Réponds que j'arrive » | Réponse possible seulement pour une notification individuelle compatible, relue et confirmée |
| Notification retirée ou remplacée avant confirmation | Réponse refusée ; aucune répétition automatique |
| WhatsApp hors réseau | Une demande transmise n'est pas présentée comme livrée |
| Téléphone verrouillé | Aucune lecture de message ni action ; déverrouillage demandé |
| Mode avion, ressources françaises installées | Reconnaissance et synthèse locales à mesurer ; messagerie hors réseau annoncée sans faux succès |

Les groupes, WhatsApp Business et l'historique complet sont exclus du prototype. Des notifications regroupées ou dépourvues de contenu structuré peuvent être ignorées. « Aucun message accessible » ne signifie pas « aucun message reçu ».

## Activation

Le bouton **Parler** est le premier déclencheur. Une tuile **Arke** peut être ajoutée aux réglages rapides Android. Le choix d'Arke comme assistant dans les paramètres système peut permettre le geste d'assistant du téléphone ; ce fonctionnement nécessite un essai réel. Aucun mot d'activation permanent n'est fourni.

## Relevés sur appareil

Conserver les résultats privés et mesures dans `.local/`, jamais les messages ni contacts dans Git. Pour chaque essai, relever : version Android, version WhatsApp, service vocal, état de verrouillage, réseau, résultat observé et délai. Mesurer séparément la consommation au repos et pendant les dialogues. Aucun seuil batterie ou latence n'est encore validé.

La compilation, les tests du moteur et les tests Android Robolectric vérifient la logique, les permissions, les frontières de verrouillage, le cycle de vie et les notifications simulées. Ils ne valident ni la reconnaissance sur le Fairphone, ni la disponibilité des actions du véritable WhatsApp, ni l'usage mains libres.
