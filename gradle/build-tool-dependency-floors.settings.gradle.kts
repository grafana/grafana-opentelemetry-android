// Minimum versions for vulnerable libraries that AGP pulls into the build itself: the plugin
// classpath and the Unified Test Platform (UTP) runner for instrumented tests. None of these
// configurations are part of the published library, so this never changes what consumers get.
// Remove an entry once AGP ships a fixed version on its own.
//
// Keep this script free of top-level properties: Android Lint crashes on them when it analyzes
// the consumer build's scripts.

fun floorFor(group: String, name: String, version: String): String? = when {
    // Bouncy Castle and Netty modules must stay on one aligned version.
    group == "org.bouncycastle" && name.endsWith("-jdk18on") -> "1.85"
    group == "io.netty" && version.startsWith("4.1.") -> "4.1.141.Final"
    else -> when ("$group:$name") {
        "com.google.guava:guava" -> "33.7.2-jre"
        "org.apache.commons:commons-lang3" -> "3.18.0"
        "org.apache.httpcomponents:httpclient" -> "4.5.14"
        "org.bitbucket.b_c:jose4j" -> "0.9.6"
        "org.jdom:jdom2" -> "2.0.6.1"
        else -> null
    }
}

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

fun ResolutionStrategy.applyFloors() = eachDependency {
    val version = requested.version ?: return@eachDependency
    val floor = floorFor(requested.group, requested.name, version) ?: return@eachDependency
    if (isOlder(version, floor)) {
        useVersion(floor)
        because("Build-tool dependency floor for known CVEs")
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { resolutionStrategy.applyFloors() }
    configurations.matching { it.name.startsWith("_internal-unified-test-platform") }
        .configureEach { resolutionStrategy.applyFloors() }
}
