plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nimbo"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.nimbo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended")
    // Wavy oficial vive en M3 1.5 beta (no en 1.4): pin sobre el BOM.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Nube (spec 07): TDLib MTProto + secrets cifrados.
    implementation("com.github.tdlibx:td:1.8.56")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Player real (spec 07d): Media3 streaming sobre rangos TDLib.
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
