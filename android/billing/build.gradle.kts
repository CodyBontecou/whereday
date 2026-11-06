plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.coroutines)
    implementation(libs.gson)
    testImplementation(libs.junit)
}
