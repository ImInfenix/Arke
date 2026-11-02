# Arke

Assistant vocal personnel en français pour le Fairphone 5 sous Android Fairphone.

Arke vise un échange vocal permettant de choisir un contact, dicter un WhatsApp, écouter la relecture et confirmer explicitement l'action. Le traitement vocal doit privilégier une exécution locale et une faible consommation.

## État du projet

Initialisation du dépôt et cadrage. Aucune application ni aucun APK ne sont encore disponibles. La prochaine étape est un prototype de faisabilité sur le téléphone cible, après préparation de l'environnement Android.

L'envoi d'un nouveau WhatsApp entièrement à la voix, l'activation écran éteint et le fonctionnement au verrouillage restent à démontrer. Une conversation ouverte avec un texte prérempli ne constitue pas un envoi réussi.

## Principes

- Confirmation explicite du destinataire et du contenu avant toute transmission.
- Possibilité de corriger ou d'annuler avant l'action.
- Lecture des messages uniquement sur demande.
- Aucun modèle de langage distant obligatoire.
- Aucune écoute permanente retenue sans mesure de consommation.
- Statuts distincts pour préparation, demande de transmission, résultat confirmé et résultat inconnu.

## Développement

- [Préparer l'environnement](docs/DEVELOPMENT.md).
- [Jalons et critères de validation](docs/ROADMAP.md).

Le dossier de cadrage original est conservé localement dans `.local/briefing/`. Ce dossier et son archive sont exclus de Git. Les fichiers versionnés décrivent uniquement le projet et son développement.
