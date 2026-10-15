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
                    url = uri("../build/test-repository")
                }
            }
            filter { includeGroup("com.grafana.opentelemetry") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "package-consumer"
