pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // ScrawlKit for debug builds, published to ~/.m2 from ~/dev/scrawl/android.
        mavenLocal { content { includeGroup("dev.kitlangton.scrawl") } }
    }
}
rootProject.name = "Whereday"
include(":automation-client", ":app", ":domain", ":data", ":platform", ":export", ":geography", ":geography-assets", ":billing")
