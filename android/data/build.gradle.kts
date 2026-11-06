plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.ksp) }
android {
    namespace = "com.bontecou.whereday.data"
    compileSdk = 36
    defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":domain"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    api(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.gson)
}
