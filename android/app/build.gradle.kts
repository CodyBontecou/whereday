import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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
    buildFeatures { compose = true }
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
// Without the purchase verifier BillingSession treats every capability as free (debug and mapQa builds rely on that),
// so a store build without it would give everything away. Every release build (assembleRelease, bundleRelease and
// anything built on them, such as a Play upload) stops here instead. The checks mirror PurchaseConfiguration.configured.
val checkReleasePurchaseVerifier = tasks.register("checkReleasePurchaseVerifier") {
    group = "verification"
    description = "Fails a release build that lacks the Play purchase verifier configuration."
    val url = providers.gradleProperty("whereday.purchaseVerifierUrl").orElse("")
    val key = providers.gradleProperty("whereday.purchaseVerifierPublicKey").orElse("")
    val projectNumber = providers.gradleProperty("whereday.playCloudProjectNumber").orElse("")
    inputs.property("purchaseVerifierUrl", url)
    inputs.property("purchaseVerifierPublicKey", key)
    inputs.property("playCloudProjectNumber", projectNumber)
    doLast {
        val problems = buildList {
            val endpoint = runCatching { URI(url.get().trim()) }.getOrNull()
            if (endpoint == null || endpoint.scheme != "https" || endpoint.host.isNullOrBlank() || endpoint.rawUserInfo != null || endpoint.query != null || endpoint.fragment != null)
                add("whereday.purchaseVerifierUrl (an https URL without user info, query or fragment)")
            val rsa = runCatching {
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key.get().trim()))) as RSAPublicKey
            }.getOrNull()
            if (rsa == null || rsa.modulus.bitLength() < 2048) add("whereday.purchaseVerifierPublicKey (a base64 X.509 RSA public key of at least 2048 bits)")
            if ((projectNumber.get().trim().toLongOrNull() ?: 0L) <= 0L) add("whereday.playCloudProjectNumber (the positive Google Cloud project number used for Play Integrity)")
        }
        if (problems.isNotEmpty()) throw GradleException(
            "Release builds require the Play purchase verifier. Missing or invalid Gradle properties:\n" +
                problems.joinToString("\n") { "  - $it" } +
                "\nWithout them every Whereday feature would ship free. Set them in ~/.gradle/gradle.properties or pass -P<name>=<value>; " +
                "debug and mapQa builds don't need them.")
    }
}
tasks.matching { it.name == "preReleaseBuild" || it.name.matches(Regex("publish\\w*Release\\w*")) }.configureEach { dependsOn(checkReleasePurchaseVerifier) }
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

