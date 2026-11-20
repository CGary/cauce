plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val cauceApiToken: String = providers.gradleProperty("cauceApiToken").orElse("").get().trim()

// Fails the APK build (not configuration), so `:core:*:test` and `:feature:*:test` keep working
// on machines without the token. The message never prints the value.
val checkCauceApiToken by tasks.registering {
    val token = cauceApiToken
    doLast {
        if (token.isEmpty()) {
            throw GradleException(
                "Falta cauceApiToken: defínelo en ~/.gradle/gradle.properties (cauceApiToken=<el mismo valor que " +
                    "CAUCE_API_TOKEN del servidor>) o exporta CAUCE_API_TOKEN y usa `just apk`. Ver D20 en docs/DECISIONS.md.",
            )
        }
        if (token.length < 32) {
            throw GradleException("cauceApiToken es demasiado corto: el servidor exige al menos 32 caracteres (D20).")
        }
        if (token.any { it == '"' || it == '\\' || it.isWhitespace() }) {
            throw GradleException("cauceApiToken no puede tener comillas, barras invertidas ni espacios.")
        }
    }
}

tasks.named("preBuild") { dependsOn(checkCauceApiToken) }

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

        // Shared API token (D20): like cauceSyncBaseUrl it comes from ~/.gradle/gradle.properties
        // (or -P / ORG_GRADLE_PROJECT_cauceApiToken), never from the repo. Building the APK
        // without it fails in checkCauceApiToken (top of this file); unit tests of other modules do not need it.
        buildConfigField("String", "API_TOKEN", "\"$cauceApiToken\"")
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
