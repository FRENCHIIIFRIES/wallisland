plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wallisland.walllock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wallisland.walllock"
        minSdk = 26
        targetSdk = 34
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        // Same fixed key as Wallisland, so every build installs over the last one.
        create("wallisland") {
            storeFile = file("../app/wallisland.keystore")
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
        lintConfig = file("../app/lint.xml")
        abortOnError = true
        warningsAsErrors = false
    }
}
