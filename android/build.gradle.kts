import com.android.build.api.artifact.SingleArtifact
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish.base")
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
    testImplementation(libs.opentelemetry.android.semconv)
}

val apiValidatorClasspath by configurations.creating
dependencies {
    apiValidatorClasspath("org.ow2.asm:asm:9.6")
    apiValidatorClasspath("org.ow2.asm:asm-tree:9.6")
    apiValidatorClasspath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
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

// Kotlin API documentation lives in the maintained module guide.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from("README.md")
}

val centralPublishing = providers.gradleProperty("publishToCentral").orNull == "true"
mavenPublishing {
    if (centralPublishing) {
        check(version.toString().matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
            "Maven Central publishing requires a stable release version in version.txt"
        }
        publishToMavenCentral(automaticRelease = false)
    }
    if (centralPublishing || providers.gradleProperty("signPublication").orNull == "true") {
        signAllPublications()
    }
}

afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "android"
        artifact(javadocJar)
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
            developers {
                developer {
                    id.set("mobile-o11y-engineers")
                    name.set("Grafana Mobile Observability")
                    organization.set("Grafana Labs")
                    organizationUrl.set("https://grafana.com")
                }
            }
            scm {
                url.set("https://github.com/grafana/grafana-opentelemetry-android")
                connection.set("scm:git:https://github.com/grafana/grafana-opentelemetry-android.git")
            }
        }
    }
}
