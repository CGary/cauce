import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.cauce.core.events"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Copies the contract (`schema/`) into the debug assets so `SchemaEventValidator` can read it at
 * runtime. The contract stays the single source of truth; nothing is duplicated in the repo.
 */
abstract class CopyDebugSchemas @Inject constructor(
    private val fileSystem: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        fileSystem.sync {
            from(schemaFiles)
            into(outputDir)
        }
    }
}

val copyDebugSchemas = tasks.register<CopyDebugSchemas>("copyDebugSchemas") {
    schemaFiles.from(
        rootProject.fileTree(rootProject.file("../schema")) {
            include("envelope.v1.json")
            include("events/*.json")
        },
    )
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        // AGP sets outputDir and treats it as the root of the debug assets.
        variant.sources.assets?.addGeneratedSourceDirectory(copyDebugSchemas) { it.outputDir }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("cauce.schemaDir", rootProject.file("../schema").absolutePath)
}

dependencies {
    api(libs.androidx.navigation.compose)
    api(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.json.schema.validator)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    // Test-only aggregation: the conformance runner dispatches through every feature's projections.
    testImplementation(project(":feature:alarmas"))
    testImplementation(project(":feature:calendario_google"))
}
