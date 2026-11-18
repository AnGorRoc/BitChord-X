import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Helpers for the native builds and the Windows start-up archive in `desktopApp/build.gradle.kts`.
 *
 * It lives here, and not in the script, because the native tasks call it from `onlyIf` and
 * `doLast`. A function declared in a build script keeps a reference to the script object, which the
 * configuration cache cannot restore, so every build that included those tasks failed once the
 * cache was reused. A class compiled in buildSrc has no such reference.
 */
object NativeTools {
    private val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    /**
     * Whether a tool on the PATH can actually be started.
     *
     * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
     * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
     * missing-tool path this build already handles.
     */
    fun runs(tool: File): Boolean = runCatching {
        val process = ProcessBuilder(tool.absolutePath, "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)

    /**
     * An executable's absolute path, from the PATH this build was invoked with.
     *
     * Resolved here rather than left to the child process. Gradle runs external
     * commands from the daemon, whose own PATH is whatever it was started with, so
     * a daemon reused from a shell that had no cmake cannot find the one the
     * current shell does — it fails with a bare "No such file or directory" while
     * `cmake --version` works fine in the terminal beside it.
     */
    fun findOnPath(tool: String): File? {
        // Windows keeps the extension on the file and off the command line, so a bare name matches
        // nothing on disk: PATHEXT is the list a shell would have tried.
        val names = if (!hostIsWindows) {
            listOf(tool)
        } else {
            val extensions = System.getenv("PATHEXT").orEmpty()
                .ifBlank { ".COM;.EXE;.BAT;.CMD" }
                .split(';')
                .filter(String::isNotBlank)
            listOf(tool) + extensions.map { tool + it.lowercase() }
        }
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .flatMap { directory -> names.map { File(directory, it) } }
            // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
            .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
    }

    fun cmakeBinary(): String = findOnPath("cmake")?.absolutePath ?: "cmake"

    fun buildStartupArchive(
        image: File,
        probe: File,
        work: File,
        classList: File,
        logger: org.gradle.api.logging.Logger,
    ) {
        val app = File(image, "app")
        val classPath = File(app, "BitChord.cfg").readLines()
            .filter { it.startsWith("app.classpath=") }
            .map { it.removePrefix("app.classpath=").replace("\$APPDIR", app.absolutePath) }
        check(classPath.isNotEmpty()) { "startupArchive: no class path in ${File(app, "BitChord.cfg")}" }
        val launcher = File(probe, "bin/java.exe")
        check(launcher.isFile) { "startupArchive: the runtime probe is missing; createRuntimeImage has to run first" }

        work.deleteRecursively()
        work.mkdirs()
        // The JVM refuses a whole list over one line longer than 4096 characters (a few huge Compose
        // lambdas produce them), so those are left out.
        val list = File(work, "startup.classlist")
        list.writeText(classList.readLines().filter { it.length < 4000 }.joinToString("\n") + "\n")

        val resources = File(app, "resources").apply { mkdirs() }
        val archive = File(resources, "startup.jsa")
        archive.delete()
        val log = File(work, "dump.log")
        val process = ProcessBuilder(
            launcher.absolutePath,
            "-Xshare:dump",
            "-Xlog:disable",
            "-XX:SharedClassListFile=${list.absolutePath}",
            "-XX:SharedArchiveFile=${archive.absolutePath}",
            "-cp", classPath.joinToString(File.pathSeparator),
        ).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            archive.delete()
            logger.warn("startupArchive: the dump took over 10 minutes and was stopped; shipping without it.")
        } else if (process.exitValue() != 0 || !archive.isFile) {
            archive.delete()
            logger.warn("startupArchive: the dump failed (${log.absolutePath}); shipping without it.")
        } else {
            // `-Xshare:on` makes the JVM fail where it would otherwise quietly run without the archive,
            // so a class path or runtime that does not fit it is caught here rather than on a user's PC.
            val check = ProcessBuilder(
                launcher.absolutePath, "-Xshare:on", "-Xlog:disable",
                "-XX:SharedArchiveFile=${archive.absolutePath}",
                "-cp", classPath.joinToString(File.pathSeparator), "-version",
            ).redirectErrorStream(true).redirectOutput(File(work, "check.log")).start()
            if (!check.waitFor(2, TimeUnit.MINUTES) || check.exitValue() != 0) {
                check.destroyForcibly()
                archive.delete()
                logger.warn("startupArchive: the JVM does not accept the archive (${File(work, "check.log").absolutePath}); shipping without it.")
            } else {
                logger.lifecycle("startupArchive: ${archive.length() / (1024 * 1024)} MB over ${classPath.size} jars, accepted by the JVM")
            }
        }
    }
}
