@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Bloc de gestion centralisée des dépendances (recommandé pour projets multi-modules aussi)
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)

    versionCatalogs {}
}

rootProject.name = "Storify"