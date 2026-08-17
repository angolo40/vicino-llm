import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing config is loaded from local.properties (git-ignored) so
// the keystore credentials never enter source control. Debug builds still
// use the stock Android debug key, so contributors without the release
// keystore can build debug APKs without changing anything.
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.sectl.litertlm.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sectl.litertlm.server"
        minSdk = 31
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProps.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only attach the release signing config when the credentials
            // are actually present. Without this guard, contributors who
            // clone the repo and run assembleRelease without a keystore
            // would hit a confusing "store file not set" error from the
            // Android Gradle Plugin.
            if (keystoreProps.getProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // We read VERSION_NAME / VERSION_CODE in the About tab.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    // LiteRT-LM SDK — declared but not yet called; exercised in Phase 4.
    implementation(libs.litertlm.android)

    // QR code generation for the About tab's crypto tip jars. Pure Java,
    // no native deps, ~500 KB. Apache 2.0.
    implementation("com.google.zxing:core:3.5.3")

    // HTML parsing for the web-search "fetch & extract" step. Pulls the
    // readable text out of the top N search results so the model gets
    // real page content, not the SEO snippet. ~400 KB, MIT-like licence.
    implementation("org.jsoup:jsoup:1.17.2")
}
