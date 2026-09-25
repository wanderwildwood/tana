import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wanderwildwood.tana"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wanderwildwood.tana"
        // The Kompakt runs Android 12 (API 31); nothing here needs anything newer.
        minSdk = 31
        targetSdk = 31
        versionCode = 4
        versionName = "0.2.1"
    }

    // A real keystore in signing/ signs every build type when it is present, so the
    // very first install is already release-signed and a later update can never hit
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE. It is gitignored, and there is no fallback:
    // a fresh clone builds an unsigned release APK, which will not install anywhere.
    val signingPropertiesFile = rootProject.file("signing/signing.properties")
    val realSigningConfig = if (signingPropertiesFile.isFile) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs.create("real") {
            storeFile = rootProject.file("signing/signing.keystore")
            storePassword = signingProperties.getProperty("STORE_PASSWORD")
            keyAlias = signingProperties.getProperty("KEY_ALIAS")
            keyPassword = signingProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            realSigningConfig?.let { signingConfig = it }
        }
        getByName("release") {
            // AGP otherwise stamps the git revision of the build into META-INF, which
            // publishes a little more about how a release was made than a release needs to
            // - and it is also the one thing that differs between a release built on the
            // build box, which works from an rsync with no .git, and the one GitHub
            // publishes from a clone. Off, so the two hash the same, which is the only way
            // the checksum in a forum post can be checked against anything but itself.
            vcsInfo {
                include = false
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            realSigningConfig?.let { signingConfig = it }
        }
    }

    lint {
        // Sideloaded onto a Kompakt, not going to Google Play, whose API-33 floor this
        // otherwise trips. Targeting the OS the device actually runs is deliberate.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // The About dialog shows the version it is actually running.
        buildConfig = true
    }

    packaging {
        resources {
            // Bouncy Castle and smbj each ship the same licence and module files.
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        }
    }

    sourceSets {
        named("main") { kotlin.srcDir("src/main/kotlin") }
        named("test") { kotlin.srcDir("src/test/kotlin") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.mmd)

    // The servers' shared drives are Samba shares. smbj speaks SMB 2 and 3 in plain Java
    // (Apache-2.0); its own Bouncy Castle is named explicitly, because Android carries a
    // stripped copy of that library under the same name and the two must not meet.
    implementation(libs.smbj)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}
