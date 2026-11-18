import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tool lookup for the native builds in `desktopApp/build.gradle.kts`.
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
}
