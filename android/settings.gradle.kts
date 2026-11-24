pluginManagement {
    repositories {
        // Optional: set CENTRAL_MIRROR=1 where Maven Central rate-limits downloads.
        if (System.getenv("CENTRAL_MIRROR") != null) maven("https://maven-central.storage-download.googleapis.com/maven2")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional: set CENTRAL_MIRROR=1 where Maven Central rate-limits downloads.
        if (System.getenv("CENTRAL_MIRROR") != null) maven("https://maven-central.storage-download.googleapis.com/maven2")
        google()
        mavenCentral()
    }
}
rootProject.name = "VideoStudio"
include(":app")
