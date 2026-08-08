package ru.andriyshkoy.lifeagent.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.DemoRoute
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun availableExportDisclosesThatJsonFileIsNotEncrypted() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                SettingsScreen(
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                    onNavigate = {},
                    persistenceAvailable = true,
                )
            }
        }

        composeRule.onNodeWithText("Локальный журнал · JSON без шифрования").assertIsDisplayed()
        composeRule.onNodeWithText("Экспорт").assertIsEnabled()
    }

    @Test
    fun exportIsDisabledWhenEncryptedStorageIsUnavailable() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                SettingsScreen(
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                    onNavigate = {},
                    persistenceAvailable = false,
                )
            }
        }

        composeRule.onNodeWithText("Хранилище недоступно").assertIsDisplayed()
        composeRule.onNodeWithText("Экспорт").assertIsNotEnabled()
    }

    @Test
    fun privacyRowOpensReadOnlyPrivacySurface() {
        var destination: DemoRoute? = null
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                SettingsScreen(
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                    onNavigate = { destination = it },
                    persistenceAvailable = true,
                )
            }
        }

        composeRule.onNodeWithText("Как хранятся локальные данные").assertIsDisplayed()
        composeRule.onNodeWithText("Приватность").performClick()

        composeRule.runOnIdle {
            assertEquals(DemoRoute.Privacy, destination)
        }
    }

    @Test
    fun healthConnectScreenIsAnExplicitDisabledPreview() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                HealthConnectScreen(onBack = {})
            }
        }

        composeRule.onNodeWithText(
            "Интеграция Health Connect ещё не реализована; сейчас данные не читаются.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Выдать доступ").assertIsNotEnabled()
    }

    @Test
    fun systemRowsExposeTimeZoneAndDiagnosticsRoutes() {
        val destinations = mutableListOf<DemoRoute>()
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                SettingsScreen(
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                    onNavigate = destinations::add,
                    persistenceAvailable = true,
                    zoneId = ZoneId.of("Asia/Novosibirsk"),
                    appVersion = "0.1.0-test",
                )
            }
        }

        composeRule.onNodeWithText("Asia/Novosibirsk").assertIsDisplayed()
        composeRule.onNodeWithText("Часовой пояс").performScrollTo().performClick()
        composeRule.onNodeWithText("Диагностика").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(DemoRoute.TimeZone, DemoRoute.Diagnostics),
                destinations,
            )
        }
    }

    @Test
    fun timeZoneSurfaceUsesInjectedDeviceClockAndHasOnlyBackAction() {
        var backCount = 0
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                TimeZoneScreen(
                    onBack = { backCount += 1 },
                    clock = Clock.fixed(
                        Instant.parse("2026-07-29T03:00:00Z"),
                        ZoneOffset.UTC,
                    ),
                    zoneId = ZoneId.of("Asia/Novosibirsk"),
                )
            }
        }

        composeRule.onNodeWithText("Asia/Novosibirsk").assertIsDisplayed()
        composeRule.onNodeWithText("Текущий пояс устройства · UTC+07:00").assertIsDisplayed()
        composeRule.onNodeWithText("29 июля 2026, 10:00").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Каждое событие хранит точный момент (instant)",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "в системных настройках Android",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)

        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    @Test
    fun diagnosticsSurfaceShowsOnlyVersionAndLocalStorageState() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                DiagnosticsScreen(
                    onBack = {},
                    encryptedStorageAvailable = true,
                    appVersion = "0.1.0-test",
                )
            }
        }

        composeRule.onNodeWithText("Зашифрованное хранилище доступно").assertIsDisplayed()
        composeRule.onNodeWithText("Локальная база открыта и готова к записи").assertIsDisplayed()
        composeRule.onNodeWithText("0.1.0-test").assertIsDisplayed()
        composeRule.onNodeWithText("Локальное хранилище").assertIsDisplayed()
        composeRule.onNodeWithText(
            "ID, логи и значения здоровья здесь не отображаются.",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun diagnosticsSurfaceReportsUnavailableEncryptedStorage() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                DiagnosticsScreen(
                    onBack = {},
                    encryptedStorageAvailable = false,
                    appVersion = "0.1.0-test",
                )
            }
        }

        composeRule.onNodeWithText("Зашифрованное хранилище недоступно").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Чтение и запись локальных данных отключены",
        ).assertIsDisplayed()
    }

    @Test
    fun privacySurfaceExplainsLocalAndExportDeletionSeparately() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                PrivacyScreen(onBack = {})
            }
        }

        composeRule.onNodeWithText(
            "Отдельной кнопки удаления внутри Life Agent пока нет.",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "удаление приложения удаляет локальную базу",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "JSON-файлы без шифрования нужно удалить отдельно",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
}
