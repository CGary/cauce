plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

gradle.projectsEvaluated {
    val violations = mutableListOf<String>()

    allprojects {
        val consumer = path

        configurations.forEach { configuration ->
            configuration.dependencies.withType<ProjectDependency>().forEach { dependency ->
                val target = dependency.dependencyProject.path
                val isTestConfiguration = configuration.name.startsWith("test")
                val coreConformanceWiring = consumer == ":core:events" && isTestConfiguration

                if (target != consumer) {
                    when {
                        consumer.startsWith(":feature:") && !target.startsWith(":core:") ->
                            violations += "$consumer -> $target (${configuration.name}): a feature may only depend on :core:*"

                        consumer.startsWith(":core:") && (target.startsWith(":feature:") || target == ":app") && !coreConformanceWiring ->
                            violations += "$consumer -> $target (${configuration.name}): core may not depend on features or :app"
                    }
                }
            }
        }
    }

    if (violations.isNotEmpty()) {
        throw GradleException(
            "Forbidden module dependencies:\n" + violations.joinToString("\n") { "  - $it" }
        )
    }
}
