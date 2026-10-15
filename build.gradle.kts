buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1" apply false
}

allprojects {
    group = "com.grafana.opentelemetry"
    version = "0.0.0-dev"
    dependencyLocking {
        lockAllConfigurations()
    }
}
