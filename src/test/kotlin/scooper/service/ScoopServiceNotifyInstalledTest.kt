package scooper.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for [ScoopService.notifyInstalled]: the Create flow installs apps
 * through ScoopClient directly (bypassing ScoopService.install), so it needs
 * a way to publish AppInstalled. The method must only publish the event:
 * both VM install paths write the index themselves (the plural upsertApps for
 * orphans, refreshInstalledAppRecordCore for bucket installs), and the old
 * internal singular upsertApp had no orphan filter, so it cleared the
 * bucketId of a same-name bucket row. Uses a real ScoopClient rooted at a
 * temp dir plus an in-memory database, following InstallRecordPersistenceTest.
 */
class ScoopServiceNotifyInstalledTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private fun createService(): Pair<ScoopService, AppsRepository> {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        val repository = createInMemoryAppsRepository(scoopClient)
        return ScoopService(scoopClient, repository) to repository
    }

    private fun app(name: String, bucket: String?): App = App(
        name = name,
        latestVersion = "1.2.3",
        version = "1.2.3",
        status = AppStatus.INSTALLED,
        bucket = bucket?.let { Bucket(it) },
    )

    // ---------------------------------------------------------------------
    // notifyInstalled
    // ---------------------------------------------------------------------

    @Test
    fun `notifyInstalled emits AppInstalled with the app name`() = runBlocking {
        val (service, _) = createService()
        var received: ScoopEvent? = null
        // Handshake: wait for the collector to actually subscribe before
        // emitting, otherwise the event lands before any subscriber exists.
        val ready = CompletableDeferred<Unit>()
        // first{} returns as soon as the event arrives, so the collector
        // coroutine finishes by itself instead of hanging on join().
        val job = launch(Dispatchers.Default) {
            received = service.events
                .onSubscription { ready.complete(Unit) }
                .first { it is ScoopEvent.AppInstalled }
        }
        ready.await()

        service.notifyInstalled(app("testapp", "main"))
        withTimeout(10_000) { job.join() }

        assertEquals(ScoopEvent.AppInstalled("testapp"), received)
    }

    @Test
    fun `notifyInstalled leaves the index to the caller`() = runBlocking {
        val (service, repository) = createService()

        service.notifyInstalled(app("testapp", "main"))

        val stored = repository.getApps(query = "testapp").value.firstOrNull { it.name == "testapp" }
        assertEquals(
            null, stored,
            "notifyInstalled must not write the index; both VM install paths upsert before calling it",
        )
    }

    @Test
    fun `notifyInstalled does not clear the bucketId of a same-name bucket row`() = runBlocking {
        val (service, repository) = createService()
        // The bucket path (refreshInstalledAppRecordCore) wrote a proper
        // bucket-scoped row before this notify arrives; ensureBucket stands in
        // for the bucket scan that would have created the row in production.
        repository.ensureBucket("main")
        repository.upsertApp(app("testapp", "main"))

        // An orphan notification for the same name must never demote the row.
        service.notifyInstalled(app("testapp", null))

        val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
        assertEquals("main", stored.bucket?.name, "the bucket row must keep its bucketId")
        assertEquals(AppStatus.INSTALLED, stored.status)
    }

    @Test
    fun `notifyInstalled does not touch other app records in the index`() = runBlocking {
        val (service, repository) = createService()
        repository.upsertApp(app("other", "main"))

        service.notifyInstalled(app("testapp", "main"))

        val names = repository.getApps().value.map { it.name }
        assertTrue(names.contains("other"), "unrelated records must survive the notification")
        assertFalse(names.contains("testapp"), "no record must appear for the notified app")
    }
}
