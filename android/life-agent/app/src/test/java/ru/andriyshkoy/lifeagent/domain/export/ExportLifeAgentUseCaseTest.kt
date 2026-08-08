package ru.andriyshkoy.lifeagent.domain.export

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.export.CanonicalLifeAgentExportCodec
import ru.andriyshkoy.lifeagent.data.export.CatalogExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.LifeAgentExportSnapshot

class ExportLifeAgentUseCaseTest {
    @Test
    fun delegatesCompleteSnapshotToCanonicalCodec() {
        val snapshot = LifeAgentExportSnapshot(
            catalogs = CatalogExportSnapshot.Empty,
            events = emptyList(),
            revisions = emptyList(),
        )
        val useCase = ExportLifeAgentUseCase(
            source = LifeAgentExportSnapshotSource { snapshot },
            codec = CanonicalLifeAgentExportCodec(),
        )

        val bytes = runSuspend { useCase() }

        assertEquals(
            """{"catalogs":{"heads":[],"items":[],"versions":[]},"events":[],"format":"life-agent","format_version":"1.0.0","revisions":[]}""",
            bytes.toString(Charsets.UTF_8),
        )
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome) { "The test block unexpectedly suspended" }.getOrThrow()
    }
}
