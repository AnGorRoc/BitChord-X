import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import NativeTools.buildStartupArchive
import NativeTools.cmakeBinary
import NativeTools.findOnPath
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("bitchordVersionName").orNull
    ?: providers.gradleProperty("bitchord.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: (rootProject.extra["bitchordVersionName"] as? String ?: "1.9.3")

/** Which platform this build is *for*, which is the host unless told otherwise. */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val hostIsMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
val targetOs: String = (providers.gradleProperty("bitchord.target").orNull ?: when {
    hostIsWindows -> "windows"
    hostIsLinux -> "linux"
    hostIsMac -> "macos"
    else -> error("BitChord desktop supports Linux, Windows, and macOS only")
}).lowercase()

val hostArch = System.getProperty("os.arch").lowercase()
val isArm64 = hostArch == "aarch64" || hostArch == "arm64"

// Windows installer metadata requires MAJOR.MINOR.BUILD even though the app's public version is
// intentionally displayed without a patch number (1.7 rather than 1.7.0).
// The build number is the desktop version code; it also keeps "-beta1" out of the numeric installer version.
val desktopVersionCode = 38
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"

// FFmpeg decodes audio; see DesktopAudioDecoder.
val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"
val nativeClassifier = when (targetOs) {
    "windows" -> "windows-x86_64"
    "linux" -> "linux-x86_64"
    "macos" -> if (isArm64) "macosx-arm64" else "macosx-x86_64"
    else -> error("BitChord desktop supports Linux, Windows, and macOS only")
}
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")

// Last.fm signs every request with these, so signing in from inside the app
// needs them at hand. Supplied locally and never committed, as on Android.
// Same default as the phone app (app/build.gradle.kts): this fork's own party server. When it is
// down the client falls back to the original BitChord official server on its own.
val listenTogetherServer = localProperty("LISTEN_TOGETHER_SERVER")
    .ifBlank { "https://bitchord.pymp.net" }
val lastfmApiKey = localProperty("LASTFM_API_KEY")
val lastfmSecret = localProperty("LASTFM_SECRET")

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    // Already on the classpath through the NewPipe extractor.
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.zxing:core:3.5.3")

    implementation(project(":shared"))
    // Its own copy of the player and pages; the phone and TV keep :sharedUi.
    implementation(project(":desktopUi"))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = when (targetOs) {
        "windows" -> "win"
        "macos" -> if (isArm64) "mac-aarch64" else "mac"
        else -> "linux"
    }
    listOf("base", "graphics", "controls", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(compose.materialIconsExtended)
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(
        when (targetOs) {
            "windows" -> compose.desktop.windows_x64
            "macos" -> if (isArm64) compose.desktop.macos_arm64 else compose.desktop.macos_x64
            else -> compose.desktop.linux_x64
        }
    )

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    // Listen Together speaks over a WebSocket; the Discord gateway already uses the same engine.
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // The shared module only compiles against org.json (Android has its own); the JVM needs the real one.
    implementation("org.json:json:20240303")

    // Browsers keep their cookies in SQLite, and importing a session from one is how this app signs
    // in — see DesktopBrowserCookies.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Chromium protects its Windows master key with DPAPI. JNA's platform helpers let the app
    // unwrap that key in the same Windows user session without invoking a shell or exposing it.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Linux desktop media controls use the standard session-bus MPRIS interfaces.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs")

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val os = targetOs
    // Not `providers` in doLast: bare, that resolves to the script, which the cache cannot restore.
    val execProviders = providers
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("app/src/main/cpp/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findOnPath("cmake")
        if (cmake == null) {
            logger.lifecycle(
                "no usable cmake — building without the Automix analyser. If cmake is installed, " +
                    "a Gradle daemon started before it was on PATH may be in use: ./gradlew --stop",
            )
        }
        val compilerName = if (crossing) "x86_64-w64-mingw32-g++" else "g++"
        val compiler = os != "windows" || findOnPath(compilerName) != null
        val ninja = os != "windows" || findOnPath("ninja") != null
        if (cmake != null && (!compiler || !ninja)) {
            logger.lifecycle(
                "$compilerName or Ninja not found — the Windows build will have no Automix analyser. " +
                    "Install a MinGW C++ compiler and Ninja, then rebuild.",
            )
        }
        cmake != null && compiler && ninja
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (os == "windows") {
            findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
                ?: error("the Windows C++ compiler disappeared while building the analyser")
        } else {
            null
        }
        val ninja = if (os == "windows") {
            findOnPath("ninja") ?: error("Ninja disappeared while building the analyser")
        } else {
            null
        }
        try {
            execProviders.exec {
                commandLine(
                    buildList {
                        addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
                        if (os == "windows") {
                            // The host may not have Visual Studio/nmake. Select the MinGW toolchain we
                            // checked above and discard any generator cached by an earlier configure.
                            addAll(
                                listOf(
                                    "--fresh",
                                    "-G", "Ninja",
                                    "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}",
                                    "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}",
                                ),
                            )
                        }
                        add("-DCMAKE_BUILD_TYPE=Release")
                        if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                    },
                )
                environment("JAVA_HOME", javaHome)
            }.result.get().assertNormalExitValue()
            execProviders.exec {
                commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
                environment("JAVA_HOME", javaHome)
            }.result.get().assertNormalExitValue()
        } catch (e: Exception) {
            logger.warn("Automix analyser native build failed (${e.message}) — continuing build without the optional native analyser.")
        }
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val os = targetOs
    // Not `providers` in doLast: bare, that resolves to the script, which the cache cannot restore.
    val execProviders = providers
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        if (os != "windows") return@onlyIf false
        val cmake = findOnPath("cmake")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
        val ninja = findOnPath("ninja")
        if (cmake == null || compiler == null || ninja == null) {
            logger.lifecycle(
                "cmake, Ninja, or a MinGW C++ compiler is unavailable — the Windows build will " +
                    "fall back to Compose's frameless window behaviour.",
            )
        }
        cmake != null && compiler != null && ninja != null
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
            ?: error("MinGW C++ compiler disappeared while building the Windows frame")
        val ninja = findOnPath("ninja")
            ?: error("Ninja disappeared while building the Windows frame")
        try {
            execProviders.exec {
                commandLine(
                    buildList {
                        addAll(
                            listOf(
                                cmakeBinary(),
                                "-S", cmakeSource.absolutePath,
                                "-B", outputDir.absolutePath,
                                "-G", "Ninja",
                                "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                                "-DCMAKE_CXX_COMPILER=${compiler.absolutePath}",
                                "-DCMAKE_BUILD_TYPE=Release",
                            ),
                        )
                        if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                    },
                )
                environment("JAVA_HOME", javaHome)
            }.result.get().assertNormalExitValue()
            execProviders.exec {
                commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "-j", "4")
                environment("JAVA_HOME", javaHome)
            }.result.get().assertNormalExitValue()
        } catch (e: Exception) {
            logger.warn("Windows native window bridge build failed (${e.message}) — continuing build with default window behaviour.")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative)
    from(analysisNativeDir) {
        include("*.so", "*.dll", "*.dylib")
        into("native")
    }
    from(windowNativeDir) {
        include("*.dll")
        into("native")
    }
    // The Automix models, taken from the Android module rather than copied into this one.
    from(rootProject.file("app/src/main/assets")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
}

/** A Windows build that can be assembled here, since an installer cannot be. */
val windowsPortableDir = layout.buildDirectory.dir("windows-portable")

val windowsPortableAssemble by tasks.registering {
    group = "distribution"
    description = "Lays out a Windows-runnable folder: application jars, Windows natives, launcher."
    dependsOn(tasks.named("jar"))
    val stage = windowsPortableDir.map { it.dir("BitChord") }
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    val version = appVersion
    val moduleIndex = desktopModuleIndexUrl
    val lastfmKey = lastfmApiKey
    val lastfmSharedSecret = lastfmSecret
    val mainClass = composeMainClass
    // Declared, not implied.
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.property("version", version)
    inputs.property("moduleIndex", moduleIndex)
    inputs.property("lastfm", lastfmKey.isNotBlank())
    inputs.property("mainClass", mainClass)
    outputs.dir(stage)
    doLast {
        val target = stage.get().asFile
        val lib = File(target, "lib")
        lib.deleteRecursively()
        lib.mkdirs()

        // Named by coordinate rather than by file name.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier
            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            val name = if (group.isNullOrBlank()) artifact.file.name else "$group.${artifact.file.name}"
            artifact.file.copyTo(File(lib, name), overwrite = true)
        }
        appJar.get().asFile.copyTo(File(lib, "bitchord-app.jar"), overwrite = true)

        // `lib\*` rather than a spelled-out classpath: the wildcard is what keeps this working when
        // a dependency is added, and the JVM is what expands it.
        File(target, "BitChord.bat").writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("rem BitChord, portable. Needs a Java 21+ runtime on PATH or in JAVA_HOME.")
                appendLine("set \"HERE=%~dp0\"")
                appendLine("set \"RUNNER=java\"")
                appendLine("if defined JAVA_HOME set \"RUNNER=%JAVA_HOME%\\bin\\java\"")
                append("\"%RUNNER%\" -Dbitchord.version=$version")
                if (moduleIndex.isNotBlank()) append(" -Dbitchord.module.index=$moduleIndex")
                if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                    append(" -Dbitchord.lastfm.key=$lastfmKey -Dbitchord.lastfm.secret=$lastfmSharedSecret")
                }
                appendLine(" -cp \"%HERE%lib\\*\" $mainClass %*")
                appendLine("endlocal")
            },
        )

        // What "it builds" is not the same as "it runs", and the difference is exactly what shipped
        // a broken archive.
        val jars = lib.listFiles { file: File -> file.name.endsWith(".jar") }.orEmpty()
        fun holds(entry: String) = jars.any { jar ->
            ZipFile(jar).use { zip -> zip.getEntry(entry) != null }
        }
        val problems = buildList {
            if (!holds("com/music/bitchord/desktop/MainKt.class")) {
                add("the BitChord desktop entry point is missing")
            }
            if (!holds("androidx/compose/runtime/Composer.class")) {
                add("the Compose runtime is missing")
            }
            if (jars.none { it.name.contains("skiko-awt-runtime-windows") }) {
                add("the Windows Skiko renderer is missing")
            }
            if (jars.any { it.name.contains("skiko-awt-runtime-linux") }) {
                add("a Linux Skiko renderer is in a Windows archive")
            }
            if (!holds("org/bytedeco/ffmpeg/windows-x86_64/avformat.dll") &&
                jars.none { it.name.contains("ffmpeg") && it.name.contains("windows-x86_64") }
            ) {
                add("the Windows FFmpeg natives are missing")
            }
        }
        check(problems.isEmpty()) { "windowsPortable: " + problems.joinToString("; ") }

        val analyser = File(analysisNativeDir.get().asFile, "bitchord_analysis.dll").isFile
        logger.lifecycle(
            if (analyser) {
                "windowsPortable: verified — Compose runtime, Windows renderer, Windows FFmpeg, Automix analyser."
            } else {
                "windowsPortable: verified, but no bitchord_analysis.dll — Automix will not run in this archive. " +
                    "Rebuild with: nix-shell -p cmake pkgsCross.mingwW64.buildPackages.gcc"
            },
        )
    }
}

val windowsPortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable Windows folder for copying to a Windows machine."
    dependsOn(windowsPortableAssemble)
    from(windowsPortableDir)
    archiveFileName.set("BitChord-$appVersion-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doLast { logger.lifecycle("windowsPortable: ${archiveFile.get().asFile.absolutePath}") }
}

val composeMainClass = "com.music.bitchord.desktop.MainKt"

/**
 * The Windows build's class-data-sharing archive: every class the app loads on a start, parsed and
 * verified once at build time instead of on every launch. Opening the window takes about two
 * seconds less with it.
 *
 * The archive records the class path it was built against and the JVM only trusts it when the
 * installed jars are named and ordered the same way. jpackage names each jar after a checksum that
 * changes on every packaging run, so the archive cannot be predicted ahead of it: it is made from
 * the finished app image, reading the class path out of the image's own `BitChord.cfg`, and the
 * installer is then wrapped around that image rather than assembled again from the jars.
 */
val startupClassList = project.file("packaging/startup.classlist")
val startupArchiveEnabled = targetOs == "windows" && hostIsWindows
val runtimeProbeDir = layout.buildDirectory.dir("startup-cds/runtime-probe")
val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/BitChord")

if (startupArchiveEnabled && startupClassList.isFile) {
    tasks.matching { it.name == "createDistributable" }.configureEach {
        val probe = runtimeProbeDir
        val image = appImageDir
        val work = layout.buildDirectory.dir("startup-cds/work")
        inputs.file(startupClassList).withPropertyName("startupClassList")
        inputs.files(probe.map { it.file("lib/modules") }).withPropertyName("runtimeModules").optional()
        val classList = startupClassList
        doLast { buildStartupArchive(image.get().asFile, probe.get().asFile, work.get().asFile, classList, logger) }
    }
    // The installer is wrapped around the image the archive was made for. Naming the type is
    // enough: Compose passes `--app-image` instead of `--input` when the property is set.
    tasks.matching { it.name == "packageExe" }.configureEach {
        dependsOn("createDistributable")
        (this as org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask).appImage.set(appImageDir)
    }
}

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.asFile.absolutePath
        }.get()
        mainClass = composeMainClass
        jvmArgs("-Xmx512m")
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        // Only the quick compiler (C1), no profiling tier and no optimising one (C2). Measured on the
        // packaged app with a song playing: the window opens ~0.4 s sooner, the first 18 s cost ~40%
        // less CPU (21-29 s instead of 39-41 s), steady-state CPU and memory are no higher, and the
        // optimising compiler, where the JVM crash in ListeningStats was, never runs. What it gives up
        // is peak speed in long, hot numeric loops, which this app barely has: audio decoding,
        // rendering and image work are native. Drop this line to get the default tiered mode back.
        jvmArgs("-XX:TieredStopAtLevel=1")
        jvmArgs("-Dbitchord.version=$appVersion")
        if (startupArchiveEnabled && startupClassList.isFile) {
            // `-Xshare:auto` (the default) makes a missing or mismatched archive a silent fallback.
            // In a dev run `$APPDIR` is not expanded, so the file is simply not found.
            // Forward slashes: jpackage reads a backslash followed by a letter in a java-option as an escape.
            jvmArgs("-XX:SharedArchiveFile=\$APPDIR/resources/startup.jsa")
        }
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Dbitchord.module.index=$desktopModuleIndexUrl")
        }
        if (lastfmApiKey.isNotBlank() && lastfmSecret.isNotBlank()) {
            jvmArgs("-Dbitchord.lastfm.key=$lastfmApiKey", "-Dbitchord.lastfm.secret=$lastfmSecret")
        }
        if (listenTogetherServer.isNotBlank()) {
            jvmArgs("-Dbitchord.listentogether.server=$listenTogetherServer")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Exe,
                TargetFormat.Deb,
            )
            packageName = "BitChord"
            packageVersion = nativePackageVersion
            description = if (targetOs == "windows") "BitChord" else "Aesthetic YouTube Music client"
            vendor = "BitChord contributors"
            copyright = "Copyright © 2026 BitChord contributors"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                // Without this module the slim production runtime crashes in twkExecuteScript,
                // while development runs work because the full JDK already contains it.
                "jdk.jsobject",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            macOS {
                iconFile.set(project.file("packaging/icons/AppIcon.icns"))
                bundleID = "com.music.bitchord"
                appCategory = "public.app-category.music"
                dockName = "BitChord"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }

            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                menuGroup = "Audio"
                appCategory = "AudioVideo;Audio;Player"
                debMaintainer = "85984486+AbhiTheModder@users.noreply.github.com"
                rpmLicenseType = "GPL-3.0"
            }

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"
                menuGroup = "BitChord"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}

// jpackage's runtime image is made with `jlink --strip-native-commands`, which removes the java
// launcher, and with it the only way jlink has to generate the default class-data-sharing
// archive (`--generate-cds-archive`). Without one the JVM parses and verifies every JDK class on
// each start.
//
// The JVM only accepts an archive made against the very `lib/modules` it runs on (it compares the
// file's size), so one cannot be borrowed from the toolchain JDK: it is made here, by the
// toolchain's launcher run on a link-farm copy of the runtime image. The same probe is what
// [startupArchive] dumps the app's archive with.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    if (!startupArchiveEnabled) return@configureEach
    val toolchainHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }
    val probe = runtimeProbeDir
    outputs.dir(probe)
    doLast {
        val image = outputs.files.files.firstOrNull { File(it, "release").isFile } ?: return@doLast
        val into = probe.get().asFile
        into.deleteRecursively()
        // Hard links where the volume allows, so the 100 MB module image is not copied.
        image.walkTopDown().filter { it.isFile && it.name != "classes.jsa" }.forEach { file ->
            val target = File(into, file.relativeTo(image).path).apply { parentFile.mkdirs() }
            runCatching { Files.createLink(target.toPath(), file.toPath()) }
                .getOrElse { file.copyTo(target, overwrite = true) }
        }
        val launcher = File(toolchainHome.get(), "bin/java.exe")
        launcher.copyTo(File(into, "bin/java.exe"), overwrite = true)
        val log = File(into.parentFile, "runtime-dump.log")
        val process = ProcessBuilder(File(into, "bin/java.exe").absolutePath, "-Xshare:dump", "-Xlog:disable")
            .redirectErrorStream(true).redirectOutput(log).start()
        val archive = File(into, "bin/server/classes.jsa")
        if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0 || !archive.isFile) {
            process.destroyForcibly()
            logger.warn("createRuntimeImage: no default CDS archive could be made (${log.absolutePath}); the runtime starts without one.")
            return@doLast
        }
        archive.copyTo(File(image, "bin/server/classes.jsa"), overwrite = true)
        logger.lifecycle("createRuntimeImage: default CDS archive made for this runtime (${archive.length() / (1024 * 1024)} MB)")
    }
}
