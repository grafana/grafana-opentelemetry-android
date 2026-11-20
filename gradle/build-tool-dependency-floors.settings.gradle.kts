// Applies the minimum versions in build-tool-dependency-floors.txt to the plugin classpath and
// AGP's UTP configurations. Neither is published, so this never changes what consumers get.

fun floors(): Map<String, String> = settings.rootDir.resolve("gradle/build-tool-dependency-floors.txt")
    .readLines()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { it.substringBeforeLast(":") to it.substringAfterLast(":") }

fun isOlder(version: String, floor: String): Boolean {
    fun parts(v: String) = v.split(Regex("[^0-9]+")).filter(String::isNotEmpty).map(String::toInt)
    val a = parts(version)
    val b = parts(floor)
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (diff != 0) return diff < 0
    }
    return false
}

fun ResolutionStrategy.applyFloors(floors: Map<String, String>) = eachDependency {
    val version = requested.version ?: return@eachDependency
    val floor = floors["${requested.group}:${requested.name}"] ?: return@eachDependency
    if (isOlder(version, floor)) {
        useVersion(floor)
        because("Build-tool dependency floor for known CVEs")
    }
}

gradle.beforeProject {
    val floors = floors()
    buildscript.configurations.configureEach { resolutionStrategy.applyFloors(floors) }
    configurations.matching { it.name.startsWith("_internal-unified-test-platform") }
        .configureEach { resolutionStrategy.applyFloors(floors) }
}
