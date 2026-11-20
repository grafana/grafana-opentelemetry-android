// Adds the minimum versions in build-tool-dependency-floors.txt as constraints on the plugin
// classpath and AGP's UTP configurations. Neither is published, so this never changes what
// consumers get. Gradle still picks a newer version when one is requested.

fun floors(): List<String> = settings.rootDir.resolve("gradle/build-tool-dependency-floors.txt")
    .readLines()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }

gradle.beforeProject {
    val floors = floors()
    floors.forEach { buildscript.dependencies.constraints.add("classpath", it) }
    configurations.matching { it.name.startsWith("_internal-unified-test-platform") }.configureEach {
        floors.forEach { dependencyConstraints.add(project.dependencies.constraints.create(it)) }
    }
}
