plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
apply(from = rootProject.file("gradle/runtime-notices.gradle.kts"))
android {
    namespace = "com.bontecou.whereday"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        resValue("string", "app_name", "Whereday")
        applicationId = "com.bontecou.whereday"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 29; targetSdk = 36
        versionCode = providers.gradleProperty("whereday.versionCode").orElse("1").get().toInt().also {
            require(it in 1..2_100_000_000) { "Android version code is outside the Play range" }
        }
        versionName = providers.gradleProperty("whereday.versionName").orElse("0.1.0").get().also {
            require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?"))) { "Use a numeric major.minor.patch version with an optional suffix" }
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        getByName("debug") { isPseudoLocalesEnabled = true }
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
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/debug"))
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/release"))
    // QA shows the production debug graph; its instrumentation-only manifest is not a product dependency.
    sourceSets.getByName("mapQa").assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/debug"))
}
tasks.matching {it.name in setOf("preDebugBuild","preMapQaBuild","preReleaseBuild")}.configureEach {
    dependsOn(if(name=="preReleaseBuild") "generateReleaseRuntimeNoticeAssets" else "generateDebugRuntimeNoticeAssets")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":platform")); implementation(project(":export"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.compose.icons); implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime); implementation(libs.lifecycle.compose)
    implementation(libs.maplibre)
    implementation(libs.window.manager)
    androidTestImplementation(libs.window.testing)
    implementation(libs.paging.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.work)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    add("mapQaImplementation", "androidx.compose.ui:ui-test-manifest")
}
