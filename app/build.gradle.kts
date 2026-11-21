plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val openCallerVersionCode = providers.gradleProperty("opencallerVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: 1
val openCallerVersionName = providers.gradleProperty("opencallerVersionName").orNull ?: "0.1.0"

if (openCallerVersionCode <= 0) {
    throw GradleException("opencallerVersionCode must be a positive integer")
}

android {
    namespace = "io.github.iamsrishanth.opencaller"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iamsrishanth.opencaller"
        minSdk = 26
        targetSdk = 37
        versionCode = openCallerVersionCode
        versionName = openCallerVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
