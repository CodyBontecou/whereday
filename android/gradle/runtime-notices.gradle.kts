import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

// Resolve the actual selected graph, including metadata-only modules. Cache-directory scans
// are insufficient: they can silently attribute unused or superseded dependencies to the app.
for (variant in listOf("debug", "release")) {
    tasks.register("write${variant.replaceFirstChar { it.uppercase() }}RuntimeNoticeInventory") {
        group = "verification"
        description = "Inventory selected $variant runtime artifacts and their published POMs for notice review"
        notCompatibleWithConfigurationCache("Queries resolved components and published POM artifacts")
        val destination = layout.buildDirectory.file("reports/runtime-notices/$variant/resolved-inputs.json")
        outputs.file(destination)
        outputs.upToDateWhen { false }
        doLast {
            val file = destination.get().asFile
            check(!file.exists() || file.delete()) { "Cannot invalidate the prior runtime notice inputs" }
            val runtime = configurations.getByName("${variant}RuntimeClasspath")
            val ids = runtime.incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
                .sortedBy { "${it.group}:${it.module}:${it.version}" }
            val artifacts = runtime.incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier }
            }.artifacts.artifacts
                .groupBy { it.id.componentIdentifier }
            val query = dependencies.createArtifactResolutionQuery().forComponents(ids)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
            val poms = query.resolvedComponents.associate { component ->
                component.id to component.getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<ResolvedArtifactResult>().singleOrNull()?.file
            }
            val components = ids.map { id ->
                val pom = poms[id] ?: error("Published POM unavailable for ${id.group}:${id.module}:${id.version}")
                linkedMapOf(
                    "coordinate" to "${id.group}:${id.module}:${id.version}",
                    "pom" to pom.absolutePath,
                    "artifacts" to artifacts[id].orEmpty().map { it.file.absolutePath }.distinct().sorted()
                )
            }
            file.parentFile.mkdirs()
            file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(linkedMapOf(
                "schemaVersion" to 1, "configuration" to "${variant}RuntimeClasspath", "components" to components
            ))) + "\n")
            logger.lifecycle("Runtime notice inputs: {} selected components → {}", components.size, file)
        }
    }
    tasks.register("generate${variant.replaceFirstChar { it.uppercase() }}RuntimeNoticeAssets") {
        group = "build"
        description = "Package verified offline $variant software notices"
        notCompatibleWithConfigurationCache("Runs the selected-input notice collector")
        dependsOn("write${variant.replaceFirstChar { it.uppercase() }}RuntimeNoticeInventory")
        val reports = layout.buildDirectory.dir("reports/runtime-notices/$variant")
        val assets = layout.buildDirectory.dir("generated/runtime-notices/$variant")
        inputs.dir(rootProject.file("notices"))
        inputs.files(rootProject.file("scripts/runtime_notices.py"), rootProject.file("scripts/package_runtime_notices.py"))
        outputs.dir(assets)
        outputs.upToDateWhen { false }
        doLast {
            exec {
                commandLine("python3", rootProject.file("scripts/runtime_notices.py"),
                    reports.get().file("resolved-inputs.json").asFile,
                    reports.get().dir("collected").asFile)
            }
            exec {
                commandLine("python3", rootProject.file("scripts/package_runtime_notices.py"),
                    reports.get().file("collected/inventory.json").asFile,
                    rootProject.file("notices"), assets.get().asFile)
            }
        }
    }
}
