package ru.andriyshkoy.lifeagent.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDocumentDeliveryTest {
    @Test
    fun `success is reported only after the destination closes and plaintext is wiped`() =
        runTest {
            val plaintext = "private export".toByteArray()
            val expected = plaintext.copyOf()
            val output = RecordingOutputStream()

            val result = deliverExportDocument(
                generate = { plaintext },
                outputFactory = TruncatingOutputFactory { output },
            )

            assertSame(ExportDeliveryResult.Success, result)
            assertArrayEquals(expected, output.bytes())
            assertTrue(output.flushed)
            assertTrue(output.closed)
            assertArrayEquals(ByteArray(plaintext.size), plaintext)
        }

    @Test
    fun `close failure is not success and a second truncating open sanitizes destination`() =
        runTest {
            val plaintext = "private export".toByteArray()
            val failedWrite = RecordingOutputStream(failOnClose = true)
            val sanitizer = RecordingOutputStream()
            val outputs = ArrayDeque<OutputStream>().apply {
                add(failedWrite)
                add(sanitizer)
            }

            val result = deliverExportDocument(
                generate = { plaintext },
                outputFactory = TruncatingOutputFactory { outputs.removeFirst() },
            )

            assertEquals(
                ExportDeliveryResult.WriteFailed(destinationSanitized = true),
                result,
            )
            assertTrue(failedWrite.closed)
            assertTrue(sanitizer.closed)
            assertArrayEquals(ByteArray(plaintext.size), plaintext)
        }

    @Test
    fun `partial write and failed sanitization are reported as not guaranteed`() =
        runTest {
            val plaintext = "private export".toByteArray()
            var opens = 0
            val factory = TruncatingOutputFactory {
                opens += 1
                when (opens) {
                    1 -> RecordingOutputStream(failAfterBytes = 3)
                    else -> throw IOException("provider unavailable")
                }
            }

            val result = deliverExportDocument(
                generate = { plaintext },
                outputFactory = factory,
            )

            assertEquals(
                ExportDeliveryResult.WriteFailed(destinationSanitized = false),
                result,
            )
            assertEquals(2, opens)
            assertArrayEquals(ByteArray(plaintext.size), plaintext)
        }

    @Test
    fun `sanitization is not guaranteed until its truncating stream closes`() =
        runTest {
            val plaintext = "private export".toByteArray()
            val partialWrite = RecordingOutputStream(failAfterBytes = 3)
            val failedSanitizer = RecordingOutputStream(failOnClose = true)
            val outputs = ArrayDeque<OutputStream>().apply {
                add(partialWrite)
                add(failedSanitizer)
            }

            val result = deliverExportDocument(
                generate = { plaintext },
                outputFactory = TruncatingOutputFactory { outputs.removeFirst() },
            )

            assertEquals(
                ExportDeliveryResult.WriteFailed(destinationSanitized = false),
                result,
            )
            assertTrue(failedSanitizer.closed)
            assertArrayEquals(ByteArray(plaintext.size), plaintext)
        }

    @Test
    fun `generation failure never opens destination`() = runTest {
        var opens = 0

        val result = deliverExportDocument(
            generate = { error("cannot build export") },
            outputFactory = TruncatingOutputFactory {
                opens += 1
                RecordingOutputStream()
            },
        )

        assertSame(ExportDeliveryResult.GenerationFailed, result)
        assertEquals(0, opens)
    }

    @Test
    fun `write cancellation sanitizes destination wipes plaintext and stays cancellation`() =
        runTest {
            val plaintext = "private export".toByteArray()
            val cancellation = CancellationException("cancelled")
            val sanitizer = RecordingOutputStream()
            var opens = 0
            val factory = TruncatingOutputFactory {
                opens += 1
                if (opens == 1) {
                    object : OutputStream() {
                        override fun write(value: Int) {
                            throw cancellation
                        }
                    }
                } else {
                    sanitizer
                }
            }

            val thrown = assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    deliverExportDocument(
                        generate = { plaintext },
                        outputFactory = factory,
                    )
                }
            }

            assertSame(cancellation, thrown)
            assertEquals(2, opens)
            assertTrue(sanitizer.closed)
            assertArrayEquals(ByteArray(plaintext.size), plaintext)
        }

    @Test
    fun `errors propagate while plaintext is still wiped`() {
        val plaintext = "private export".toByteArray()
        val fatal = AssertionError("fatal")

        val thrown = assertThrows(AssertionError::class.java) {
            kotlinx.coroutines.runBlocking {
                deliverExportDocument(
                    generate = { plaintext },
                    outputFactory = TruncatingOutputFactory {
                        object : OutputStream() {
                            override fun write(value: Int) {
                                throw fatal
                            }
                        }
                    },
                )
            }
        }

        assertSame(fatal, thrown)
        assertArrayEquals(ByteArray(plaintext.size), plaintext)
    }

    @Test
    fun `duplicate choose request cannot start a second picker`() {
        val choosing = ExportUiPhase.Idle.after(ExportUiEvent.ChooseRequested)

        assertEquals(ExportUiPhase.ChoosingDestination, choosing)
        assertEquals(
            ExportUiPhase.ChoosingDestination,
            choosing.after(ExportUiEvent.ChooseRequested),
        )
        assertEquals(
            ExportUiPhase.Delivering,
            ExportUiPhase.Delivering.after(ExportUiEvent.ChooseRequested),
        )
    }

    @Test
    fun `destination callback after recreation starts delivery without a saved busy flag`() {
        val recreatedPhase = ExportUiPhase.Idle

        val delivering = recreatedPhase.after(ExportUiEvent.DestinationAccepted)

        assertEquals(ExportUiPhase.Delivering, delivering)
        assertEquals(
            ExportUiPhase.Idle,
            delivering.after(ExportUiEvent.DeliveryFinished),
        )
    }

    @Test
    fun `picker cancellation and launch failure both release the flow`() {
        val choosing = ExportUiPhase.ChoosingDestination

        assertEquals(
            ExportUiPhase.Idle,
            choosing.after(ExportUiEvent.DestinationCancelled),
        )
        assertEquals(
            ExportUiPhase.Idle,
            choosing.after(ExportUiEvent.LaunchFailed),
        )
    }

    private class RecordingOutputStream(
        private val failAfterBytes: Int? = null,
        private val failOnClose: Boolean = false,
    ) : OutputStream() {
        private val delegate = ByteArrayOutputStream()

        var flushed: Boolean = false
            private set
        var closed: Boolean = false
            private set

        override fun write(value: Int) {
            val failurePoint = failAfterBytes
            if (failurePoint != null && delegate.size() >= failurePoint) {
                throw IOException("partial write")
            }
            delegate.write(value)
        }

        override fun flush() {
            flushed = true
        }

        override fun close() {
            closed = true
            if (failOnClose) {
                throw IOException("close failed")
            }
        }

        fun bytes(): ByteArray = delegate.toByteArray()
    }
}
