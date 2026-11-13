plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.govorun.echo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.govorun.echo"
        minSdk = 31
        targetSdk = 35
        versionCode = 276
        versionName = "0.1.275"
        // Public identifier for the Android OAuth client. The device obtains its own token.
        manifestPlaceholders["YANDEX_CLIENT_ID"] = "b40b6b8a28a844cc8aca1298aca70e05"
        buildConfigField("String", "UPDATE_FEED", "\"\"")
        buildConfigField("boolean", "HAS_HOTKEY", "true")
        buildConfigField("boolean", "HAS_YANDEX_HOME", "true")
        // Smart functions are provided by Govorun in the same release for every distribution channel.
        buildConfigField("boolean", "HAS_SMART", "true")
        // Echo on a computer: the blind relay the phone sends a computer's notes through (relay/ in this repository).
        buildConfigField("String", "ECHO_WEB_RELAY", "\"https://echo.govorun.club\"")
        // The name under the launcher icon; a measuring build beside the usual debug one can have its own (debug, below).
        manifestPlaceholders["launcherLabel"] = "@string/launcher_name"
        // Govorun's signature permission that alone may open the volume-key entry (VolumeKeyEntry).
        manifestPlaceholders["govorunPackage"] = "com.govorun.lite"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // A measuring run can live beside the usual debug build and its notes: -PechoDebugSuffix=.bench gives it its own package.
            applicationIdSuffix = providers.gradleProperty("echoDebugSuffix").getOrElse(".debug")
            // ... and a name of its own on the launcher, so two debug icons are told apart: -PechoDebugLabel="Эхо — синтетика".
            providers.gradleProperty("echoDebugLabel").orNull?.let { manifestPlaceholders["launcherLabel"] = it }
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "HAS_SMART", "true")
            // The relay on the computer the phone or emulator is plugged into: go run ./relay, then adb reverse tcp:8787 tcp:8787.
            buildConfigField("String", "ECHO_WEB_RELAY", "\"http://127.0.0.1:8787\"")
            // The debug build of Echo pairs with the debug build of Govorun (GovorunProvider).
            manifestPlaceholders["govorunPackage"] = "com.govorun.lite.debug"
            // A separately registered debug client must not intercept the signed app's OAuth callback.
            manifestPlaceholders["YANDEX_CLIENT_ID"] = providers.gradleProperty("echoYandexDebugClientId")
                .getOrElse("00000000000000000000000000000000")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Volume-key capture is provided by Govorun; no separate accessibility service.
            buildConfigField("boolean", "HAS_HOTKEY", "false")
        }
    }


    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }

    packaging.resources.excludes += "META-INF/NOTICE.md"

    androidResources {
        localeFilters += listOf("ru", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

abstract class GenerateRecordingShortcut : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val resourceDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val shortcut = resourceDirectory.file("xml/shortcuts.xml").get().asFile
        shortcut.parentFile.mkdirs()
        shortcut.writeText(
            """
            <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
                <shortcut
                    android:shortcutId="record"
                    android:enabled="true"
                    android:icon="@mipmap/ic_shortcut_record"
                    android:shortcutShortLabel="@string/shortcut_record_short"
                    android:shortcutLongLabel="@string/shortcut_record_long">
                    <intent
                        android:action="android.intent.action.VIEW"
                        android:targetClass="com.govorun.echo.record.QuickRecordActivity"
                        android:targetPackage="${applicationId.get()}" />
                </shortcut>
                <shortcut
                    android:shortcutId="command"
                    android:enabled="true"
                    android:icon="@mipmap/ic_shortcut_command"
                    android:shortcutShortLabel="@string/widget_command_label"
                    android:shortcutLongLabel="@string/shortcut_command_short">
                    <intent
                        android:action="com.govorun.echo.SAY_COMMAND"
                        android:targetClass="com.govorun.echo.record.QuickRecordActivity"
                        android:targetPackage="${applicationId.get()}" />
                </shortcut>
            </shortcuts>
            """.trimIndent(),
        )
    }
}

androidComponents {
    onVariants { variant ->
        // Android reads targetPackage as a literal in shortcut XML.
        val generateShortcut = tasks.register<GenerateRecordingShortcut>("generate${variant.name.replaceFirstChar(Char::uppercaseChar)}RecordingShortcut") {
            applicationId.set(variant.applicationId)
            resourceDirectory.set(layout.buildDirectory.dir("generated/recordingShortcut/${variant.name}"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generateShortcut, GenerateRecordingShortcut::resourceDirectory)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.adamglin:phosphor-icon:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // Reads the code on a computer's screen («На компьютере») on the phone itself: no Google services, nothing leaves the phone.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-compose:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.yandex.android:authsdk:3.1.3")
    // LoginSDK's public parcelables reference Parcelize, which the SDK POM omits.
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.3.0")
    implementation(project(":clip"))
    implementation(project(":smart"))

}

// The licence notices in the app name every library it ships: a store or test-channel build stops when the two drift apart.
val checkLicenseNotices by tasks.registering {
    val notices = file("src/main/assets/licenses.txt")
    // Only the classpaths of the build types this project has: a copy without some of them still checks the rest.
    val classpaths = listOf("releaseRuntimeClasspath", "betaRuntimeClasspath").mapNotNull { configurations.findByName(it)?.incoming?.resolutionResult }
    doLast {
        val artifact = Regex("[\\w.-]+:[\\w.-]+:[\\w.-]+")
        val listed = notices.readLines().map { it.trim() }.filter { artifact.matches(it) }.toSet()
        val shipped =
            classpaths.flatMap { it.allComponents }.map { it.id }
                .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
                .filterNot { it.module.endsWith("-bom") }
                .map { "${it.group}:${it.module}:${it.version}" }
                .toSet()
        check(listed == shipped) { "${notices.name} is out of date. Add: ${shipped - listed}. Remove: ${listed - shipped}" }
    }
}
tasks.configureEach { if (name == "preReleaseBuild" || name == "preBetaBuild") dependsOn(checkLicenseNotices) }
