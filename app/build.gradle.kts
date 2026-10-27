plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nimbo"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.nimbo"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        // TDLib trae .so x4 ABIs: solo arm64 (celu). Emulador x86_64 → añadir aquí.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Firma release: clave y contraseña en ~/.gradle/gradle.properties
    // (NIMBO_KEYSTORE, NIMBO_KEYSTORE_PASSWORD), nunca en el repo.
    // Sin ellas, el release sale sin firmar.
    val ks = providers.gradleProperty("NIMBO_KEYSTORE").orNull
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = providers.gradleProperty("NIMBO_KEYSTORE_PASSWORD").get()
            keyAlias = "nimbo"
            keyPassword = storePassword
        }
    }
    // public = la de GitHub Releases (solo traducción en el teléfono);
    // personal = añade el traductor web de Google. Mismo applicationId.
    flavorDimensions += "dist"
    productFlavors {
        create("public") {
            dimension = "dist"
            isDefault = true
        }
        create("personal") {
            dimension = "dist"
            versionNameSuffix = "-personal"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Log y demás de android.jar devuelven valores por defecto en tests JVM.
    testOptions.unitTests.isReturnDefaultValues = true
    buildFeatures {
        compose = true
        // VERSION_NAME para SetTdlibParameters.
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Rutas tipadas (@Serializable) entre pestañas y visores (ADR 0007).
    implementation(libs.navigation.compose)
    implementation(libs.serialization.json)
    // DI (ADR 0007).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.documentfile)
    // Traducción en el teléfono (lector): modelos por idioma bajo demanda.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.langid)
    // Nube (spec 07): TDLib MTProto + secrets cifrados.
    implementation(libs.tdlib)
    implementation(libs.security.crypto)
    implementation(libs.coroutines.android)
    // Player real (spec 07d): Media3 streaming sobre rangos TDLib.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
