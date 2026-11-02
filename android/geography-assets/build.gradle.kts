plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    implementation("net.iakovlev:timeshape:2026b.29")
    testImplementation(project(":geography"))
    testImplementation(libs.coroutines)
    testImplementation(libs.junit)
}
val assetDirectory=rootProject.layout.projectDirectory.dir("platform/build/generated/geography-assets")
val generate by tasks.registering(JavaExec::class) {
    dependsOn(tasks.classes)
    mainClass.set("com.bontecou.whereday.geographyassets.GenerateBoundaries")
    classpath=sourceSets.main.get().runtimeClasspath
    args(assetDirectory.asFile.absolutePath)
    inputs.files(sourceSets.main.get().runtimeClasspath)
    outputs.dir(assetDirectory)
    maxHeapSize="1g"
}
tasks.test {
    dependsOn(generate)
    systemProperty("geography.assets",assetDirectory.asFile.absolutePath)
    maxHeapSize="1g"
}
