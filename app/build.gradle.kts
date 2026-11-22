import org.gradle.api.tasks.Exec
import java.io.File
import java.util.Properties

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
        ndk { abiFilters += "arm64-v8a" }
    }

    ndkVersion = "28.2.13676358"

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

    // The pinned PJSIP/Mbed TLS source build writes generated SWIG bindings and
    // native libraries here. The source build task is added with the SIP line.
    sourceSets.getByName("main") {
        java.srcDir("$buildDir/generated/pjsip/src/main/java")
        jniLibs.srcDir("$buildDir/generated/pjsip/src/main/jniLibs")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

val generatedPjsipRoot = layout.buildDirectory.dir("generated/pjsip")
val usePreparedPjsip = providers.gradleProperty("opencallerUsePreparedNative").orNull == "true"
val pjsipBash = providers.gradleProperty("opencallerBash").orNull
    ?: providers.environmentVariable("OPENCALLER_BASH").orNull
    ?: "bash"
val pjsipBuildScript = rootProject.file("scripts/build-pjsip-android.sh")
val localAndroidProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(::load)
}
val androidSdkPath = providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: localAndroidProperties.getProperty("sdk.dir")?.replace("\\:", ":")
    ?: throw GradleException("Set sdk.dir in local.properties or ANDROID_SDK_ROOT")
val androidSdkDirectory = File(androidSdkPath)
val pjsipNdkVersion = "28.2.13676358"
val pjsipNdkDirectory = androidSdkDirectory.resolve("ndk/$pjsipNdkVersion")
val androidCmakeDirectory = androidSdkDirectory.resolve("cmake")
val androidCmakeBin = androidCmakeDirectory.listFiles()
    ?.filter(File::isDirectory)
    ?.firstOrNull { it.name == "3.31.6" }
    ?.resolve("bin")
    ?: androidCmakeDirectory.listFiles()
        ?.filter(File::isDirectory)
        ?.firstOrNull { it.name == "3.22.1" }
        ?.resolve("bin")
val buildPjsipAndroid = tasks.register<Exec>("buildPjsipAndroid") {
    val pathEntries = listOfNotNull(androidCmakeBin?.absolutePath, System.getenv("PATH"))

    inputs.file(pjsipBuildScript)
    inputs.files(fileTree(rootProject.file("third_party/pjproject")) { exclude("**/.git") })
    inputs.files(fileTree(rootProject.file("third_party/mbedtls")) { exclude("**/.git") })
    inputs.property("androidNdkVersion", pjsipNdkVersion)
    inputs.property("androidAbi", "arm64-v8a")
    outputs.dir(generatedPjsipRoot.map { it.dir("src/main/java") })
    outputs.dir(generatedPjsipRoot.map { it.dir("src/main/jniLibs") })
    environment("ANDROID_NDK_ROOT", pjsipNdkDirectory.absolutePath)
    environment("JAVA_HOME", System.getProperty("java.home"))
    environment("OPENCALLER_ANDROID_ABI", "arm64-v8a")
    environment("PATH", pathEntries.joinToString(File.pathSeparator))

    if (usePreparedPjsip) {
        val preparedLibrary = generatedPjsipRoot.get().file("src/main/jniLibs/arm64-v8a/libpjsua2.so").asFile
        if (!preparedLibrary.isFile) {
            throw GradleException("opencallerUsePreparedNative=true requires the stable PJSIP library under app/build/generated/pjsip")
        }
        onlyIf { false }
    } else {
        commandLine(pjsipBash, pjsipBuildScript.absolutePath)
    }
}

val preparePjsipJavaSources = tasks.register("preparePjsipJavaSources") {
    dependsOn(buildPjsipAndroid)
    val cameraSource = generatedPjsipRoot.map { it.file("src/main/java/org/pjsip/PjCamera2.java") }
    outputs.file(cameraSource)
    doLast {
        val cameraFile = cameraSource.get().asFile
        if (cameraFile.isFile) {
            val source = cameraFile.readText()
            val classDeclaration = "public class PjCamera2"
            val lintAnnotation = "@android.annotation.SuppressLint(\"MissingPermission\")\n$classDeclaration"
            if (classDeclaration in source && lintAnnotation !in source) {
                // This generated camera wrapper is unreachable: OpenCaller builds PJSIP with video disabled.
                cameraFile.writeText(source.replace(classDeclaration, lintAnnotation))
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(preparePjsipJavaSources)
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
