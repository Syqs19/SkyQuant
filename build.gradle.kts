plugins {
    // Automatically applies the correct Loom variant based on the MC version
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("com.gradleup.shadow")
}

// DO NOT set group = ...! It's handled by loom-back-compat/mod.group in stonecutter.properties.toml
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

// Used for publishing to Modrinth/CurseForge
val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    // MoulConfig (config screen library used by SkyHanni/Firmament, see docs/RESEARCH.md)
    maven("https://maven.notenoughupdates.org/releases/")
}

// Dependencies bundled directly into our own jar (relocated in tasks.shadowJar below)
// instead of being loaded as separate mods, so players don't need to install them
// separately. See docs/RESEARCH.md section 4 for why this is needed for MoulConfig.
val shadowImpl: Configuration = configurations.create("shadowImpl") {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    // Only downloads the required Fabric API modules instead of the whole package
    // https://github.com/FabricMC/fabric
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang Mappings on obfuscated versions (not needed from 26.1 onward)
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("deps.fabric_language_kotlin")}")
    fapi(
        "fabric-lifecycle-events-v1",
        "fabric-resource-loader-v0",
        "fabric-content-registries-v0",
        "fabric-registry-sync-v0",
        "fabric-message-api-v1",
        "fabric-command-api-v2",
        // Keyboard and render hooks on any open screen, used to open the bazaar graph from
        // whatever item the cursor is over.
        "fabric-screen-api-v1",
        // HUD render hook, for the pinned-price overlay.
        "fabric-rendering-v1"
    )

    // The full Fabric API, in the test client only - never compiled against, never shipped.
    //
    // The modules above are what SkyQuant itself needs, and each registers under its own id
    // ("fabric-lifecycle-events-v1" and so on). Nothing among them is called "fabric-api": that is
    // the id of the umbrella mod. Development-only mods dropped in run/mods routinely declare a
    // hard dependency on that umbrella, and the loader then refuses to start with "requires
    // fabric-api, which is missing" even though every module such a mod actually calls is present.
    //
    // modRuntimeOnly is what keeps this from undoing the choice above: it puts the jar in the
    // test client's mod folder without adding it to the compile classpath, so accidentally
    // importing an unlisted module is still a compile error, and the published jar and its
    // declared dependencies are byte-for-byte what they were.
    val fabricApiVersion: String = sc.properties["deps.fabric_api"]
    modRuntimeOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Config screen library. No per-patch build exists (only "modern-26.1"), but unlike
    // Fabric API it doesn't use mixins into Minecraft's own classes, so it's much less
    // sensitive to the kind of patch-to-patch breakage we hit with Fabric API earlier.
    shadowImpl("org.notenoughupdates.moulconfig:modern-26.1:4.7.2") {
        exclude("org.jetbrains.kotlin")
        exclude("org.jetbrains.kotlinx")
    }

    // Mod Menu: only used to add a "Config" button to our entry in the mod list.
    // Compile-time only (not modImplementation) on purpose: Mod Menu is an optional
    // soft dependency, declared via the "modmenu" entrypoint in fabric.mod.json, not
    // a hard runtime requirement. If a player doesn't have Mod Menu installed, nothing
    // breaks - they just won't see the "Config" button (can still use /skyquant config).
    implementation("maven.modrinth:modmenu:18.0.0")

    // Unit tests, for the logic that doesn't need a running game: number formatting, config
    // migration, rankings, curve maths. Anything touching Minecraft classes can't be tested
    // this way (they aren't loaded outside the game), which is a reason to keep that logic
    // separate from the drawing in the first place.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
    accessWidenerPath = sc.process(
        rootProject.file("src/main/resources/skyquant.ct"),
        "build/processed.ct"
    )

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1") // Assigns names to lambdas: useful for mixins
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run") // "run" directory shared between versions
        jvmArguments.add("-Dmixin.debug.export=true") // Exports transformed mixin classes, useful for debugging
        jvmArguments.add("-Ddevauth.enabled=1") // Enables DevAuth Neo: allows logging in with a real Microsoft account in the test client
        // Fixed account name: without this, DevAuth Neo asks for the name via console before
        // opening the login browser, but the Gradle task console is not interactive
        // and the request fails immediately ("Nothing provided!"). With the name already set
        // it goes straight to the embedded login browser.
        jvmArguments.add("-Ddevauth.account=syqs")
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

kotlin {
    jvmToolchain(requiredJava.majorVersion.toInt())
}

tasks {
    test {
        useJUnitPlatform()
        // Failures are printed in full: the point of these is to say what broke without having
        // to rerun anything by hand.
        testLogging {
            events("failed")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    // The plain jar (without bundled dependencies) is only an intermediate step now;
    // shadowJar below is the real distributable, same approach as SkyHanni.
    named<Jar>("jar") {
        archiveClassifier.set("slim")
    }

    shadowJar {
        archiveClassifier.set("")
        // INCLUDE (not the Shadow default EXCLUDE): required for mergeServiceFiles() below
        // to actually merge META-INF/services files instead of silently dropping them.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        // Don't auto-bundle a whole configuration (that would also pull in Minecraft/Fabric
        // API/Kotlin from the runtime classpath). Instead add shadowImpl's jar(s) manually
        // below, so we can strip the one file that actually collides with our own.
        configurations = emptyList()
        from(shadowImpl.map { zipTree(it) }) {
            // MoulConfig bundles LibNinePatch internally, which ships its own
            // fabric.mod.json - excluded here so it can't collide with (or override) ours.
            exclude("fabric.mod.json")
            // ...and its own bare "LICENSE" (MPL-2.0, LibNinePatch's). At the root of our jar
            // that filename reads as *SkyQuant's* licence, contradicting the GPL-3.0-or-later
            // declared in fabric.mod.json - so the one file a reader would open to check what
            // they may do with this jar told them the wrong answer. Re-added under a name that
            // says whose it is, by the LICENSE-MoulConfig.txt rule below.
            exclude("LICENSE")
        }

        // The bundled dependency's licence, kept but attributed. Dropping it outright would be
        // the easy fix and the wrong one: MPL-2.0 requires the notice to travel with the code,
        // and the code is still in here.
        from(shadowImpl.map { zipTree(it) }) {
            include("LICENSE")
            rename { "LICENSE-MoulConfig.txt" }
        }

        // SkyQuant's own licence, which was never in the jar at all - the name was taken by the
        // dependency's copy. GPL-3.0 asks that the terms accompany the distributed work.
        from(rootProject.file("LICENSE")) {
            rename { "LICENSE-SkyQuant.txt" }
        }
        exclude("META-INF/versions/**")
        exclude("META-INF/*.kotlin_module")
        mergeServiceFiles()
        // Renames MoulConfig's packages inside our jar so they don't collide with a
        // (possibly different, incompatible) copy bundled by another mod like
        // SkyHanni or Firmament, which do the same relocation on their end.
        relocate("io.github.notenoughupdates.moulconfig", "dev.syqs.skyquant.deps.moulconfig")
    }

    assemble {
        dependsOn(shadowJar)
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        from(shadowJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
