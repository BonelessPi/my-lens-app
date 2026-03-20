plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mylens"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mylens"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended kept for icons that exist in it (Delete, Add, etc.)
    // For RotateRight (which doesn't exist), we use Rotate90DegreesCw which does.
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Accompanist permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // Coil — image loading in Compose
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Reorderable drag-and-drop list
    implementation("sh.calvin.reorderable:reorderable:2.1.1")

    // OpenCV via Maven Central — no manual SDK download required!
    // Available since 4.9.0. Includes native .so for all ABIs.
    implementation("org.opencv:opencv:4.10.0")

    // iText7 — PDF generation
    implementation("com.itextpdf:itext7-core:7.2.5") {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.itextpdf", module = "bouncy-castle-adapter")
        exclude(group = "com.itextpdf", module = "bouncy-castle-fips-adapter")
    }
}
