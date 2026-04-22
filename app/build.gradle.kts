plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace  = "dev.bonelesspi.mylens"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bonelesspi.mylens"
        minSdk        = 33
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/*.md"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.exifinterface)
    implementation(libs.datastore.preferences)

    // CameraX
    implementation(libs.bundles.camerax)

    // Third-party
    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    implementation(libs.opencv)
    implementation(libs.itext7.core) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.itextpdf", module = "bouncy-castle-adapter")
        exclude(group = "com.itextpdf", module = "bouncy-castle-fips-adapter")
    }
}