import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Reads a properties file in the project root if it exists. Both files below are gitignored. */
fun rootProperties(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Release signing key: path + passwords (the key itself lives in ~/.android, outside the project).
val keystoreProperties = rootProperties("keystore.properties")
// appToken: shared with the Worker's APP_TOKEN secret, so casual callers who find the URL are turned away.
val localProperties = rootProperties("local.properties")

android {
    namespace = "com.calorieapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calorieapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.5.0"

        val workerUrl = providers.gradleProperty("WORKER_URL").getOrElse("http://10.0.2.2:8787/")
        buildConfigField("String", "WORKER_URL", "\"$workerUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"${localProperties.getProperty("appToken", "")}\"")
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Exactly the release build (shrunk, release-signed) but installable next to the debug app,
        // with its own data, for checking a release before sending it to anyone.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            manifestPlaceholders["appLabel"] = "Calorie App (test)"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Camera
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Local storage (used from step 3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking (used from step 2)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Image display (handles EXIF rotation of captured JPEGs)
    implementation(libs.coil.compose)
    // Barcode scanning via Google Play services: its own scanner screen, no camera permission needed
    implementation(libs.play.services.code.scanner)

    // Settings (goals, energy unit, server URL)
    implementation(libs.androidx.datastore.preferences)

    // Reads JPEG orientation so the upload is shrunk and rotated correctly
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
}
