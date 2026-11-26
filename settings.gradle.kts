pluginManagement {
    repositories {
        google()
        // Miroir Google de Maven Central (évite les erreurs 429 de repo.maven.apache.org)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "UsbFormatter"
include(":app")
