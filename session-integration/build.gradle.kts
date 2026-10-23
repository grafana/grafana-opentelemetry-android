buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins {
    id("com.android.library") version "9.4.1"
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("com.diffplug.spotless") version "8.10.4"
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "com.grafana.opentelemetry.android.sessiondraft"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 } }
    // Exercise the package's actual initializer without changing its published sources or pins.
    sourceSets["main"].kotlin.directories.add("../android/src/main/java")
    sourceSets["test"].kotlin.directories.add("../android/src/test/java")
    sourceSets["test"].java.directories.add("../android/src/test/java")
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all {
            it.maxParallelForks = 1
            it.systemProperty("sessionDraft.lockProbe", file("src/test/resources/LockProbe.java").absolutePath)
        }
    }
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(platform("io.opentelemetry.android:opentelemetry-android-bom:1.8.0-session57-draft-alpha-SNAPSHOT"))
    implementation("io.opentelemetry.android:android-agent:1.8.0-session57-draft-SNAPSHOT")
    implementation("io.opentelemetry.android:session:1.8.0-session57-draft-SNAPSHOT")
    testImplementation("io.opentelemetry.android:core:1.8.0-session57-draft-alpha-SNAPSHOT")
    testImplementation("io.opentelemetry.android:semconv:1.8.0-session57-draft-alpha-SNAPSHOT")
    testImplementation("io.opentelemetry.android:services:1.8.0-session57-draft-alpha-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha")
}

spotless {
    kotlin {
        target("src/**/*.kt", "probe/src/**/*.kt")
        ktlint("1.7.1")
    }
}

kover {
    reports.filters.includes.classes(
        "com.grafana.opentelemetry.android.ColdStartSessions*",
        "com.grafana.opentelemetry.android.DraftSession*",
        "com.grafana.opentelemetry.android.SavedSession",
    )
}
