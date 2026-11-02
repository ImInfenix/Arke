# Faisabilité du prototype

## État des connaissances

Les mécanismes ci-dessous sont documentés. Leur présence dans le code ne constitue pas une validation du Fairphone 5 ni de la version de WhatsApp qui y est installée.

| Fonction | Mécanisme retenu | Limite |
| --- | --- | --- |
| Reconnaissance | `SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12+ | Service et français hors ligne à vérifier sur le téléphone ; aucun repli distant |
| Synthèse | Voix française Android avec `isNetworkConnectionRequired=false` | Une voix locale doit être installée |
| Contacts | Fournisseur Contacts Android, autorisation explicite | Numéros internationaux utilisables uniquement ; homonymes soumis à choix |
| Nouveau WhatsApp | Click to Chat, conversation et texte préparés après confirmation | Envoi manuel ; aucun résultat de livraison disponible |
| Lecture | Notifications WhatsApp individuelles structurées, encore accessibles | Aucun historique intégral, ni groupes, ni notifications masquées |
| Réponse | `RemoteInput` de l'action de notification, après confirmation | Action à revalider ; transmission à WhatsApp seulement, livraison inconnue |
| Activation | Bouton, tuile de réglages rapides, Activity `ACTION_ASSIST` | Sélection de l'assistant et gestes dépendants du système |
| Verrouillage | Blocage du dialogue, de la lecture et des actions | Politique conservatrice du prototype, à arbitrer après essais |

Le dossier original réserve un arbitrage au propriétaire si seul un parcours partiellement manuel est possible. Le prototype rend ce compromis testable ; il ne remplace pas l'objectif d'envoi entièrement vocal par une fonctionnalité déclarée équivalente.

## Nouveau message : incertitude principale

La [documentation WhatsApp Click to Chat](https://faq.whatsapp.com/5913398998672934) décrit l'ouverture d'une conversation et le préremplissage d'un message. Elle ne démontre pas une transmission automatique depuis une application personnelle. La recherche documentaire n'a pas établi de mécanisme officiel général permettant cet envoi depuis le compte WhatsApp personnel ; cela ne prouve pas une impossibilité absolue.

Le prototype n'emploie ni automatisation d'interface par accessibilité, ni service cloud, ni API d'entreprise comme substitut au compte personnel. Il expose clairement la préparation et ne certifie jamais un envoi après l'ouverture de WhatsApp.

## Protection des échanges

Le microphone fonctionne par tours de parole et s'arrête pendant la synthèse. Une relecture doit se terminer avant la reprise vocale. Les callbacks périmés sont ignorés. Une reconnaissance accompagnée d'un score inférieur à 0,65 demande une répétition ; certains moteurs ne fournissent pas de score, et aucun seuil ne garantit à lui seul l'exactitude. Le destinataire et le contenu sont donc toujours relus avant confirmation.

Les confirmations, le contexte de réponse et les notifications ont une durée limitée. Un contact ou une notification modifiés invalident l'action. Une tentative de réponse au résultat incertain n'est pas répétée automatiquement. La protection contre répétition vit dans le processus, sans persistance des messages ; chaque nouvelle conversation nécessite un nouveau consentement.

Les notifications de plusieurs conversations partageant un nom affiché identique ne permettent pas de répondre. L'utilisateur doit préciser une conversation non ambiguë. Les textes incomplets sont signalés ; une lecture abrégée par le moteur ne crée pas de contexte de réponse.

L'application ne demande pas la permission Internet, ne conserve ni audio ni messages sur disque et ne journalise pas leur contenu. Les fournisseurs Android de voix ont leur propre processus ; les essais hors connexion restent indispensables. WhatsApp utilise naturellement son réseau pour la messagerie.

## Références Android

- [Reconnaissance locale et disponibilité](https://developer.android.com/reference/android/speech/SpeechRecognizer).
- [Caractéristiques d'une voix de synthèse](https://developer.android.com/reference/android/speech/tts/Voice).
- [Notifications actives accessibles](https://developer.android.com/reference/android/service/notification/NotificationListenerService).
- [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput) et [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent).
- [Authentification des actions](https://developer.android.com/reference/android/app/Notification.Action#isAuthenticationRequired()).
- [Qualification au rôle d'assistant](https://android.googlesource.com/platform/frameworks/support/+/7a44ebad596652c4b8f2548609e5af44d09fb175/core/core-role/src/main/java/androidx/core/role/RoleManagerCompat.java).
