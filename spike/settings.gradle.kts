// Spike 16.0 (spec 16): proyecto aparte, no entra en main.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // Solo en el spike: desde 16.1 va un espejo propio con SHA-256.
        exclusiveContent {
            forRepository { maven("https://mvn.mchv.eu/repository/mchv/") }
            filter { includeGroup("it.tdlight") }
        }
    }
}
rootProject.name = "nimbo-spike"
