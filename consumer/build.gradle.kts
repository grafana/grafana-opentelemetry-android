buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    id("com.android.application") version "9.5.0"
}

android {
    namespace = "com.grafana.opentelemetry.consumer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grafana.opentelemetry.consumer"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.0-dev"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
    flavorDimensions += "setup"
    productFlavors {
        create("grafana") {
            dimension = "setup"
            applicationIdSuffix = ".grafana"
        }
        create("upstream") {
            dimension = "setup"
            applicationIdSuffix = ".upstream"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    "grafanaImplementation"("com.grafana.opentelemetry:android:${file("../version.txt").readText().trim()}")
    "upstreamImplementation"(platform("io.opentelemetry.android:opentelemetry-android-bom:1.7.0-alpha"))
    "upstreamImplementation"("io.opentelemetry.android:android-agent")
}

dependencyLocking {
    lockAllConfigurations()
    // This artifact is built from the same checkout; third-party dependencies remain locked.
    ignoredDependencies.add("com.grafana.opentelemetry:android")
}

tasks.register("checkRemovalDependencies") {
    doLast {
        listOf("upstreamDebugRuntimeClasspath", "upstreamReleaseRuntimeClasspath").forEach { name ->
            val modules = configurations.getByName(name).resolvedConfiguration.resolvedArtifacts
            check(modules.none { it.moduleVersion.id.group == "com.grafana.opentelemetry" }) {
                "$name still depends on the Grafana layer"
            }
        }
    }
}
