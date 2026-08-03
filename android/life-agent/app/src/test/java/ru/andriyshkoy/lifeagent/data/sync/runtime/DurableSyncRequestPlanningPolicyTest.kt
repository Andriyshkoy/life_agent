package ru.andriyshkoy.lifeagent.data.sync.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSyncRequestPlanningPolicyTest {
    @Test
    fun revokeAlwaysOutranksRefreshAndEverySyncRoute() {
        val retained = DurableSyncRequestKind.entries.toSet()
        for (auth in DurableSyncPlannerAuth.entries) {
            for (stream in DurableSyncPlannerStream.entries) {
                assertEquals(
                    DurableSyncRequestPlan.RetainExisting(DurableSyncRequestKind.REVOKE),
                    decide(
                        auth = auth,
                        stream = stream,
                        session = true,
                        outbox = true,
                        retained = retained,
                        waitingAmbiguous = true,
                        otherOpen = true,
                    ),
                )
            }
        }
    }

    @Test
    fun currentWaiterAndExpiredAccessOutrankBootstrapPushAndPull() {
        val nonRevoke = setOf(
            DurableSyncRequestKind.BOOTSTRAP,
            DurableSyncRequestKind.PUSH,
            DurableSyncRequestKind.PULL,
        )
        listOf(
            decide(
                auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                stream = DurableSyncPlannerStream.BOOTSTRAP_REQUIRED,
                session = true,
                outbox = true,
                retained = nonRevoke,
                waitingCurrent = true,
                otherOpen = true,
            ),
            decide(
                auth = DurableSyncPlannerAuth.REFRESH_REQUIRED,
                stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
                session = false,
                outbox = true,
                retained = nonRevoke,
                otherOpen = true,
            ),
        ).forEach { plan ->
            assertEquals(
                noRequest(DurableSyncNoRequestReason.REFRESH_REQUIRED),
                plan,
            )
        }
    }

    @Test
    fun nonRevokeExistingRequestsKeepBootstrapPushPullOrder() {
        val ordered = listOf(
            DurableSyncRequestKind.BOOTSTRAP,
            DurableSyncRequestKind.PUSH,
            DurableSyncRequestKind.PULL,
        )
        for (mask in 1 until (1 shl ordered.size)) {
            val retained = ordered
                .filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                .toSet()
            assertEquals(
                DurableSyncRequestPlan.RetainExisting(ordered.first { it in retained }),
                decide(
                    auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                    stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
                    session = false,
                    outbox = true,
                    retained = retained,
                    otherOpen = true,
                ),
            )
        }
    }

    @Test
    fun unresolvedOpenRequestBlocksEveryNewIntentWithoutRefreshAuthority() {
        for (auth in DurableSyncPlannerAuth.entries) {
            if (auth == DurableSyncPlannerAuth.REFRESH_REQUIRED) continue
            for (stream in DurableSyncPlannerStream.entries) {
                for (session in listOf(false, true)) {
                    for (outbox in listOf(false, true)) {
                        assertEquals(
                            noRequest(DurableSyncNoRequestReason.OPEN_REQUEST_REQUIRES_RECOVERY),
                            decide(
                                auth = auth,
                                stream = stream,
                                session = session,
                                outbox = outbox,
                                otherOpen = true,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun ambiguousWaitingMetadataBlocksExpiredAccessAndEveryNonRevokeRoute() {
        val plan = decide(
            auth = DurableSyncPlannerAuth.REFRESH_REQUIRED,
            stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
            session = false,
            outbox = true,
            retained = setOf(
                DurableSyncRequestKind.BOOTSTRAP,
                DurableSyncRequestKind.PUSH,
                DurableSyncRequestKind.PULL,
            ),
            waitingAmbiguous = true,
            otherOpen = true,
        )

        assertEquals(
            noRequest(DurableSyncNoRequestReason.OPEN_REQUEST_REQUIRES_RECOVERY),
            plan,
        )
    }

    @Test
    fun leaseRecoveryProjectionRetainsExpiredSendingAndBlocksLiveSending() {
        val expiredSending = decide(
            auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
            stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
            session = false,
            outbox = false,
            retained = setOf(DurableSyncRequestKind.PULL),
        )
        val liveSending = decide(
            auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
            stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
            session = false,
            outbox = false,
            otherOpen = true,
        )

        assertEquals(
            DurableSyncRequestPlan.RetainExisting(DurableSyncRequestKind.PULL),
            expiredSending,
        )
        assertEquals(
            noRequest(DurableSyncNoRequestReason.OPEN_REQUEST_REQUIRES_RECOVERY),
            liveSending,
        )
    }

    @Test
    fun inactiveAuthorityNeverCreatesANewDurableRequest() {
        val expected = mapOf(
            DurableSyncPlannerAuth.MISSING to
                DurableSyncNoRequestReason.AUTHORITY_MISSING,
            DurableSyncPlannerAuth.REFRESH_REQUIRED to
                DurableSyncNoRequestReason.REFRESH_REQUIRED,
            DurableSyncPlannerAuth.REVOKE_PENDING to
                DurableSyncNoRequestReason.REVOKE_REQUEST_MISSING,
            DurableSyncPlannerAuth.UNUSABLE to
                DurableSyncNoRequestReason.AUTHORITY_UNUSABLE,
        )
        expected.forEach { (auth, reason) ->
            for (stream in DurableSyncPlannerStream.entries) {
                for (session in listOf(false, true)) {
                    for (outbox in listOf(false, true)) {
                        assertEquals(
                            noRequest(reason),
                            decide(auth, stream, session, outbox),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun bootstrapRequiredCreatesOnlyStartOrContinuationBootstrapIntent() {
        for (outbox in listOf(false, true)) {
            assertEquals(
                DurableSyncRequestPlan.CreateBootstrap(DurableBootstrapIntentKind.START),
                decide(
                    auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                    stream = DurableSyncPlannerStream.BOOTSTRAP_REQUIRED,
                    session = false,
                    outbox = outbox,
                ),
            )
            assertEquals(
                DurableSyncRequestPlan.CreateBootstrap(DurableBootstrapIntentKind.CONTINUE),
                decide(
                    auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                    stream = DurableSyncPlannerStream.BOOTSTRAP_REQUIRED,
                    session = true,
                    outbox = outbox,
                ),
            )
        }
    }

    @Test
    fun actionableIncrementalOutboxPrecedesPull() {
        assertEquals(
            DurableSyncRequestPlan.CreatePush,
            decide(
                auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
                session = false,
                outbox = true,
            ),
        )
        assertEquals(
            DurableSyncRequestPlan.CreatePull,
            decide(
                auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
                session = false,
                outbox = false,
            ),
        )
    }

    @Test
    fun activePullContinuationPrecedesANewPushBatch() {
        for (outbox in listOf(false, true)) {
            assertEquals(
                DurableSyncRequestPlan.CreatePull,
                decide(
                    auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                    stream = DurableSyncPlannerStream.PULLING_WITH_CURSOR,
                    session = false,
                    outbox = outbox,
                ),
            )
        }
    }

    @Test
    fun pushMayProceedWithoutCursorButPullFailsClosed() {
        assertEquals(
            DurableSyncRequestPlan.CreatePush,
            decide(
                auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                stream = DurableSyncPlannerStream.INCREMENTAL_WITHOUT_CURSOR,
                session = false,
                outbox = true,
            ),
        )
        assertEquals(
            noRequest(DurableSyncNoRequestReason.AUTHORITATIVE_CURSOR_MISSING),
            decide(
                auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                stream = DurableSyncPlannerStream.INCREMENTAL_WITHOUT_CURSOR,
                session = false,
                outbox = false,
            ),
        )
        assertEquals(
            noRequest(DurableSyncNoRequestReason.AUTHORITATIVE_CURSOR_MISSING),
            decide(
                auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                stream = DurableSyncPlannerStream.PULLING_WITHOUT_CURSOR,
                session = false,
                outbox = true,
            ),
        )
    }

    @Test
    fun missingOrHaltedStreamFailsClosed() {
        for (outbox in listOf(false, true)) {
            assertEquals(
                noRequest(DurableSyncNoRequestReason.STREAM_AUTHORITY_MISSING),
                decide(
                    auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                    stream = DurableSyncPlannerStream.MISSING,
                    session = false,
                    outbox = outbox,
                ),
            )
            assertEquals(
                noRequest(DurableSyncNoRequestReason.INTEGRITY_HALTED),
                decide(
                    auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                    stream = DurableSyncPlannerStream.INTEGRITY_HALTED,
                    session = false,
                    outbox = outbox,
                ),
            )
        }
    }

    @Test
    fun bootstrapSessionOutsideBootstrapPhaseFailsClosed() {
        DurableSyncPlannerStream.entries
            .filterNot { it == DurableSyncPlannerStream.BOOTSTRAP_REQUIRED }
            .forEach { stream ->
                for (outbox in listOf(false, true)) {
                    assertEquals(
                        noRequest(
                            DurableSyncNoRequestReason.INCONSISTENT_BOOTSTRAP_AUTHORITY,
                        ),
                        decide(
                            auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
                            stream = stream,
                            session = true,
                            outbox = outbox,
                        ),
                    )
                }
            }
    }

    @Test
    fun pullIsNeverCreatedWithoutAllPullAuthorities() {
        for (auth in DurableSyncPlannerAuth.entries) {
            for (stream in DurableSyncPlannerStream.entries) {
                for (session in listOf(false, true)) {
                    for (outbox in listOf(false, true)) {
                        val plan = decide(auth, stream, session, outbox)
                        if (plan == DurableSyncRequestPlan.CreatePull) {
                            assertEquals(DurableSyncPlannerAuth.ACTIVE_CURRENT, auth)
                            assertFalse(session)
                            assertTrue(
                                stream in setOf(
                                    DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
                                    DurableSyncPlannerStream.PULLING_WITH_CURSOR,
                                ),
                            )
                            if (stream == DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR) {
                                assertFalse(outbox)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun snapshotCopiesCandidateKindsAndAllRepresentationsStayRedacted() {
        val mutableKinds = mutableSetOf(DurableSyncRequestKind.PUSH)
        val snapshot = DurableSyncRequestPlanningSnapshot(
            auth = DurableSyncPlannerAuth.ACTIVE_CURRENT,
            stream = DurableSyncPlannerStream.INCREMENTAL_WITH_CURSOR,
            activeBootstrapSessionPresent = false,
            actionableOutboxPresent = true,
            retainedExistingRequests = mutableKinds,
        )
        mutableKinds.clear()

        assertEquals(setOf(DurableSyncRequestKind.PUSH), snapshot.retainedExistingRequests)
        assertEquals(
            DurableSyncRequestPlan.RetainExisting(DurableSyncRequestKind.PUSH),
            DurableSyncRequestPlanningPolicy.decide(snapshot),
        )

        val representations = buildList {
            add(snapshot.toString())
            add(DurableSyncRequestPlan.RetainExisting(DurableSyncRequestKind.PUSH).toString())
            add(
                DurableSyncRequestPlan.CreateBootstrap(
                    DurableBootstrapIntentKind.CONTINUE,
                ).toString(),
            )
            add(DurableSyncRequestPlan.CreatePush.toString())
            add(DurableSyncRequestPlan.CreatePull.toString())
            add(noRequest(DurableSyncNoRequestReason.REFRESH_REQUIRED).toString())
        }
        representations.forEach { representation ->
            assertTrue(representation.contains("redacted=true"))
            assertFalse(representation.contains("laa_"))
            assertFalse(representation.contains("lar_"))
            assertFalse(representation.contains("request_body"))
        }
    }

    private fun decide(
        auth: DurableSyncPlannerAuth,
        stream: DurableSyncPlannerStream,
        session: Boolean,
        outbox: Boolean,
        retained: Set<DurableSyncRequestKind> = emptySet(),
        waitingCurrent: Boolean = false,
        waitingAmbiguous: Boolean = false,
        otherOpen: Boolean = false,
    ): DurableSyncRequestPlan = DurableSyncRequestPlanningPolicy.decide(
        DurableSyncRequestPlanningSnapshot(
            auth = auth,
            stream = stream,
            activeBootstrapSessionPresent = session,
            actionableOutboxPresent = outbox,
            retainedExistingRequests = retained,
            currentAuthorityWaitingRefreshPresent = waitingCurrent,
            waitingRefreshMetadataAmbiguous = waitingAmbiguous,
            otherOpenRequestBlocksCreation = otherOpen,
        ),
    )

    private fun noRequest(reason: DurableSyncNoRequestReason) =
        DurableSyncRequestPlan.NoRequest(reason)
}
