buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1" apply false
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
}

allprojects {
    group = "com.grafana.opentelemetry"
    version = rootProject.file("version.txt").readText().trim()
    dependencyLocking {
        lockAllConfigurations()
    }
}
