plugins { id("com.android.application") }

extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    namespace = "com.grafana.sessiondraftprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.grafana.sessiondraftprobe"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "draft-only"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 } }
    // Compile the same internal draft and initializer, without publishing a test API.
    sourceSets["main"].kotlin.directories.addAll(
        listOf("../src/main/kotlin", "../../android/src/main/java"),
    )
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(platform("io.opentelemetry.android:opentelemetry-android-bom:1.8.0-session57-draft-alpha-SNAPSHOT"))
    implementation("io.opentelemetry.android:android-agent:1.8.0-session57-draft-SNAPSHOT")
    implementation("io.opentelemetry.android:session:1.8.0-session57-draft-SNAPSHOT")
    implementation("io.opentelemetry.android.instrumentation:native-crash:1.8.0-session57-draft-alpha-SNAPSHOT")
}
