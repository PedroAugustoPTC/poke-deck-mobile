plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão vem da tag da release (-PversionName=1.2.3). Build local = 0.0.0, que nunca se atualiza sozinho.
val appVersion = (findProperty("versionName") as String?) ?: "0.0.0"
val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)

android {
    namespace = "io.github.pedroaugustoptc.pokedeck"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pedroaugustoptc.pokedeck"
        minSdk = 26
        // shortcut: 35 mantém o onBackPressed e a trava de retrato em tablets; subir para 36 exige OnBackInvokedCallback.
        targetSdk = 35
        versionCode = maxOf(1, versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]) // 0.0.0 local = 1
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            // O CI decodifica o keystore dos secrets; sempre a MESMA chave, senão o Android recusa a atualização.
            val file = System.getenv("KEYSTORE_FILE")
            if (file != null) {
                storeFile = file(file)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // o org.json do android.jar não funciona em teste local
}
