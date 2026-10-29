import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.FilterConfiguration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version lives in gradle.properties (pushup.versionCode / pushup.versionName).
val appVersionCode = providers.gradleProperty("pushup.versionCode").get().toInt()
val appVersionName = providers.gradleProperty("pushup.versionName").get()

// Release signing. Set all four (env vars, or Gradle properties e.g. in ~/.gradle/gradle.properties)
// to sign with a dedicated keystore. Otherwise release falls back to the debug keystore
// (~/.android/debug.keystore), which is what every install so far has been signed with.
// Android only updates an app in place if the new APK has the SAME signing key.
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("PUSHUP_KEYSTORE")
val releaseKeystorePassword = signingValue("PUSHUP_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("PUSHUP_KEY_ALIAS")
val releaseKeyPassword = signingValue("PUSHUP_KEY_PASSWORD")
val hasReleaseKeystore = releaseKeystore != null && releaseKeystorePassword != null && releaseKeyAlias != null

// ABI splits (4 per-ABI APKs + 1 universal) only when a release task is requested,
// so assembleDebug/installDebug stay fast and still produce a single app-debug.apk.
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// R8 code + resource shrinking for release. Off by default; see README / -Ppushup.minify=true.
val minifyRelease = providers.gradleProperty("pushup.minify").orNull?.toBoolean() ?: false

android {
    namespace = "com.pushupalarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pushupalarm"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    splits {
        abi {
            isEnable = buildingRelease
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Real problems (NewApi, RestrictedApi, ...) stay errors and fail the build.
        // These are "a newer version exists" / style nudges and must not fail CI.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "UseKtx")
        // Only affects Play Store device filtering (CAMERA implies a back camera);
        // sideloaded installs ignore <uses-feature>.
        disable += "PermissionImpliesUnsupportedChromeOsHardware"
        // Running on minSdk 26 phones is the whole point: never let this slip to a warning.
        error += "NewApi"
    }
}

if (buildingRelease && !hasReleaseKeystore) {
    logger.lifecycle("pushup-alarm: PUSHUP_KEYSTORE not set, signing release with the debug keystore (~/.android/debug.keystore)")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Copies the release APKs to app/build/dist/ with friendly names:
 * pushup-alarm-<versionName>-<abi>.apk and pushup-alarm-<versionName>-universal.apk.
 */
abstract class CollectApksTask : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val out = outputDir.get().asFile
        out.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
        val built = builtArtifactsLoader.get().load(apkFolder.get())
            ?: throw GradleException("No APKs found in ${apkFolder.get()}")
        built.elements.forEach { apk ->
            val abi = apk.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            val target = File(out, "pushup-alarm-${versionName.get()}-$abi.apk")
            File(apk.outputFile).copyTo(target, overwrite = true)
            logger.lifecycle("  ${target.relativeTo(project.rootDir)}  (${target.length() / 1024} KB)")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val collect = tasks.register<CollectApksTask>("collect${variant.name.replaceFirstChar { it.uppercase() }}Apks") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            versionName.set(appVersionName)
            outputDir.set(layout.buildDirectory.dir("dist"))
        }
        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { c -> c.uppercase() }}" }
            .configureEach { dependsOn(collect) }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Capped so nothing drags Compose past the BOM's 1.7.x: core-ktx 1.18+ and
    // lifecycle 2.9+ / activity-compose 1.11+ pull Compose runtime/ui 1.9-1.11.
    // Bump these together with the Compose BOM.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Camera + on-device pose detection for counting reps
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.guava:guava:33.7.2-android") // ListenableFuture for CameraX

    testImplementation("junit:junit:4.13.2")
}
