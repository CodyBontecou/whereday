plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
fun configString(value:String):String {
    require(value.none { it<' ' }) { "Purchase configuration must not contain control characters" }
    return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
}
android {
    namespace = "com.bontecou.whereday.platform"
    compileSdk = 36
    defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/geography-assets"))
    // Run the same provider-free transport regressions on the JVM and Android runtime.
    sourceSets.getByName("test").java.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/kotlin")
    buildFeatures { buildConfig=true }
    defaultConfig {
        val reviewEnabled=providers.gradleProperty("whereday.playReviewEnabled").getOrElse("false")
        require(reviewEnabled in setOf("true","false"))
        buildConfigField("boolean","PLAY_REVIEW_AVAILABLE",reviewEnabled)
        buildConfigField("String","PURCHASE_PRODUCT_ID",configString(providers.gradleProperty("whereday.purchaseProductId").getOrElse("whereday_export_lifetime")))
        buildConfigField("String","PURCHASE_VERIFIER_URL",configString(providers.gradleProperty("whereday.purchaseVerifierUrl").getOrElse("")))
        buildConfigField("String","PURCHASE_VERIFIER_PUBLIC_KEY",configString(providers.gradleProperty("whereday.purchaseVerifierPublicKey").getOrElse("")))
        val usageEnabled=providers.gradleProperty("whereday.analyticsEnabled").getOrElse("false")
        require(usageEnabled in setOf("true","false"))
        buildConfigField("boolean","USAGE_ANALYTICS_AVAILABLE",usageEnabled)
        buildConfigField("String","USAGE_ANALYTICS_TOKEN",configString(providers.gradleProperty("whereday.analyticsToken").getOrElse("")))
        val placesEnabled=providers.gradleProperty("whereday.geoapifyNearbyEnabled").getOrElse("false")
        require(placesEnabled in setOf("true","false"))
        val routingEnabled=providers.gradleProperty("whereday.geoapifyRoutingEnabled").getOrElse("false")
        require(routingEnabled in setOf("true","false"))
        val placesKey=providers.gradleProperty("whereday.geoapifyApiKey").getOrElse("").trim()
        require(placesEnabled!="true" && routingEnabled!="true" || placesKey.length in 1..512 && placesKey.none {it.isISOControl()}) {"Geoapify configuration is incomplete"}
        buildConfigField("boolean","GEOAPIFY_NEARBY_AVAILABLE",placesEnabled)
        buildConfigField("boolean","GEOAPIFY_ROUTING_AVAILABLE",routingEnabled)
        buildConfigField("String","GEOAPIFY_API_KEY",configString(if(placesEnabled=="true" || routingEnabled=="true") placesKey else ""))
        val projectNumber=providers.gradleProperty("whereday.playCloudProjectNumber").getOrElse("0").toLong()
        require(projectNumber>=0)
        buildConfigField("long","PLAY_CLOUD_PROJECT_NUMBER","${projectNumber}L")
    }
}
tasks.named("preBuild") { dependsOn(":geography-assets:generate") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":data")); implementation(project(":export"))
    implementation(libs.core); implementation(libs.location)
    implementation(libs.coroutines.android); implementation(libs.work)
    implementation(libs.documentfile)
    api(project(":geography"))
    api(project(":billing"))
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("com.google.android.play:review:2.0.2")
    implementation(libs.gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.work:work-testing:2.10.0")
}
