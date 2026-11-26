import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the keystore and its passwords live OUTSIDE the repository.
// Path from the WRISTGATE_SIGNING env var, or ~/.config/wristgate/signing.properties:
//   storeFile=/path/to/release.jks
//   storePassword=…
//   keyAlias=…
//   keyPassword=…
// Without it, release builds come out unsigned (debug builds always work).
val signingProps = Properties().apply {
    val path = System.getenv("WRISTGATE_SIGNING")
        ?: "${System.getProperty("user.home")}/.config/wristgate/signing.properties"
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.federicogalli.wristgate"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.federicogalli.wristgate"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Debug builds install side by side with the release one.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Release APK named after the version: wristgate-0.1.0.apk
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("wristgate-${output.versionName.get()}.apk")
        }
    }
}
// No external dependencies: Android system APIs only.
