plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { implementation(libs.coroutines); testImplementation(libs.junit); testImplementation(libs.gson) }
