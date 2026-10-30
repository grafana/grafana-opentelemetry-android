plugins { id("com.android.application") }

val disableNativeCrash = providers.gradleProperty("disableNativeCrash").map(String::toBoolean).getOrElse(false)

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
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 } }
    testOptions.unitTests.isIncludeAndroidResources = true
    // Compile the same internal draft and initializer, without publishing a test API.
    sourceSets["main"].kotlin.directories.addAll(
        listOf("../src/main/kotlin", "../../android/src/main/java"),
    )
}

dependencyLocking {
    lockAllConfigurations()
    // The comparison build omits one locked module; remaining versions stay pinned.
    if (disableNativeCrash) lockMode = LockMode.LENIENT
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(platform("io.opentelemetry.android:opentelemetry-android-bom:1.9.0-session57-e13e9d89-alpha-SNAPSHOT"))
    implementation("io.opentelemetry.android:android-agent:1.9.0-session57-e13e9d89-SNAPSHOT")
    implementation("io.opentelemetry.android:session:1.9.0-session57-e13e9d89-SNAPSHOT")
    if (!disableNativeCrash) {
        implementation("io.opentelemetry.android.instrumentation:native-crash:1.9.0-session57-e13e9d89-alpha-SNAPSHOT")
    }
}
