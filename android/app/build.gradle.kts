plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cauce.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cauce.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        val syncBaseUrl = providers.gradleProperty("cauceSyncBaseUrl")
            .orElse("http://10.0.2.2:8080")
            .get()
        buildConfigField("String", "SYNC_BASE_URL", "\"$syncBaseUrl\"")

        val userId = providers.gradleProperty("cauceUserId").orElse("u1").get()
        buildConfigField("String", "USER_ID", "\"$userId\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(project(":core:events"))
    implementation(project(":core:ui"))
    implementation(project(":core:sync"))
    implementation(project(":feature:alarmas"))
    implementation(project(":feature:calendario_google"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
}
