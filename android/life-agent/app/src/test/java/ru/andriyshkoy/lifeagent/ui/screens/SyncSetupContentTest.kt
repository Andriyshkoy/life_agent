package ru.andriyshkoy.lifeagent.ui.screens

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.sync.SyncBootstrapUiStatus
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupSummary
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupUiState

class SyncSetupContentTest {
    @Test
    fun `input is canonicalized to seven unambiguous groups`() {
        assertEquals(
            "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345",
            normalizeEnrollmentCodeInput(
                "abcd efgh-ijklm-npqr-stuv-wxyz-2345-extra",
            ),
        )
        assertTrue(isEnrollmentCodeReady("ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345"))
        assertFalse(isEnrollmentCodeReady("ABCD-EFGH-I0O1-NPQR-STUV-WXYZ-2345"))
    }

    @Test
    fun `server confirmation uses injected local zone`() {
        assertEquals(
            "3 августа 2026, 11:05",
            formatServerConfirmation(
                instant = Instant.parse("2026-08-03T04:05:00Z"),
                zoneId = ZoneId.of("Asia/Novosibirsk"),
            ),
        )
        assertEquals(
            "Пока нет",
            formatServerConfirmation(null, ZoneId.of("Asia/Novosibirsk")),
        )
    }

    @Test
    fun `settings subtitle never claims that accepted work has completed`() {
        assertEquals(
            "Подключена · в очереди 2",
            syncSettingsSubtitle(
                SyncSetupUiState.Ready(
                    SyncSetupSummary(
                        pendingCount = 2,
                        bootstrap = SyncBootstrapUiStatus.IN_PROGRESS,
                        lastServerConfirmationAt = null,
                    ),
                ),
            ),
        )
        assertEquals(
            "Требует внимания · локальные данные доступны",
            syncSettingsSubtitle(
                SyncSetupUiState.Error(
                    SyncSetupSummary.Empty,
                    ru.andriyshkoy.lifeagent.ui.sync.SyncSetupErrorReason.NEW_CODE_REQUIRED,
                ),
            ),
        )
    }
}
