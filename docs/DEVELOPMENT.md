# Environnement de développement

## Préparation sous Windows

Installer la version stable d'[Android Studio](https://developer.android.com/studio/install), puis terminer son assistant de configuration.

Dans **More Actions > SDK Manager**, ou **Tools > SDK Manager** si un projet est ouvert, installer :

| Onglet | Composant |
| --- | --- |
| SDK Platforms | Android SDK Platform 36, API 36 |
| SDK Tools | Android SDK Build-Tools 36.0.0 |
| SDK Tools | Android SDK Platform-Tools, version stable proposée |
| SDK Tools | Android SDK Command-line Tools, latest |

Activer **Show Package Details** si nécessaire pour choisir les versions. Les licences doivent être acceptées pendant l'installation. Le gestionnaire est décrit dans la [documentation Android](https://developer.android.com/studio/intro/update#sdk-manager).

Le JDK intégré à Android Studio permet d'éviter une installation Java séparée. Un JDK existant compatible pourra également être utilisé. Les versions du plugin Android, de Gradle et du JDK seront fixées ensemble lors de la création du prototype, selon la [table de compatibilité Android](https://developer.android.com/build/releases/about-agp). L'installation globale de Gradle n'est pas nécessaire : le projet utilisera un wrapper versionné.

La plateforme de compilation API 36 ne constitue pas la version Android minimale de l'application. Celle-ci sera choisie après vérification du téléphone cible.

## Téléphone de test

Pour les essais de faisabilité, prévoir le Fairphone 5 avec WhatsApp configuré, un câble USB de données et le débogage USB activé. Autoriser l'ordinateur à la connexion. Les tests de reconnaissance locale nécessiteront des ressources vocales françaises hors ligne disponibles sur le téléphone.

Un émulateur pourra compléter les essais d'interface ; la validation du parcours WhatsApp, du verrouillage et de la batterie nécessitera l'appareil réel. Aucun envoi réel ne doit être lancé sans confirmation explicite pour le message concerné.

## Fichiers locaux

Conserver les SDK, caches, captures privées, contacts de test et documents de cadrage hors des sources versionnées. Dans le dépôt, `.local/` et `.tools/` sont réservés aux ressources locales ignorées. `local.properties`, les clés de signature et les sorties de compilation sont également ignorés.

L'agent ne doit pas installer ni modifier d'outils hors du dossier du projet sans autorisation explicite. Si une installation utilisateur est nécessaire, interrompre l'implémentation jusqu'à confirmation de sa disponibilité.

## Reprise

Après installation, vérifier la présence des composants SDK et l'exécution du JDK. Fixer ensuite les versions de compilation, créer le prototype Android et son wrapper, puis exécuter compilation, tests pertinents et analyse Android avant les essais sur appareil.
