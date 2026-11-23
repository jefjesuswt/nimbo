import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    // Wavy: el material3 estable de CMP (1.9.0) no lo trae.
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation(platform("it.tdlight:tdlight-java-bom:3.5.9+td.1.8.68"))
    implementation("it.tdlight:tdlight-java")
    implementation(group = "it.tdlight", name = "tdlight-natives", classifier = "linux_amd64_gnu_ssl3")
    implementation("com.google.zxing:core:3.5.3")
}

compose.desktop {
    application {
        mainClass = "spike.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "nimbo-spike"
            packageVersion = "0.0.1"
        }
    }
}
