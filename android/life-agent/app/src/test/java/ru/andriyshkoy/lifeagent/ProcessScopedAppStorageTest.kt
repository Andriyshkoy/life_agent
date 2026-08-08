package ru.andriyshkoy.lifeagent

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessScopedAppStorageTest {
    @Test
    fun opensLocalContainerOnceAndClosesItOnce() {
        var opens = 0
        val storage = ProcessScopedAppStorage {
            opens += 1
            FakeContainer()
        }

        val first = storage.open().getOrThrow()
        val second = storage.open().getOrThrow()

        assertSame(first, second)
        assertEquals(1, opens)

        storage.closeIfOpened()
        storage.closeIfOpened()

        assertEquals(1, first.closeCalls)
    }

    @Test
    fun failedOpenIsStableAndNeverAttemptsToCloseAContainer() {
        var opens = 0
        val expected = IllegalStateException("local storage unavailable")
        val storage = ProcessScopedAppStorage<FakeContainer> {
            opens += 1
            throw expected
        }

        val first = storage.open()
        val second = storage.open()

        assertTrue(first.isFailure)
        assertSame(expected, first.exceptionOrNull())
        assertSame(expected, second.exceptionOrNull())
        assertEquals(1, opens)

        storage.closeIfOpened()
    }

    @Test
    fun failedCloseCanBeRetried() {
        val container = FakeContainer(failFirstClose = true)
        val storage = ProcessScopedAppStorage { container }
        storage.open().getOrThrow()

        val firstFailure = runCatching { storage.closeIfOpened() }.exceptionOrNull()

        assertTrue(firstFailure is IllegalStateException)
        assertEquals(1, container.closeCalls)

        storage.closeIfOpened()
        storage.closeIfOpened()

        assertEquals(2, container.closeCalls)
    }

    private class FakeContainer(
        private val failFirstClose: Boolean = false,
    ) : Closeable {
        var closeCalls: Int = 0

        override fun close() {
            closeCalls += 1
            if (failFirstClose && closeCalls == 1) {
                throw IllegalStateException("synthetic close failure")
            }
        }
    }
}
