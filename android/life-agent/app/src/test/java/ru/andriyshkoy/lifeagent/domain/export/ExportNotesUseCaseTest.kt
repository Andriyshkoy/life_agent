package ru.andriyshkoy.lifeagent.domain.export

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.export.CanonicalNotesExportCodec
import ru.andriyshkoy.lifeagent.data.export.NotesExportSnapshot

class ExportNotesUseCaseTest {
    @Test
    fun delegatesSnapshotToCanonicalCodec() {
        val snapshot = NotesExportSnapshot(
            events = emptyList(),
            revisions = emptyList(),
        )
        val source = NotesExportSnapshotSource { snapshot }
        val useCase = ExportNotesUseCase(
            source = source,
            codec = CanonicalNotesExportCodec(),
        )

        val bytes = runSuspend { useCase() }

        assertEquals(
            """{"events":[],"format":"life-agent-notes","format_version":"2.0.0","revisions":[]}""",
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
        return checkNotNull(outcome) {
            "The test block unexpectedly suspended"
        }.getOrThrow()
    }
}
