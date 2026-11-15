plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oldmobile.biketracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oldmobile.biketracker"
        minSdk = 26
        // Target the phone's own API level family: the Zenfone 3 runs Android 8.0.
        // targetSdk 28 keeps the simple background/foreground-service model of that era.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/biketracker.jks")
            storePassword = "biketracker"
            keyAlias = "biketracker"
            keyPassword = "biketracker"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug { signingConfig = signingConfigs.getByName("release") }
    }

    androidResources { noCompress += listOf("bin") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true; all {
        it.systemProperty("robolectric.offline", "true")
        it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
        it.dependsOn("copyRobolectricJars")
    }  } }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.garmin:fit:21.221.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

// Robolectric's own downloader is not proxy friendly: resolve its Android jars through Gradle.
val robolectricSdk28: Configuration by configurations.creating { isTransitive = false }
val robolectricSdk34: Configuration by configurations.creating { isTransitive = false }
dependencies {
    robolectricSdk28("org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i6")
    robolectricSdk34("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}
tasks.register<Copy>("copyRobolectricJars") {
    from(robolectricSdk28, robolectricSdk34)
    into(layout.buildDirectory.dir("robolectric-jars"))
}
