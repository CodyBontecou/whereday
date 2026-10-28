// Test-only foreign UID client. It can target only the disposable mapQa application.
plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android {
    namespace="com.bontecou.whereday.automationclient";compileSdk=36
    defaultConfig {applicationId="com.bontecou.whereday.automationclient";minSdk=29;targetSdk=36;versionCode=1;versionName="test-only"}
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
androidComponents {beforeVariants(selector().withBuildType("release")) {it.enable=false}}
kotlin {jvmToolchain(17)}
