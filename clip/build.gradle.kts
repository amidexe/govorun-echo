plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// A note as a video to watch or subtitles to edit with: it gets the sound and the words with their times, nothing else of the app.
android {
    namespace = "com.govorun.echo.clip"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.media3:media3-muxer:1.11.1")

}
