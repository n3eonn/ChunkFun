plugins {
    java
}

group = "dev.chunkfun"
version = "1.0.0"
description = "ChunkFun - single-chunk survival minigame with rotating dimensions"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API for Minecraft 26.2 (provided by the server at runtime).
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
}

java {
    // Minecraft 26.x requires Java 25.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
