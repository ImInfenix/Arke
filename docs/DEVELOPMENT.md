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

La chaîne du prototype est fixée à **AGP 8.11.1, Gradle 8.13, Kotlin 2.2.21 et JDK 21**, avec du bytecode Java 17. Ces versions sont compatibles selon les tables [Android](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) et [Gradle](https://docs.gradle.org/current/userguide/compatibility.html).

Attention : le **JDK 25** fourni par l'Android Studio installé ne permet pas d'exécuter Gradle 8.13. Le JDK 21 déjà présent avec Rider convient, sans installation supplémentaire. L'installation globale de Gradle n'est pas utilisée : le wrapper versionné télécharge la distribution, dont l'empreinte SHA-256 est fixée.

La plateforme de compilation API 36 ne constitue pas la version Android minimale de l'application. Celle-ci sera choisie après vérification du téléphone cible.

## Téléphone de test

Pour les essais de faisabilité, prévoir le Fairphone 5 avec WhatsApp configuré, un câble USB de données et le débogage USB activé. Autoriser l'ordinateur à la connexion. Les tests de reconnaissance locale nécessiteront des ressources vocales françaises hors ligne disponibles sur le téléphone.

Un émulateur pourra compléter les essais d'interface ; la validation du parcours WhatsApp, du verrouillage et de la batterie nécessitera l'appareil réel. Aucun envoi réel ne doit être lancé sans confirmation explicite pour le message concerné.

## Fichiers locaux

Conserver les SDK, caches, captures privées, contacts de test et documents de cadrage hors des sources versionnées. Dans le dépôt, `.local/` et `.tools/` sont réservés aux ressources locales ignorées. `local.properties`, les clés de signature et les sorties de compilation sont également ignorés.

L'agent ne doit pas installer ni modifier d'outils hors du dossier du projet sans autorisation explicite. Si une installation utilisateur est nécessaire, interrompre l'implémentation jusqu'à confirmation de sa disponibilité.

## Compiler sous PowerShell

Depuis la racine du dépôt :

```powershell
./scripts/build.ps1
```

Le script recherche un JDK compatible dans `JAVA_HOME`, puis le JDK de Rider. Il vérifie les composants SDK et redirige les caches, fichiers temporaires et préférences Java/Android sous `.local/`. Il restaure ensuite les variables d'environnement du processus appelant. Aucun SDK manquant n'est téléchargé automatiquement.

Pour préciser les installations :

```powershell
./scripts/build.ps1 -JdkPath 'C:\Path\To\jdk-21' -SdkPath 'C:\Path\To\Android\Sdk'
```

Les tâches par défaut sont `:core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug` et `:app:lintDebug`. Pour une tâche particulière :

```powershell
./scripts/build.ps1 -Tasks ':core:test'
```

L'APK est produit dans `app/build/outputs/apk/debug/app-debug.apk`. Les rapports de tests sont sous `core/build/reports/tests/test/` et `app/build/reports/tests/testDebugUnitTest/` ; celui de l'analyse est sous `app/build/reports/lint-results-debug.html`.

Une clé de débogage locale est créée dans `.local/debug.keystore`, avec les identifiants Android de débogage usuels. Elle sert uniquement aux essais ; une distribution de production nécessiterait une signature distincte.

## Android Studio

Ouvrir la racine du dépôt, puis choisir le **JDK 21** dans **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK**. Pour conserver les écritures de l'agent dans le projet, celui-ci utilise le script PowerShell. Une utilisation directe de l'IDE peut employer les caches utilisateur habituels d'Android Studio.

Le SDK peut émettre un avertissement sur sa version XML ; la mise à jour des Command-line Tools reste recommandée. L'ancien `sdkmanager` n'est pas utilisé pour compiler le prototype.
