plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":domain")); implementation(libs.gson); implementation(libs.coroutines); testImplementation(libs.junit) }

// Isolate the large import contract from other test fixtures and enforce a fixed JVM heap ceiling.
tasks.test {
    // Downstream Swift acceptance consumes this file, including after build-cache restoration.
    outputs.file(layout.buildDirectory.file("place-provenance/android-exports.json"))
    exclude("**/GoogleTimelineImportBudgetTest.class", "**/StreamingImportScaleTest.class", "**/GpxStreamingTest.class", "**/KmlStreamingBudgetTest.class", "**/JsonLocationStreamingBudgetTest.class", "**/MarkdownStreamingBudgetTest.class")
}
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

tasks.register<Test>("streamingKmlBudgetTest") {
    group="verification"
    description="Stream one million KML fixes under a 96 MiB JVM heap with bounded private disk staging."
    dependsOn(tasks.testClasses)
    testClassesDirs=sourceSets["test"].output.classesDirs
    classpath=sourceSets["test"].runtimeClasspath
    filter {includeTestsMatching("com.bontecou.whereday.export.KmlStreamingBudgetTest")}
    maxHeapSize="96m"
}

tasks.register<Test>("streamingJsonLocationBudgetTest") {
    group="verification"
    description="Stream one million records per JSON location format under a 96 MiB JVM heap."
    dependsOn(tasks.testClasses)
    testClassesDirs=sourceSets["test"].output.classesDirs
    classpath=sourceSets["test"].runtimeClasspath
    filter {includeTestsMatching("com.bontecou.whereday.export.JsonLocationStreamingBudgetTest")}
    maxHeapSize="96m"
}


tasks.register<Test>("streamingMarkdownBudgetTest") {
    group="verification"
    description="Stream one million visits and points with bounded reverse-day Markdown staging under a 96 MiB JVM heap."
    dependsOn(tasks.testClasses)
    testClassesDirs=sourceSets["test"].output.classesDirs
    classpath=sourceSets["test"].runtimeClasspath
    filter {includeTestsMatching("com.bontecou.whereday.export.MarkdownStreamingBudgetTest")}
    maxHeapSize="96m"
}

tasks.register<Test>("googleTimelineImportBudgetTest") {
    group="verification"
    description="Stream a 250,000-point Google device segment and enforce source limits under a 96 MiB heap."
    dependsOn(tasks.testClasses)
    testClassesDirs=sourceSets["test"].output.classesDirs
    classpath=sourceSets["test"].runtimeClasspath
    filter {includeTestsMatching("com.bontecou.whereday.export.GoogleTimelineImportBudgetTest")}
    maxHeapSize="96m"
}
