plugins {
    id("java")
    id("idea")
    id("net.fabricmc.fabric-loom") version("1.17.20")
}

evaluationDependsOn(":common")

val MINECRAFT_VERSION: String by rootProject.extra
val PARCHMENT_VERSION: String? by rootProject.extra
val FABRIC_LOADER_VERSION: String by rootProject.extra
val FABRIC_API_VERSION: String by rootProject.extra
val SODIUM_DEPENDENCY_FABRIC: Any by rootProject.extra
val MOD_VERSION: String by rootProject.extra

repositories {
    mavenLocal()
    maven("https://maven.caffeinemc.net/releases")
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
    maven {
        name = "caffeinemcRepositoryReleases"
        url = uri("https://maven.caffeinemc.net/releases")
    }
}

base {
    archivesName.set("iris-fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:${MINECRAFT_VERSION}")

    implementation("net.fabricmc:fabric-loader:$FABRIC_LOADER_VERSION")

    fun addRuntimeFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        runtimeOnly(module)
    }

    fun addCompileFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        compileOnly(module)
    }

    fun addEmbeddedFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        implementation(module)
        include(module)
    }

    fun implementAndInclude(name: String) {
        implementation(name)
        include(name)
    }

    // Fabric API modules
    addEmbeddedFabricModule("fabric-api-base")
    addEmbeddedFabricModule("fabric-key-mapping-api-v1")
    addRuntimeFabricModule("fabric-block-getter-api-v2")
    addRuntimeFabricModule("fabric-rendering-fluids-v1")
    addRuntimeFabricModule("fabric-resource-loader-v0")
    addRuntimeFabricModule("fabric-lifecycle-events-v1")
    addRuntimeFabricModule("fabric-renderer-api-v1")
    addCompileFabricModule("fabric-renderer-api-v1")

    implementation(SODIUM_DEPENDENCY_FABRIC)
    implementAndInclude("org.antlr:antlr4-runtime:4.13.1")
    implementAndInclude("io.github.douira:glsl-transformer:3.0.0-pre3")
    implementAndInclude("org.anarres:jcpp:1.4.14")

    implementation(project(":common"))
    implementation(project(path = ":common", configuration = "vendoredJar"))
    implementation(project(path = ":common", configuration = "apiJar"))
    compileOnly(project(path = ":common", configuration = "headersJar"))

    compileOnly(files(rootDir.resolve("DHApi.jar")))
}

tasks.named("compileTestJava").configure {
    enabled = false
}

tasks.named("test").configure {
    enabled = false
}

loom {
    if (project(":common").file("src/main/resources/iris.accesswidener").exists())
        accessWidenerPath.set(project(":common").file("src/main/resources/iris.accesswidener"))

    @Suppress("UnstableApiUsage")
    mixin {
        defaultRefmapName.set("iris-fabric.refmap.json")
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
            // Vulkan port testing: -PtestWorld=<name> joins (and on first use creates) that world;
            // -PdlssDebug=spin|walk turns on Iris DLSS' camera motion + frame capture (run/iris-dlss/captures).
            if (project.hasProperty("testWorld")) {
                vmArg("-Diris.vulkanTest.world=" + project.property("testWorld").toString())
            }
            // -PtestCommands="summon pig ~ ~ ~3;time set 13000" runs commands after joining; -PtestPitch=-30 tilts the camera.
            if (project.hasProperty("testCommands")) {
                vmArg("-Diris.vulkanTest.commands=" + project.property("testCommands").toString())
            }
            if (project.hasProperty("testTicks")) {
                vmArg("-Diris.vulkanTest.captureTicks=" + project.property("testTicks").toString())
            }
            if (project.hasProperty("testPitch")) {
                vmArg("-Diris.vulkanTest.pitch=" + project.property("testPitch").toString())
            }
            // -PdlssBenchmark cycles Iris DLSS' modes once in the world and logs fps; -PwindowSize=1920x1080.
            if (project.hasProperty("dlssBenchmark")) {
                vmArg("-Diris-dlss.benchmark=true")
                vmArg("-Diris.vulkanTest.captureTicks=2600")
            }
            // -PdlssForeignAcquire: the game's swapchain image is acquired outside VulkanGpuSurface, like Lunar Client does.
            if (project.hasProperty("dlssForeignAcquire")) {
                vmArg("-Diris-dlss.debugForeignAcquire=true")
            }
            if (project.hasProperty("windowSize")) {
                val size = project.property("windowSize").toString().split("x")
                programArgs("--width", size[0], "--height", size[1])
            }
            if (project.hasProperty("dlssDebug")) {
                val mode = project.property("dlssDebug").toString()
                vmArg(if (mode == "walk") "-Diris-dlss.debugWalk=true" else "-Diris-dlss.debugSpin=1.5")
                vmArg("-Diris-dlss.captureAt=300")
                vmArg("-Diris-dlss.debugLog=true")
            }
           // environmentVariable("SDL_VIDEODRIVER", "x11")
           // vmArgs("-Dmixin.debug.export=true")
           // vmArg("-XX:+AllowEnhancedClassRedefinition")
        }
    }
}

tasks {
    processResources {
        from(project.project(":common").sourceSets.main.get().resources)
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(zipTree(project.project(":common").tasks.jar.get().archiveFile))

        manifest.attributes["Main-Class"] = "net.irisshaders.iris.LaunchWarn"
    }

    jar.get().destinationDirectory = rootDir.resolve("build").resolve("libs")
}
