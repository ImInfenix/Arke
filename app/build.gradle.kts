import java.io.File

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.arke.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.arke.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1-prototype"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".local/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

val debugKeystore = rootProject.file(".local/debug.keystore")
val createDebugKeystore by tasks.registering(Exec::class) {
    onlyIf { !debugKeystore.exists() }
    doFirst {
        debugKeystore.parentFile.mkdirs()
        require(debugKeystore.parentFile.toPath().toRealPath().startsWith(rootProject.projectDir.toPath().toRealPath()))
    }
    val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
    commandLine(keytool, "-genkeypair", "-keystore", debugKeystore.absolutePath,
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
        "-dname", "CN=Android Debug,O=Android,C=US", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
}

tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(createDebugKeystore)
}
