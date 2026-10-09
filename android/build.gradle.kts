import com.android.build.api.artifact.SingleArtifact
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.grafana.opentelemetry.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(platform(libs.opentelemetry.android.bom))
    api(libs.opentelemetry.android.agent)

    testImplementation(libs.junit)
    testImplementation(libs.opentelemetry.android.core)
}

val apiValidatorClasspath by configurations.creating
dependencies {
    apiValidatorClasspath("org.ow2.asm:asm:9.6")
    apiValidatorClasspath("org.ow2.asm:asm-tree:9.6")
    apiValidatorClasspath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.10")
}

// AGP's built-in Kotlin plugin is not auto-detected by the API validator.
// Read the actual published classes instead of relying on Kotlin plugin callbacks.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val extractApiClasses = tasks.register<Sync>("extractApiClasses") {
        from(variant.artifacts.get(SingleArtifact.AAR).map { zipTree(it) }) {
            include("classes.jar")
        }
        into(layout.buildDirectory.dir("api/classes"))
    }
    val apiBuild = tasks.register<KotlinApiBuildTask>("apiBuild") {
        dependsOn(extractApiClasses)
        inputJar.set(layout.buildDirectory.file("api/classes/classes.jar"))
        runtimeClasspath.from(apiValidatorClasspath)
        outputApiFile.set(layout.buildDirectory.file("api/android.api"))
    }
    val apiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
        dependsOn(apiBuild)
        projectApiFile.set(layout.projectDirectory.file("api/android.api"))
        generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
    }
    tasks.register<Copy>("apiDump") {
        from(apiBuild.flatMap { it.outputApiFile })
        into(layout.projectDirectory.dir("api"))
    }
    tasks.named("check") {
        dependsOn(apiCheck)
    }
}

publishing {
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repository").get().asFile.toURI()
        }
    }
}

afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "android"
        pom {
            name.set("Grafana OpenTelemetry Android")
            description.set("Grafana configuration for the upstream OpenTelemetry Android SDK")
            url.set("https://github.com/grafana/grafana-opentelemetry-android")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            scm {
                url.set("https://github.com/grafana/grafana-opentelemetry-android")
                connection.set("scm:git:https://github.com/grafana/grafana-opentelemetry-android.git")
            }
        }
    }
}
