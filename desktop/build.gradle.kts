import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "com.ars3nb.volta"

/**
 * The version Windows sees. An installer only takes MAJOR.MINOR.BUILD made of numbers
 * (MAJOR and MINOR up to 255, BUILD up to 65535), so a tag like v1.2.0-beta becomes 1.2.0.
 * A v… tag names it (VOLTA_VERSION_NAME, as in the Android build); any other CI build is
 * 1.1.<run number>, the same numbering the APKs use; a local build is 1.1.0.
 */
val voltaVersion: String = run {
    val fromTag = System.getenv("VOLTA_VERSION_NAME")?.trim().orEmpty()
    val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    val raw = fromTag.ifBlank { if (run != null) "1.1.$run" else "1.1.0" }
    val parts = Regex("\\d+").findAll(raw.substringBefore('-')).map { it.value.toInt() }.toMutableList()
    while (parts.size < 3) parts += 0
    val major = parts[0].coerceIn(1, 255)
    val minor = parts[1].coerceIn(0, 255)
    val build = parts[2].coerceIn(0, 65535)
    "$major.$minor.$build"
}
version = voltaVersion

/** What the in-app updater compares: 100 + the CI run number, as the APKs count; 0 by hand. */
val voltaVersionCode: Int = System.getenv("VOLTA_VERSION_CODE")?.trim()?.toIntOrNull() ?: 0

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.google.code.gson:gson:2.13.1")
    // Windows itself: the user's proxy settings (WinINet), the registry, the window frame.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation("junit:junit:4.13.2")
}

/*
 * The Android app's texts (app/src/main/res/values…/strings….xml) and the desktop's own
 * (src/main/res), copied into the jar as volta-res/<folder>/<file> with an index, and
 * their names as constants in R (R.string.home_title…), so screens shared with Android
 * read the very same words in every language. See i18n/Res.kt.
 */
val androidResDir = layout.projectDirectory.dir("../app/src/main/res")
val desktopResDir = layout.projectDirectory.dir("src/main/res")

val voltaResources by tasks.registering {
    val sources = listOf(androidResDir.asFile, desktopResDir.asFile)
    inputs.files(sources.map { root -> fileTree(root) { include("values*/strings*.xml") } })
    val out = layout.buildDirectory.dir("generated/volta-res/resources")
    outputs.dir(out)
    doLast {
        val target = out.get().asFile.resolve("volta-res")
        target.deleteRecursively()
        target.mkdirs()
        val index = StringBuilder()
        sources.forEachIndexed { n, root ->
            root.listFiles { f -> f.isDirectory && f.name.startsWith("values") }?.sortedBy { it.name }?.forEach { folder ->
                folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
                    ?.sortedBy { it.name }
                    ?.forEach { file ->
                        // Android's files first in each folder: they win a name both have.
                        val name = (if (n == 0) "a-" else "d-") + file.name
                        target.resolve(folder.name).mkdirs()
                        file.copyTo(target.resolve("${folder.name}/$name"), overwrite = true)
                        index.append(folder.name).append('/').append(name).append('\n')
                    }
            }
        }
        target.resolve("index.txt").writeText(index.toString())
    }
}

val voltaR by tasks.registering {
    val defaults = listOf(androidResDir.dir("values").asFile, desktopResDir.dir("values").asFile)
    inputs.files(defaults.map { root -> fileTree(root) { include("strings*.xml") } })
    val out = layout.buildDirectory.dir("generated/volta-res/kotlin")
    outputs.dir(out)
    doLast {
        val kinds = linkedMapOf("string" to sortedSetOf<String>(), "plurals" to sortedSetOf(), "array" to sortedSetOf())
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        defaults.flatMap { it.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }?.toList().orEmpty() }
            .forEach { file ->
                val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
                    val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                    when (el.tagName) {
                        "string" -> kinds.getValue("string") += name
                        "plurals" -> kinds.getValue("plurals") += name
                        "string-array" -> kinds.getValue("array") += name
                    }
                }
            }
        val text = buildString {
            append("// Generated by the voltaR task from the Android and desktop strings*.xml.\n")
            append("package com.ars3nb.volta.desktop.i18n\n\n")
            append("/** Text keys, named as in the Android app (R.string.home_title and so on). */\n")
            append("@Suppress(\"ClassName\", \"unused\", \"ObjectPropertyName\")\n")
            append("object R {\n")
            kinds.forEach { (kind, names) ->
                append("    object $kind {\n")
                names.forEach { append("        const val $it: String = \"$it\"\n") }
                append("    }\n")
            }
            append("}\n")
        }
        val file = out.get().asFile.resolve("com/ars3nb/volta/desktop/i18n/R.kt")
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}

sourceSets["main"].resources.srcDir(voltaResources)
kotlin.sourceSets["main"].kotlin.srcDir(voltaR)

tasks.test {
    useJUnit()
    // CoreConfigSamplesTest writes the configs Volta builds here, for `xray run -test`.
    System.getenv("VOLTA_CONFIG_DUMP")?.let { environment("VOLTA_CONFIG_DUMP", it) }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * The Xray core and its geo lists ship inside the app (resources/windows-x64, filled in by
 * CI or by hand, see README.md). A package without them would install fine and then never
 * connect, so building one without them fails here instead.
 */
val verifyCore by tasks.registering {
    val dir = layout.projectDirectory.dir("resources/windows-x64")
    doLast {
        val missing = listOf("xray.exe", "geoip.dat", "geosite.dat")
            .filter { !dir.file(it).asFile.isFile || dir.file(it).asFile.length() < 1024 }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing in desktop/resources/windows-x64: ${missing.joinToString()}. " +
                    "Put the Xray core there first (see desktop/README.md)."
            )
        }
    }
}

tasks.matching {
    it.name in setOf("createDistributable", "packageExe", "packageMsi", "packageDistributionForCurrentOS")
}.configureEach { dependsOn(verifyCore) }

compose.desktop {
    application {
        mainClass = "com.ars3nb.volta.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx384m",
            "-Dfile.encoding=UTF-8",
            "-Dvolta.version=$voltaVersion",
            "-Dvolta.versionCode=$voltaVersionCode",
            // Volta's own requests go straight out, never through the proxy it sets.
            "-Djava.net.useSystemProxies=false"
        )

        nativeDistributions {
            // TEMPORARY (investigation): an MSI too when asked.
            if (System.getenv("VOLTA_MSI") == "1") targetFormats(TargetFormat.Exe, TargetFormat.Msi) else targetFormats(TargetFormat.Exe)
            packageName = "Volta"
            packageVersion = voltaVersion
            description = "Volta VPN"
            vendor = "Volta"
            copyright = "Volta"

            // xray.exe, geoip.dat and geosite.dat: next to the app, found at run time
            // through the compose.application.resources.dir property.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // The Java runtime is cut down to these modules (jlink).
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.xml",
                "java.sql",
                "jdk.accessibility",
                "jdk.charsets",
                "jdk.crypto.ec",
                // The update self-check serves a release site on this machine.
                "jdk.httpserver",
                "jdk.localedata",
                "jdk.unsupported",
                "jdk.zipfs"
            )

            windows {
                iconFile.set(project.file("icons/volta.ico"))
                menuGroup = "Volta"
                menu = true
                shortcut = true
                dirChooser = true
                // No admin rights needed: Volta changes only the current user's settings.
                perUserInstall = true
                upgradeUuid = "9ee47d8c-27e3-41a7-bda1-8fa20a3d124c"
            }
        }
    }
}
