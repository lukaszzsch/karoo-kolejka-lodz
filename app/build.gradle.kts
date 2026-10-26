plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "pl.kolejka.karoo"
    compileSdk = 35

    defaultConfig {
        // Osobna aplikacja obok „Pociąg do Warszawy": inny applicationId i ID rozszerzenia.
        applicationId = "pl.kolejka.karoo.lodz"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-lodz"
        // Rozkład publikowany przez .github/workflows/timetable.yml na GitHub Pages (SPEC 1.2).
        buildConfigField("String", "MANIFEST_URL", "\"https://lukaszzsch.github.io/karoo-kolejka-lodz/manifest.json\"")
        // Tryb awaryjny (SPEC 6.10): kafelek liczy z bieżącej pozycji, trasa jest ignorowana.
        buildConfigField("boolean", "NEARBY_MODE", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Na własny użytek: podpis debug, dopóki faza 6 nie doda klucza release z sekretów CI.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "sqlite" // plik rozkładu kopiowany 1:1, rozmiar sprawdzalny przez openFd
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.karoo.ext)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
}
