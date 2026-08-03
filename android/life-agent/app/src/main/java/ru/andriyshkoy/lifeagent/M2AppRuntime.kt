package ru.andriyshkoy.lifeagent

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.ProductionProtectedActionablePushConstructionPort
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestConstructionSettings
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningFacade
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncResponseStore
import ru.andriyshkoy.lifeagent.data.local.db.SyncRequestPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.createProductionProtectedDurableDispatchPort
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenVault
import ru.andriyshkoy.lifeagent.data.sync.runtime.M2AuthRuntime
import ru.andriyshkoy.lifeagent.data.sync.runtime.M2AuthRuntimePolicy
import ru.andriyshkoy.lifeagent.data.sync.runtime.M2AuthRuntimeResult
import ru.andriyshkoy.lifeagent.data.sync.runtime.createProductionM2AuthRuntime
import ru.andriyshkoy.lifeagent.data.sync.runtime.createProductionM2SyncWorkExecutionPort
import ru.andriyshkoy.lifeagent.data.sync.status.RoomSyncStatusReadModel
import ru.andriyshkoy.lifeagent.data.sync.transport.LazyProductionM2HttpsTransportBundle
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionPort
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkScheduler
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkSchedulingResult
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NoteSummary
import ru.andriyshkoy.lifeagent.notes.domain.NotesExportSnapshot
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import ru.andriyshkoy.lifeagent.ui.sync.DefaultSyncSetupController
import ru.andriyshkoy.lifeagent.ui.sync.ManualSyncEnqueueGateway
import ru.andriyshkoy.lifeagent.ui.sync.ManualSyncEnqueueResult
import ru.andriyshkoy.lifeagent.ui.sync.SyncEnrollmentGateway
import ru.andriyshkoy.lifeagent.ui.sync.SyncEnrollmentGatewayResult

/**
 * Process-scoped production M2 graph. Constructing it opens neither HTTP
 * transport nor deployment configuration; the shared bundle remains lazy
 * until an auth or exact-dispatch boundary has actual network work.
 */
internal class M2ProductionAppRuntime private constructor(
    private val accessTokenVault: AccessTokenVault,
    private val authRuntime: M2AuthRuntime,
    private val scheduler: SyncWorkScheduler,
    private val statusReadModel: RoomSyncStatusReadModel,
    val notesRepository: NotesRepository,
    val syncWorkExecutionPort: SyncWorkExecutionPort,
) : AutoCloseable {
    fun enqueueAtStartup() {
        try {
            scheduler.enqueueAtStartup()
        } catch (_: Exception) {
            // Local storage is already open; scheduling cannot invalidate it.
        }
    }

    fun createSyncSetupController(): DefaultSyncSetupController =
        DefaultSyncSetupController(
            readModel = statusReadModel,
            enrollmentGateway = ProductionSyncEnrollmentGateway(authRuntime),
            manualSyncGateway = ProductionManualSyncEnqueueGateway(scheduler),
        )

    override fun close() = accessTokenVault.close()

    override fun toString(): String = "M2ProductionAppRuntime(redacted=true)"

    companion object {
        fun create(
            context: Context,
            database: LifeAgentDatabase,
            localNotesRepository: NotesRepository,
        ): M2ProductionAppRuntime {
            val appContext = context.applicationContext
            val accessTokenVault = AccessTokenVault()
            return try {
                val transports = LazyProductionM2HttpsTransportBundle()
                val authPolicy = M2AuthRuntimePolicy()
                val authRuntime = createProductionM2AuthRuntime(
                    context = appContext,
                    database = database,
                    accessTokenVault = accessTokenVault,
                    transports = transports,
                    policy = authPolicy,
                )
                val actionablePushes = ProductionProtectedActionablePushConstructionPort(
                    database = database,
                )
                val planningFacade = ProtectedSyncRequestPlanningFacade(
                    context = appContext,
                    database = database,
                    settings = ProtectedSyncRequestConstructionSettings(
                        pageSize = authPolicy.bootstrapPageSize,
                        attemptBudget = authPolicy.bootstrapAttemptBudget,
                        requestLifetimeMillis = authPolicy.bootstrapRequestLifetimeMillis,
                    ),
                    actionablePushes = actionablePushes,
                )
                val responseStore = ProtectedSyncResponseStore(
                    context = appContext,
                    database = database,
                    bootstrapIntents = planningFacade.protectedBootstrapIntents,
                )
                val protectedDispatch = createProductionProtectedDurableDispatchPort(
                    context = appContext,
                    database = database,
                    bootstrapIntents = planningFacade.protectedBootstrapIntents,
                    accessTokenVault = accessTokenVault,
                    transports = transports,
                    responseStore = responseStore,
                )
                val workExecutionPort = createProductionM2SyncWorkExecutionPort(
                    authRuntime = authRuntime,
                    planningFacade = planningFacade,
                    requestPersistenceStore = SyncRequestPersistenceStore(database),
                    protectedDispatch = protectedDispatch,
                )
                val scheduler = SyncWorkScheduler(appContext)
                M2ProductionAppRuntime(
                    accessTokenVault = accessTokenVault,
                    authRuntime = authRuntime,
                    scheduler = scheduler,
                    statusReadModel = RoomSyncStatusReadModel(
                        database.syncStatusProjectionDao(),
                    ),
                    notesRepository = PostCommitSyncNotesRepository(
                        delegate = localNotesRepository,
                        enqueue = {
                            scheduler.enqueueNow()
                            Unit
                        },
                    ),
                    syncWorkExecutionPort = workExecutionPort,
                )
            } catch (error: Throwable) {
                accessTokenVault.close()
                throw error
            }
        }
    }
}

/** Schedules coalesced work only after a successful local mutation returns. */
internal class PostCommitSyncNotesRepository(
    private val delegate: NotesRepository,
    private val enqueue: () -> Unit,
) : NotesRepository {
    override suspend fun create(command: CreateNoteCommand): NoteMutationOutcome =
        mutate { delegate.create(command) }

    override suspend fun correct(command: CorrectNoteCommand): NoteMutationOutcome =
        mutate { delegate.correct(command) }

    override suspend fun retract(command: RetractNoteCommand): NoteMutationOutcome =
        mutate { delegate.retract(command) }

    override fun observeLastCommitted(): Flow<NoteSummary?> = delegate.observeLastCommitted()

    override suspend fun getByEventId(eventId: UUID): NoteSnapshot? =
        delegate.getByEventId(eventId)

    override suspend fun findByOperationId(operationId: UUID): NoteMutationReceipt? =
        delegate.findByOperationId(operationId)

    override suspend fun exportSnapshot(): NotesExportSnapshot = delegate.exportSnapshot()

    private suspend fun mutate(
        operation: suspend () -> NoteMutationOutcome,
    ): NoteMutationOutcome {
        val outcome = operation()
        if (outcome is NoteMutationOutcome.Persisted) {
            try {
                enqueue()
            } catch (_: Exception) {
                // The Room commit remains authoritative when scheduling is unavailable.
            }
        }
        return outcome
    }

    override fun toString(): String = "PostCommitSyncNotesRepository(redacted=true)"
}

internal fun interface M2EnrollmentRuntimeBoundary {
    suspend fun enroll(
        ownedEnrollmentCode: WipeableSecret,
        replaceActiveDevice: Boolean,
    ): M2AuthRuntimeResult
}

/** Owns the submitted secret and never enables implicit device replacement. */
internal class ProductionSyncEnrollmentGateway internal constructor(
    private val runtime: M2EnrollmentRuntimeBoundary,
) : SyncEnrollmentGateway {
    constructor(runtime: M2AuthRuntime) : this(
        M2EnrollmentRuntimeBoundary(runtime::enroll),
    )

    override suspend fun enroll(
        ownedEnrollmentCode: WipeableSecret,
    ): SyncEnrollmentGatewayResult = try {
        when (
            runtime.enroll(
                ownedEnrollmentCode = ownedEnrollmentCode,
                replaceActiveDevice = false,
            )
        ) {
            is M2AuthRuntimeResult.AccessReady,
            is M2AuthRuntimeResult.DurableCredentialsCommitted,
            -> SyncEnrollmentGatewayResult.CONNECTED

            M2AuthRuntimeResult.Rejected -> SyncEnrollmentGatewayResult.REJECTED
            M2AuthRuntimeResult.ManualReenrollmentRequired ->
                SyncEnrollmentGatewayResult.NEW_CODE_REQUIRED

            M2AuthRuntimeResult.Busy -> SyncEnrollmentGatewayResult.BUSY
            M2AuthRuntimeResult.LocalUnavailable,
            M2AuthRuntimeResult.Unenrolled,
            is M2AuthRuntimeResult.RecoveryComplete,
            -> SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE
        }
    } finally {
        ownedEnrollmentCode.close()
    }

    override fun toString(): String = "ProductionSyncEnrollmentGateway(redacted=true)"
}

internal class ProductionManualSyncEnqueueGateway internal constructor(
    private val enqueue: () -> SyncWorkSchedulingResult,
) : ManualSyncEnqueueGateway {
    constructor(scheduler: SyncWorkScheduler) : this(scheduler::enqueueNow)

    override fun enqueueNow(): ManualSyncEnqueueResult = try {
        when (enqueue()) {
            SyncWorkSchedulingResult.ENQUEUED -> ManualSyncEnqueueResult.QUEUED
            SyncWorkSchedulingResult.NOT_CONFIGURED ->
                ManualSyncEnqueueResult.NOT_CONFIGURED

            SyncWorkSchedulingResult.MISCONFIGURED ->
                ManualSyncEnqueueResult.MISCONFIGURED
        }
    } catch (_: Exception) {
        ManualSyncEnqueueResult.FAILED
    }

    override fun toString(): String =
        "ProductionManualSyncEnqueueGateway(redacted=true)"
}
