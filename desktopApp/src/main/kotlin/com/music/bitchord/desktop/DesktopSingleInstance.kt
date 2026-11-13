package com.music.bitchord.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets

/**
 * Manages single-instance execution for the desktop application.
 *
 * Ensures only one instance runs at a time. If a second instance is started,
 * it notifies the existing running instance (via local TCP loopback) to restore
 * and bring its window to the foreground, then exits.
 */
internal object DesktopSingleInstance {

    private const val LOCK_FILE_NAME = "bitchord.lock"
    private const val PORT_FILE_NAME = "bitchord.port"

    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var serverSocket: ServerSocket? = null

    /**
     * Attempts to acquire the single-instance lock.
     *
     * @return true if this process is the primary (first) instance;
     *         false if another instance is already running.
     */
    fun acquireOrNotify(): Boolean {
        val lockDir = File(System.getProperty("user.home"), ".bitchord").apply { mkdirs() }
        val lockFile = File(lockDir, LOCK_FILE_NAME)
        val portFile = File(lockDir, PORT_FILE_NAME)

        try {
            val randomAccessFile = RandomAccessFile(lockFile, "rw")
            val channel = randomAccessFile.channel
            val lock = channel.tryLock()

            if (lock != null) {
                // Primary instance acquired the lock
                lockChannel = channel
                fileLock = lock

                // Start local server to listen for reopen signals from subsequent instances
                startListener(portFile)
                return true
            }
        } catch (e: Exception) {
            DesktopTrackLog.log("SingleInstance lock check exception: ${e.message}")
        }

        // Another instance is holding the lock. Notify it to bring its window to the front.
        notifyExistingInstance(portFile)
        return false
    }

    private fun startListener(portFile: File) {
        try {
            val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            portFile.writeText(server.localPort.toString(), StandardCharsets.UTF_8)
            portFile.deleteOnExit()

            val listenerThread = Thread({
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        client.use {
                            val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                            val message = reader.readLine()
                            if (message == "REOPEN" || message == "ACTIVATE") {
                                DesktopTrackLog.log("SingleInstance: reopen command received from secondary instance")
                                DesktopWindowVisibility.show()
                            }
                        }
                    } catch (_: Exception) {
                        // Server closed or accept interrupted
                    }
                }
            }, "BitChord-SingleInstance-Listener").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            DesktopTrackLog.log("SingleInstance failed to start listener: ${e.message}")
        }
    }

    private fun notifyExistingInstance(portFile: File) {
        try {
            if (portFile.exists()) {
                val portStr = portFile.readText(StandardCharsets.UTF_8).trim()
                val port = portStr.toIntOrNull()
                if (port != null && port in 1..65535) {
                    Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                        socket.soTimeout = 3000
                        val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                        writer.write("REOPEN\n")
                        writer.flush()
                    }
                    DesktopTrackLog.log("SingleInstance: successfully notified existing instance on port $port")
                    return
                }
            }
        } catch (e: Exception) {
            DesktopTrackLog.log("SingleInstance failed to notify existing instance: ${e.message}")
        }
    }
}
