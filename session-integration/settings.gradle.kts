pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "localSessionDraft"
                    url = uri("build/upstream-repository")
                }
            }
            filter { includeGroupByRegex("io\\.opentelemetry\\.android(\\..*)?") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "android-session-integration-draft"
include(":probe")
