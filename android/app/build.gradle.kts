plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "com.bontecou.whereday"
    compileSdk = 36
    defaultConfig {
        resValue("string", "app_name", "Whereday")
        applicationId = "com.bontecou.whereday"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release { isMinifyEnabled = false }
        create("mapQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".mapqa"
            resValue("string", "app_name", "Whereday Map QA")
            versionNameSuffix = "-mapqa"
            matchingFallbacks += listOf("debug")
        }
    }
    // Connected application tests uninstall their target on cleanup. Never target the owner package.
    testBuildType = "mapQa"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":platform")); implementation(project(":export"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.compose.icons); implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime); implementation(libs.lifecycle.compose)
    implementation(libs.maplibre)
    implementation(libs.paging.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.work)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    add("mapQaImplementation", "androidx.compose.ui:ui-test-manifest")
}
