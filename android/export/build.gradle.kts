plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":domain")); implementation(libs.gson); implementation(libs.coroutines); testImplementation(libs.junit) }

// Isolate the large import contract from other test fixtures and enforce a fixed JVM heap ceiling.
tasks.test { exclude("**/StreamingImportScaleTest.class", "**/GpxStreamingTest.class") }
tasks.register<Test>("streamingImportBudgetTest") {
    group="verification"
    description="Stream a million JSON, CSV and Markdown records under a 96 MiB JVM heap."
    dependsOn(tasks.testClasses)
    testClassesDirs=sourceSets["test"].output.classesDirs
    classpath=sourceSets["test"].runtimeClasspath
    filter {includeTestsMatching("com.bontecou.whereday.export.StreamingImportScaleTest")}
    maxHeapSize="96m"
}

tasks.register<Test>("streamingGpxBudgetTest") {
    group="verification"
    description="Stream one million GPX fixes under a 96 MiB JVM heap."
    dependsOn(tasks.testClasses)
    testClassesDirs=sourceSets["test"].output.classesDirs
    classpath=sourceSets["test"].runtimeClasspath
    filter {includeTestsMatching("com.bontecou.whereday.export.GpxStreamingTest")}
    maxHeapSize="96m"
}
