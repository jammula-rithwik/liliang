plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liliang.app"
    compileSdk = 36

    defaultConfig {
        // Distinct from workout2-app so both install side by side.
        applicationId = "com.liliang.app"
        minSdk = 26
        targetSdk = 36
        // Bump these on every build worth installing. They sat at 1 / "1.0" through
        // every release to date, which is why "is R8-1 actually installed?" could not
        // be answered from the phone — and it sent the duplicate-session investigation
        // down a wrong branch for days. Visible in Settings → Apps → KUAN.
        versionCode = 22
        versionName = "R22 — 力量"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// APKs are named for what they are — Liliang-R<code>.apk, the R matching the
// release round in the git log — instead of the stock app-debug.apk, so the
// file on the phone says which build it is (Greg, 29 Aug 2026). Migrated off
// the deprecated applicationVariants API 30 Aug 2026; the VariantOutputImpl
// cast is still the only route to the filename pre-AGP-9, so keep an eye on
// it at the next AGP major.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.let { impl ->
                val code = impl.versionCode.orNull ?: 0
                impl.outputFileName.set("Liliang-R$code.apk")
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    debugImplementation(composeBom)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
