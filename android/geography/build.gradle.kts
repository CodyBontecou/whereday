plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":domain"))
    implementation(libs.coroutines)
    implementation("com.esri.geometry:esri-geometry-api:2.2.4")
    testImplementation(libs.junit)
}
