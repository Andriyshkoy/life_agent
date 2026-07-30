package ru.andriyshkoy.lifeagent.export

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

internal enum class ExportUiPhase {
    Idle,
    ChoosingDestination,
    Delivering,
}

internal enum class ExportUiEvent {
    ChooseRequested,
    DestinationAccepted,
    DestinationCancelled,
    LaunchFailed,
    DeliveryFinished,
}

/**
 * The state is deliberately not persisted. Android's activity-result registry persists a pending
 * document-picker result, while a new UI instance must never inherit an unrecoverable busy flag.
 */
internal fun ExportUiPhase.after(event: ExportUiEvent): ExportUiPhase =
    when (event) {
        ExportUiEvent.ChooseRequested ->
            if (this == ExportUiPhase.Idle) {
                ExportUiPhase.ChoosingDestination
            } else {
                this
            }

        ExportUiEvent.DestinationAccepted -> ExportUiPhase.Delivering
        ExportUiEvent.DestinationCancelled,
        ExportUiEvent.LaunchFailed,
        ExportUiEvent.DeliveryFinished,
        -> ExportUiPhase.Idle
    }

internal sealed interface ExportDeliveryResult {
    data object Success : ExportDeliveryResult

    data object GenerationFailed : ExportDeliveryResult

    data class WriteFailed(
        val destinationSanitized: Boolean,
    ) : ExportDeliveryResult
}

internal fun interface TruncatingOutputFactory {
    fun open(): OutputStream
}

/**
 * Opens a SAF destination only in a mode whose contract includes truncation.
 *
 * Providers do not all implement the same mode set, so read/write-and-truncate is attempted first
 * and write-and-truncate second. Plain "w" is intentionally not used because it is not guaranteed
 * to truncate existing content.
 */
internal class ContentResolverTruncatingOutputFactory(
    private val contentResolver: ContentResolver,
    private val destination: Uri,
) : TruncatingOutputFactory {
    override fun open(): OutputStream {
        var firstFailure: Exception? = null

        TRUNCATING_MODES.forEach { mode ->
            try {
                contentResolver.openOutputStream(destination, mode)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else if (failure !== firstFailure) {
                    firstFailure.addSuppressed(failure)
                }
            }
        }

        throw IOException(
            "The selected document provider did not open a truncating output stream.",
            firstFailure,
        )
    }

    private companion object {
        val TRUNCATING_MODES = listOf("rwt", "wt")
    }
}

/**
 * Builds the plaintext only after a destination exists, owns that buffer for its entire lifetime,
 * and wipes it before returning on every path.
 */
internal suspend fun deliverExportDocument(
    generate: suspend () -> ByteArray,
    outputFactory: TruncatingOutputFactory,
): ExportDeliveryResult {
    var plaintext: ByteArray? = null
    var generationCompleted = false

    return try {
        plaintext = generate()
        generationCompleted = true
        writeAndCloseExport(
            bytes = plaintext,
            outputFactory = outputFactory,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (generationCompleted) {
            ExportDeliveryResult.WriteFailed(destinationSanitized = false)
        } else {
            ExportDeliveryResult.GenerationFailed
        }
    } finally {
        plaintext?.fill(0)
    }
}

private fun writeAndCloseExport(
    bytes: ByteArray,
    outputFactory: TruncatingOutputFactory,
): ExportDeliveryResult =
    try {
        outputFactory.open().use { output ->
            output.write(bytes)
            output.flush()
        }
        ExportDeliveryResult.Success
    } catch (cancelled: CancellationException) {
        sanitizeDestination(outputFactory)
        throw cancelled
    } catch (_: Exception) {
        ExportDeliveryResult.WriteFailed(
            destinationSanitized = sanitizeDestination(outputFactory),
        )
    }

/**
 * A successful truncate-and-close is the strongest guarantee available from an arbitrary document
 * provider. When it fails, callers must tell the user that partial plaintext may remain.
 */
private fun sanitizeDestination(
    outputFactory: TruncatingOutputFactory,
): Boolean =
    try {
        outputFactory.open().use { output ->
            output.flush()
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
