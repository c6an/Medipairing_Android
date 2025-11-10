// (No custom imports)


// require Gradle 8.2+
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

plugins {
    alias(libs.plugins.android.application)
    id("com.github.spotbugs")
    id("com.google.gms.google-services")
}

spotbugs {
    ignoreFailures.set(false)
    showStackTraces.set(true)
    showProgress.set(true)
    effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
    reportLevel.set(com.github.spotbugs.snom.Confidence.DEFAULT)
    visitors.set(listOf("FindSqlInjection", "SwitchFallthrough"))
    omitVisitors.set(listOf("FindNonShortCircuit"))
    // chooseVisitors is not supported in SpotBugs Gradle plugin 6.x; use visitors/omitVisitors or filters instead
    reportsDir.set(layout.buildDirectory.dir("spotbugs"))
    includeFilter.set(file("include.xml"))
    excludeFilter.set(file("exclude.xml"))
    baselineFile.set(file("baseline.xml"))
    onlyAnalyze.set(listOf("com.foobar.MyClass", "com.foobar.mypkg.*"))
    maxHeapSize.set("1g")
    extraArgs.set(listOf("-nested:false"))
    jvmArgs.set(listOf("-Duser.language=ja"))
}

android {
    namespace = "com.example.medipairing"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.medipairing"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}



dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.coordinatorlayout)
    implementation(libs.play.services.auth)
    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)


    // TensorFlow Lite core (Interpreter-only lightweight path)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    implementation(libs.okhttp)
    // Guava (Android flavor) provides ListenableFuture used by CameraX
    implementation("com.google.guava:guava:32.1.3-android")
    implementation("androidx.concurrent:concurrent-futures:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("com.kakao.sdk:v2-all:2.22.0")
    implementation("com.google.firebase:firebase-auth:22.0.0")
    implementation("com.google.firebase:firebase-bom:34.5.0")

    // ML Kit Text Recognition (on-device) - bump to latest
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Optional: better results for Korean text
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Encrypt SharedPreferences (Keystore-backed)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")
}

android {
    // Resolve potential libc++_shared duplicates from native deps
    packaging {
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}
