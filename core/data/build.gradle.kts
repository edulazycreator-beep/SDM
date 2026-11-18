plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.sdm.core.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.x64)
    api(project(":domain"))
    api(project(":core:database"))
    api(project(":download-engine"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.webkit)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.androidx.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
