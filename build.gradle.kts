plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

/**
 * Module dependency rules (see docs/ARCHITECTURE.md). Pure modules must never depend on
 * Android modules, and the engine must stay independent of everything except core:common.
 */
val pureModules = setOf(":core:common", ":domain", ":download-engine", ":testing:testserver")
val allowedProjectDeps = mapOf(
    ":core:common" to emptySet(),
    ":domain" to setOf(":core:common"),
    ":download-engine" to setOf(":core:common", ":testing:testserver"),
    ":testing:testserver" to emptySet(),
    ":core:database" to setOf(":core:common", ":domain"),
    ":core:designsystem" to setOf(":core:common", ":domain"),
)

tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Fails when a module depends on a module its layer may not use."
    notCompatibleWithConfigurationCache("Inspects the project model")
    val pure = pureModules
    doLast {
        val violations = mutableListOf<String>()
        project.subprojects.forEach { p ->
            val allowed = allowedProjectDeps[p.path] ?: return@forEach
            p.configurations.forEach { c ->
                c.dependencies.withType(ProjectDependency::class.java).forEach { d ->
                    val target = d.path
                    if (target !in allowed && target != p.path) violations += "${p.path} -> $target (${c.name})"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Module dependency violations:\n" + violations.joinToString("\n"))
        }
        logger.lifecycle("Module dependency check passed for ${allowedProjectDeps.size} constrained modules; pure JVM modules: $pure")
    }
}
