package com.grafana.opentelemetry.android

import android.util.AtomicFile
import io.mockk.every
import io.mockk.mockkConstructor
import io.opentelemetry.android.Incubating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(Incubating::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], manifest = Config.NONE)
class ColdStartSessionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = SavedSession("a".repeat(32), 100)
    private val second = SavedSession("b".repeat(32), 200)

    @Test fun `previous session is captured before save and does not change on reset`() {
        ColdStartSessions(temporary.root, "main").use {
            assertNull(it.getPreviousSession())
            assertEquals("", it.get().id)
            it.save(first)
        }
        ColdStartSessions(temporary.root, "main").use {
            assertEquals(first.id, it.getPreviousSession()!!.id)
            assertEquals(first.startTimestamp, it.getPreviousSession()!!.startTimestamp)
            it.save(second)
            assertEquals(second.id, it.get().id)
            assertEquals(first.id, it.getPreviousSession()!!.id)
        }
        ColdStartSessions(temporary.root, "main").use { assertEquals(second.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `corrupt records start fresh and can be replaced`() {
        val file = ColdStartSessions.recordFile(temporary.root, "main")
        val invalid =
            listOf(
                "bad json",
                "{}",
                """{"version":1.5,"id":"${first.id}","startTimestamp":100}""",
                """{"version":"1","id":"${first.id}","startTimestamp":100}""",
                """{"version":1,"id":"bad","startTimestamp":100}""",
                """{"version":1,"id":"${first.id}","startTimestamp":"100"}""",
                """{"version":1,"id":"${first.id}","startTimestamp":100.5}""",
                """{"version":1,"id":"${first.id}","startTimestamp":9223372036854775808}""",
                """{"version":1,"id":"${first.id}","startTimestamp":0}""",
                """{"version":1,"id":"${"0".repeat(32)}","startTimestamp":100}""",
            )
        for (contents in invalid) {
            file.writeText(contents)
            ColdStartSessions(temporary.root, "main").use {
                assertNull(contents, it.getPreviousSession())
                assertEquals("corrupt-record", it.failure)
                it.save(first)
                assertEquals(first.id, it.get().id)
            }
            ColdStartSessions(temporary.root, "main").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
        }
    }

    @Test fun `oversized records are preserved without reading or replacing the full file`() {
        val file = ColdStartSessions.recordFile(temporary.root, "main")
        val oversized = "x".repeat(4097)
        file.writeText(oversized)
        ColdStartSessions(temporary.root, "main").use {
            assertNull(it.getPreviousSession())
            it.save(first)
            assertEquals(first.id, it.get().id)
            assertEquals("oversized-record", it.failure)
        }
        assertEquals(oversized, file.readText())
    }

    @Test fun `future records are preserved without linking or overwriting`() {
        val file = ColdStartSessions.recordFile(temporary.root, "main")
        val future = "{\"version\":2,\"newField\":true}"
        file.writeText(future)
        ColdStartSessions(temporary.root, "main").use {
            assertNull(it.getPreviousSession())
            it.save(first)
            assertEquals(first.id, it.get().id)
            assertEquals("unsupported-version", it.failure)
        }
        assertEquals(future, file.readText())
    }

    @Test fun `unreadable record is not mistaken for a missing record`() {
        val file = ColdStartSessions.recordFile(temporary.root, "main")
        assertTrue(file.mkdir())
        val marker = File(file, "keep").apply { writeText("existing data") }
        ColdStartSessions(temporary.root, "main").use {
            assertNull(it.getPreviousSession())
            assertEquals("read-failed", it.failure)
            it.save(first)
            assertEquals(first.id, it.get().id)
        }
        assertEquals("existing data", marker.readText())
    }

    @Test fun `unavailable directory falls back to memory`() {
        ColdStartSessions(temporary.newFile("not-a-directory"), "main").use {
            assertNull(it.getPreviousSession())
            it.save(first)
            assertEquals(first.id, it.get().id)
            assertEquals("storage-unavailable", it.failure)
        }
    }

    @Test fun `directory created by another process does not disable persistence`() {
        val root = File(temporary.root, "records")
        var mkdirsCalled = false
        val racingRoot =
            object : File(root.path) {
                override fun mkdirs(): Boolean {
                    mkdirsCalled = true
                    // Another process creates the directory after the initial isDirectory check.
                    assertTrue(root.mkdirs())
                    return false
                }
            }
        ColdStartSessions(racingRoot, "main").use {
            assertTrue(mkdirsCalled)
            assertNull(it.failure)
            it.save(first)
            assertNull(it.failure)
        }
        ColdStartSessions(root, "main").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `failed save keeps memory current but next launch sees last durable session`() {
        val root = temporary.newFolder("records")
        val displaced = File(temporary.root, "displaced")
        val writer = Executors.newSingleThreadExecutor()
        ColdStartSessions(root, "main", writer).use {
            it.save(first)
            writer.submit {}.get(10, SECONDS)
            assertTrue(root.renameTo(displaced))
            root.writeText("unavailable")
            it.save(second)
            writer.submit {}.get(10, SECONDS)
            assertEquals(second.id, it.get().id)
            assertEquals("write-failed", it.failure)
            assertTrue(root.delete())
            assertTrue(displaced.renameTo(root))
        }
        ColdStartSessions(root, "main").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `save and get do not wait for the writer and pending saves keep the latest session`() {
        val writer = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        writer.submit { check(release.await(10, SECONDS)) }
        val storage = ColdStartSessions(temporary.root, "main", writer)
        try {
            val current =
                callers.submit<String> {
                    storage.save(first)
                    storage.save(second)
                    storage.get().id
                }
            assertEquals(second.id, current.get(5, SECONDS))
            assertFalse(ColdStartSessions.recordFile(temporary.root, "main").exists())
        } finally {
            release.countDown()
            storage.close()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(10, SECONDS))
        }
        ColdStartSessions(temporary.root, "main").use { assertEquals(second.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `shutdown drains writes before releasing ownership even when interrupted`() {
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val writer =
            object : ThreadPoolExecutor(1, 1, 0, SECONDS, LinkedBlockingQueue()) {
                override fun shutdown() {
                    super.shutdown()
                    closing.countDown()
                }
            }
        writer.submit { check(release.await(10, SECONDS)) }
        val storage = ColdStartSessions(temporary.root, "main", writer)
        storage.save(first)
        val closer =
            Thread {
                Thread.currentThread().interrupt()
                storage.close()
                interrupted.set(Thread.currentThread().isInterrupted)
                closed.countDown()
            }
        try {
            closer.start()
            assertTrue(closing.await(5, SECONDS))
            ColdStartSessions(temporary.root, "main").use { assertEquals("storage-unavailable", it.failure) }
            assertEquals(1L, closed.count)
        } finally {
            release.countDown()
            closer.join(10000)
            storage.close()
        }
        assertFalse(closer.isAlive)
        assertEquals(0L, closed.count)
        assertTrue(interrupted.get())
        ColdStartSessions(temporary.root, "main").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `an in-flight disk write does not hold the session state lock`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callers = Executors.newSingleThreadExecutor()
        mockkConstructor(AtomicFile::class) {
            every { anyConstructed<AtomicFile>().startWrite() } answers {
                entered.countDown()
                check(release.await(10, SECONDS))
                callOriginal()
            }
            val storage = ColdStartSessions(temporary.root, "main")
            try {
                storage.save(first)
                assertTrue(entered.await(5, SECONDS))
                assertEquals(
                    second.id,
                    callers
                        .submit<String> {
                            storage.save(second)
                            storage.get().id
                        }.get(5, SECONDS),
                )
            } finally {
                release.countDown()
                storage.close()
                callers.shutdownNow()
                assertTrue(callers.awaitTermination(10, SECONDS))
            }
        }
        ColdStartSessions(temporary.root, "main").use { assertEquals(second.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `a rejected write task leaves the session available in memory`() {
        val writer = Executors.newSingleThreadExecutor().apply { shutdown() }
        ColdStartSessions(temporary.root, "main", writer).use {
            it.save(first)
            assertEquals(first.id, it.get().id)
            assertEquals("write-failed", it.failure)
        }
        ColdStartSessions(temporary.root, "main").use { assertNull(it.getPreviousSession()) }
    }

    @Test fun `a later write can succeed after an asynchronous write failure`() {
        val root = temporary.newFolder("records")
        val displaced = File(temporary.root, "displaced")
        val writer = Executors.newSingleThreadExecutor()
        ColdStartSessions(root, "main", writer).use {
            assertTrue(root.renameTo(displaced))
            root.writeText("unavailable")
            it.save(first)
            writer.submit {}.get(10, SECONDS)
            assertEquals("write-failed", it.failure)
            assertTrue(root.delete())
            assertTrue(displaced.renameTo(root))
            it.save(second)
        }
        ColdStartSessions(root, "main").use { assertEquals(second.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `invalid new sessions cannot replace a durable record`() {
        ColdStartSessions(temporary.root, "main").use {
            it.save(first)
            it.save(SavedSession("invalid", 200))
            it.save(SavedSession(second.id, -1))
            assertEquals("invalid-session", it.failure)
            assertEquals(first.id, it.get().id)
        }
        ColdStartSessions(temporary.root, "main").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `independent process names keep separate chains and reject competing writers`() {
        ColdStartSessions(temporary.root, "app").use { main ->
            main.save(first)
            ColdStartSessions(temporary.root, "app:worker").use { worker ->
                assertNull(worker.getPreviousSession())
                worker.save(second)
            }
            ColdStartSessions(temporary.root, "app").use { competing ->
                assertNull(competing.getPreviousSession())
                competing.save(second)
                assertEquals("storage-unavailable", competing.failure)
            }
        }
        ColdStartSessions(temporary.root, "app").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
        ColdStartSessions(temporary.root, "app:worker").use { assertEquals(second.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `closed owner cannot write after another owner starts`() {
        val closed = ColdStartSessions(temporary.root, "main")
        closed.save(first)
        closed.close()
        closed.close()
        ColdStartSessions(temporary.root, "main").use { it.save(second) }
        closed.save(first)
        ColdStartSessions(temporary.root, "main").use { assertEquals(second.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `rejecting a duplicate owner preserves the lock against another JVM process`() {
        val source = File(checkNotNull(System.getProperty("sessionDraft.lockProbe")))
        val lockFile = File(ColdStartSessions.recordFile(temporary.root, "main").path + ".lock")

        fun probe(): String {
            val process =
                ProcessBuilder(
                    File(System.getProperty("java.home"), "bin/java").path,
                    source.path,
                    lockFile.path,
                ).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(15, SECONDS))
                val result =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                assertEquals(result, 0, process.exitValue())
                return result
            } finally {
                process.destroyForcibly()
                process.waitFor(5, SECONDS)
            }
        }
        ColdStartSessions(temporary.root, "main").use { owner ->
            owner.save(first)
            assertEquals("LOCKED", probe())
            ColdStartSessions(temporary.root, "main").use { assertEquals("storage-unavailable", it.failure) }
            ColdStartSessions(File(temporary.root, "."), "main").use { assertEquals("storage-unavailable", it.failure) }
            assertEquals("LOCKED", probe())
        }
        assertEquals("AVAILABLE", probe())
    }

    @Test fun `concurrent saves leave a complete record matching memory`() {
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            var last: String? = null
            ColdStartSessions(temporary.root, "main").use { storage ->
                val tasks =
                    (1..4).map { role ->
                        executor.submit {
                            ready.countDown()
                            check(start.await(10, SECONDS))
                            repeat(25) { index ->
                                val id = "%032x".format(role * 100 + index)
                                storage.save(SavedSession(id, 100L + index))
                                assertEquals(32, storage.get().id.length)
                            }
                        }
                    }
                assertTrue(ready.await(10, SECONDS))
                start.countDown()
                tasks.forEach { it.get(30, SECONDS) }
                assertNull(storage.failure)
                last = storage.get().id
            }
            ColdStartSessions(temporary.root, "main").use { assertEquals(last, it.getPreviousSession()!!.id) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, SECONDS))
        }
    }

    @Test fun `atomic backup is recovered before linking`() {
        ColdStartSessions(temporary.root, "main").use { it.save(first) }
        val file = ColdStartSessions.recordFile(temporary.root, "main")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        file.writeText("interrupted replacement")
        ColdStartSessions(temporary.root, "main").use { assertEquals(first.id, it.getPreviousSession()!!.id) }
    }

    @Test fun `blank process identity is rejected and names cannot escape the directory`() {
        assertThrows(IllegalArgumentException::class.java) { ColdStartSessions(temporary.root, " ") }
        assertEquals(temporary.root, ColdStartSessions.recordFile(temporary.root, "../../worker").parentFile)
    }
}
