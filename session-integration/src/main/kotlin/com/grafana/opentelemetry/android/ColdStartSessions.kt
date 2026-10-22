package com.grafana.opentelemetry.android

import android.util.AtomicFile
import io.opentelemetry.android.Incubating
import io.opentelemetry.android.agent.session.SessionStorage
import io.opentelemetry.android.session.Session
import io.opentelemetry.api.trace.TraceId
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest

/** Draft storage: a new linked session on each process launch, never restoration. */
@OptIn(Incubating::class)
internal class ColdStartSessions(
    root: File,
    processName: String,
) : SessionStorage,
    Closeable {
    private val file = recordFile(root, processName)
    private val atomicFile = AtomicFile(file)
    private var owner: RandomAccessFile? = null
    private var lease: FileLock? = null
    private var ownershipKey: String? = null
    private var writable = false
    private var closed = false
    private var current: Session = SavedSession("", -1)

    @Volatile
    var failure: String? = null
        private set

    private val previous: Session?

    init {
        require(processName.isNotBlank()) { "A stable Android process name is required, not a PID" }
        previous =
            try {
                if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
                    throw IOException("Cannot create session directory")
                }
                val lockFile = File(root, file.name + ".lock")
                synchronized(owners) {
                    val key = lockFile.canonicalPath
                    // Closing a second descriptor can release this process's existing POSIX lock.
                    if (!owners.add(key)) throw IOException("Session storage already owned")
                    ownershipKey = key
                    owner = RandomAccessFile(lockFile, "rw")
                    lease = owner!!.channel.tryLock()
                    if (lease == null) throw IOException("Session storage already owned")
                }
                writable = true
                readPrevious().also { if (it != null) current = it }
            } catch (_: Exception) {
                failure = "storage-unavailable"
                writable = false
                releaseOwner()
                null
            }
    }

    override fun getPreviousSession(): Session? = previous

    @Synchronized
    override fun get(): Session = current

    @Synchronized
    override fun save(newSession: Session) {
        var output: FileOutputStream? = null
        try {
            val saved = SavedSession(newSession.id, newSession.startTimestamp)
            if (!TraceId.isValid(saved.id) || saved.startTimestamp <= 0) {
                failure = "invalid-session"
                return
            }
            current = saved
            if (closed || !writable) return
            val bytes =
                JSONObject()
                    .put("version", 1)
                    .put("id", saved.id)
                    .put("startTimestamp", saved.startTimestamp)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            output = atomicFile.startWrite()
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (_: Exception) {
            failure = "write-failed"
            try {
                atomicFile.failWrite(output)
            } catch (_: Exception) {
                // Storage must not interrupt session creation or observer delivery.
            }
        }
    }

    private fun readPrevious(): Session? {
        val bytes =
            try {
                atomicFile.openRead().use { input ->
                    val buffer = ByteArray(4097)
                    var size = 0
                    while (size < buffer.size) {
                        val count = input.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size > 4096) {
                        writable = false
                        failure = "oversized-record"
                        return null
                    }
                    buffer.copyOf(size)
                }
            } catch (_: FileNotFoundException) {
                // Permission errors and directories can also produce FileNotFoundException.
                if (file.exists() || File(file.path + ".bak").exists()) {
                    writable = false
                    failure = "read-failed"
                }
                return null
            } catch (_: IOException) {
                writable = false
                failure = "read-failed"
                return null
            }
        return try {
            val record = JSONObject(String(bytes, Charsets.UTF_8))
            val version = record.get("version")
            require(version is Int || version is Long)
            if ((version as Number).toLong() != 1L) {
                writable = false
                failure = "unsupported-version"
                return null
            }
            val id = record.get("id") as String
            val timestamp = record.get("startTimestamp")
            require(timestamp is Int || timestamp is Long)
            val started = (timestamp as Number).toLong()
            require(TraceId.isValid(id) && started > 0)
            SavedSession(id, started)
        } catch (_: Exception) {
            failure = "corrupt-record"
            null
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        releaseOwner()
    }

    private fun releaseOwner() {
        synchronized(owners) {
            try {
                lease?.release()
            } catch (_: Exception) {
            }
            try {
                owner?.close()
            } catch (_: Exception) {
            }
            lease = null
            owner = null
            ownershipKey?.let { owners.remove(it) }
            ownershipKey = null
        }
    }

    companion object {
        private val owners = mutableSetOf<String>()

        internal fun recordFile(
            root: File,
            processName: String,
        ): File {
            val key =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(processName.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return File(root, "$key.json")
        }
    }
}

internal class SavedSession(
    override val id: String,
    override val startTimestamp: Long,
) : Session
