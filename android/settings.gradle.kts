pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Whereday"
include(":automation-client", ":app", ":domain", ":data", ":platform", ":export", ":geography", ":geography-assets", ":billing")
