plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
apply(from = rootProject.file("gradle/runtime-notices.gradle.kts"))
android {
    namespace = "com.bontecou.whereday"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        // Setup links for ScrawlKit (the scrawl build type): `scrawl link --scheme wheredayscrawl`.
        manifestPlaceholders["scrawlScheme"] = "wheredayscrawl"
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
    buildFeatures { compose = true; buildConfig = true }
    // Lists the translated values-* folders as the app's languages, so Android 13+ offers them in per-app language settings.
    androidResources { generateLocaleConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The Play upload key lives outside the repo; its path and passwords come from ~/.gradle/gradle.properties.
    val uploadStoreFile = providers.gradleProperty("whereday.uploadStoreFile").orNull
    if (uploadStoreFile != null) signingConfigs.create("upload") {
        storeFile = file(uploadStoreFile)
        storePassword = providers.gradleProperty("whereday.uploadStorePassword").get()
        keyAlias = providers.gradleProperty("whereday.uploadKeyAlias").get()
        keyPassword = providers.gradleProperty("whereday.uploadKeyPassword").get()
    }
    buildTypes {
        getByName("debug") { isPseudoLocalesEnabled = true }
        release {
            isMinifyEnabled = false
            if (uploadStoreFile != null) signingConfig = signingConfigs.getByName("upload")
        }
        create("mapQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".mapqa"
            resValue("string", "app_name", "Whereday Map QA")
            versionNameSuffix = "-mapqa"
            matchingFallbacks += listOf("debug")
        }
        // The debug app plus ScrawlKit (`./gradlew :app:installScrawl`): hold three fingers to send
        // a recording to your coding agent through Scrawl on the Mac. A build type of its own so the
        // debug and release graphs, and their notice lock, stay exactly the product's (like mapQa's
        // test-only manifest, ScrawlKit is a development tool outside that catalog).
        create("scrawl") {
            initWith(getByName("debug"))
            versionNameSuffix = "-scrawl"
            matchingFallbacks += listOf("debug")
        }
    }
    // Connected application tests uninstall their target on cleanup. Never target the owner package.
    testBuildType = "mapQa"
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/debug"))
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/release"))
    // QA shows the production debug graph; its instrumentation-only manifest is not a product dependency.
    sourceSets.getByName("mapQa").assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/debug"))
    sourceSets.getByName("scrawl") {
        assets.srcDir(layout.buildDirectory.dir("generated/runtime-notices/debug"))
        java.srcDir("src/debug/kotlin")
        manifest.srcFile("src/scrawl/AndroidManifest.xml")
    }
}
tasks.matching {it.name in setOf("preDebugBuild","preMapQaBuild","preScrawlBuild","preReleaseBuild")}.configureEach {
    dependsOn(if(name=="preReleaseBuild") "generateReleaseRuntimeNoticeAssets" else "generateDebugRuntimeNoticeAssets")
}
kotlin { jvmToolchain(17) }
// ScrawlKit in the scrawl build type: hold three fingers to record the screen, your voice and touches and
// send them to your coding agent through Scrawl on the Mac. It comes from ~/.m2
// (`./gradlew publishToMavenLocal` in ~/dev/scrawl/android) and is left out where that isn't
// there (CI); release builds never include it.
val scrawlKitAvailable = File(System.getProperty("user.home"), ".m2/repository/dev/kitlangton/scrawl/scrawlkit-autoinstall/0.1.0").isDirectory

dependencies {
    if (scrawlKitAvailable) add("scrawlImplementation", "dev.kitlangton.scrawl:scrawlkit-autoinstall:0.1.0")
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

// The scrawl build type is the debug app: its debug-only dependencies too.
listOf("Implementation", "RuntimeOnly", "CompileOnly").forEach { kind ->
    configurations.named("scrawl$kind") { extendsFrom(configurations.getByName("debug$kind")) }
}

