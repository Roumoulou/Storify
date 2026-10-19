/*
════════════════════════════════════════════════════════════════════════════════
 STORIFY, UN SEUL PROJET GRADLE
════════════════════════════════════════════════════════════════════════════════

Une racine à un seul projet : la lib, ses deux variantes et ses tests (build.gradle.kts).
Le banc d'essai, Storibench, est une racine Gradle à part dans le classeur, qui inclut
celle-ci en build composite (`includeBuild("../Storify")`) et substitue fr.moulou:storify
par ses sources fraîches ; ce fichier n'en sait rien, et c'est voulu : un build inclus
garde ses propres dépôts et ses propres réglages.

Les dépôts vivent ici, pour les plugins du build comme pour les dépendances, et nulle part
dans build.gradle.kts (FAIL_ON_PROJECT_REPOS) : ce projet n'applique pas Loom, rien ne vient
donc masquer les dépôts de settings, le piège mesuré sur TravellingDimension ne jouant que
chez un consommateur. mavenCentral suffit : Kotlin, kotlinx, tomlkt, slf4j et JUnit y sont ;
Repsy reçoit la publication, il ne fournit rien à ce build.
════════════════════════════════════════════════════════════════════════════════
*/

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "Storify"
