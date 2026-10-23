plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cauce.feature.alarmas"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    }

    // Pure projections live here: no Android imports, so :feature:alarmas:test runs on the JVM.
    sourceSets.getByName("main").java.srcDir("src/domain/kotlin")
}

dependencies {
    implementation(project(":core:events"))
    implementation(project(":core:ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
