# Arke

Assistant vocal personnel en français pour le Fairphone 5 sous Android Fairphone.

Arke vise un échange vocal permettant de choisir un contact, dicter un WhatsApp, écouter la relecture et confirmer explicitement l'action. Le traitement vocal doit privilégier une exécution locale et une faible consommation.

## État du projet

Un prototype Android est disponible dans les sources. Il propose un dialogue vocal local, un parcours écrit, la sélection des contacts, la correction et la confirmation des brouillons, ainsi que la lecture et la réponse aux notifications WhatsApp compatibles.

Après relecture et confirmation explicite, Arke peut ouvrir WhatsApp, vérifier le numéro dans la fiche du contact, revenir au texte confirmé et appuyer une seule fois sur Envoyer. Le service **Arke · envoi WhatsApp confirmé** doit être activé dans les paramètres d'accessibilité. Une interface inconnue, un numéro différent ou un texte modifié arrêtent l'opération. La livraison reste non confirmée.

Le dialogue est bloqué au verrouillage. L'activation par geste d'assistant et la qualité des voix doivent encore être vérifiées sur le Fairphone. Le jalon d'usage mains libres en voiture n'est pas atteint.

## Principes

- Confirmation explicite du destinataire et du contenu avant toute transmission.
- Possibilité de corriger ou d'annuler avant l'action.
- Lecture des messages uniquement sur demande.
- Aucun modèle de langage distant obligatoire.
- Aucune écoute permanente retenue sans mesure de consommation.
- Statuts distincts pour préparation, demande de transmission, résultat confirmé et résultat inconnu.

## Développement

- Sous PowerShell : `./scripts/build.ps1` lance les tests, produit l'APK de débogage et exécute l'analyse Android.
- APK local : `app/build/outputs/apk/debug/app-debug.apk` ; Android 12 ou ultérieur.
- [Environnement et compilation](docs/DEVELOPMENT.md).
- [Installation et premiers essais](docs/TESTING.md).
- [Faisabilité et limites](docs/FEASIBILITY.md).
- [Jalons et critères de validation](docs/ROADMAP.md).

Le dossier de cadrage original est conservé localement dans `.local/briefing/`. Ce dossier et son archive sont exclus de Git. Les fichiers versionnés décrivent uniquement le projet et son développement.
