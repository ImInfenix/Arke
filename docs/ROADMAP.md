# Jalons de développement

Tous les jalons fonctionnels ci-dessous restent à valider. Aucun tag de livraison ne sera créé avant l'obtention d'un résultat testable.

Le prototype `0.2.0-prototype` ajoute l'envoi automatique après confirmation via un service d'accessibilité. Il vérifie le numéro et le texte avant un unique clic ; les interfaces inconnues sont refusées. La réception sur Fairphone, les gestes d'assistant et la fiabilité du véritable WhatsApp restent à valider ; le dialogue reste bloqué au verrouillage.

| Jalon | Résultat attendu | Validation |
| --- | --- | --- |
| 0 — Faisabilité | Conversation française locale, sélection d'un contact et exploration des possibilités WhatsApp | Essais distincts déverrouillé, écran éteint et verrouillé ; limites et mesures consignées |
| 1 — Premier envoi | Demande, dictée, relecture, confirmation et retour sincère sur la transmission | Parcours répété sur le Fairphone 5 avec le bon destinataire, sans envoi accidentel |
| 2 — Robustesse | Annulation, correction, homonymes et demande contenant déjà le texte | Aucun choix arbitraire de contact, aucun doublon, aucun mot dicté interprété comme confirmation |
| 3 — Lecture et réponse | Lecture explicite des messages accessibles et réponse au bon interlocuteur | Aucune annonce spontanée, confirmation avant réponse et limites d'historique annoncées |
| 4 — Activation quotidienne | Invocation hors de l'écran principal, étude du verrouillage et de l'écran éteint | Mode choisi après essais de confidentialité, de latence et de consommation |
| 5 — Formulations naturelles | Compréhension plus souple sans modification du sens dicté | Gain mesuré ; modèle local seulement si son apport justifie son coût |
| 6 — Extensions | Nouvelles capacités choisies selon l'usage | Besoin concret, faisabilité et validation propres à chaque action |

## Première étude à mener

1. Vérifier la version Android du Fairphone et les services français de reconnaissance et de synthèse disponibles localement. L'API Android de reconnaissance explicitement locale existe depuis l'API 31, mais sa présence doit être vérifiée sur l'appareil : [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer#createOnDeviceSpeechRecognizer(android.content.Context)).
2. Vérifier l'accès aux contacts après autorisation et représenter les contacts ambigus sans sélection implicite.
3. Étudier séparément préparation d'un nouveau WhatsApp, transmission effective et réponse à une notification. Ne pas extrapoler la possibilité d'une opération à une autre.
4. Définir une machine d'états distinguant dictée, relecture, confirmation et action engagée. Interdire toute répétition automatique d'une transmission au résultat inconnu.
5. Mesurer les temps de réponse et la consommation sans fixer de seuil arbitraire avant les premiers essais.

## Décisions réservées au propriétaire

Si seul un parcours partiellement manuel est possible, présenter le prototype et sa limite puis demander si ce compromis est acceptable avant de modifier l'expérience cible. Les règles d'usage au verrouillage et une éventuelle écoute permanente demandent également un arbitrage sur la base des essais.

L'usage en voiture ne pourra être déclaré validé qu'après démonstration d'une activation et d'une exécution sans manipulation de l'écran. Les premiers essais seront réalisés à l'arrêt.
