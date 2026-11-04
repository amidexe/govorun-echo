plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Shared types and text operations; processing is provided by the installed Govorun app.
android {
    namespace = "com.govorun.echo.smart"
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

}
