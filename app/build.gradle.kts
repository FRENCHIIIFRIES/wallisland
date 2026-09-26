plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wallisland.island"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wallisland.island"
        minSdk = 26
        targetSdk = 34
        // CI's run number is the build number, so every published APK is newer than the last and the
        // in-app updater can compare them. Local builds are build 1.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        // One fixed key so each build installs over the previous one (and the in-app updater works).
        // It lives in the repo on purpose: this is a sideloaded hobby app, and a key per CI run made
        // every update fail. Move it to a CI secret if the app is ever distributed more widely.
        create("wallisland") {
            storeFile = file("wallisland.keystore")
            storePassword = "wallisland"
            keyAlias = "wallisland"
            keyPassword = "wallisland"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("wallisland")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("wallisland")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = false
    }
}
