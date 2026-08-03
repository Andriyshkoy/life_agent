package ru.andriyshkoy.lifeagent.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import ru.andriyshkoy.lifeagent.ui.components.DetailTopBar
import ru.andriyshkoy.lifeagent.ui.components.PrimaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.SecondaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.StatusCard
import ru.andriyshkoy.lifeagent.ui.sync.LocalOnlySyncSetupController
import ru.andriyshkoy.lifeagent.ui.sync.SyncBootstrapUiStatus
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupController
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupErrorReason
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupNotice
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupSummary
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupUiState

private const val ENROLLMENT_CODE_SYMBOL_COUNT = 28
private const val ENROLLMENT_CODE_GROUP_SIZE = 4
private val ENROLLMENT_CODE_SYMBOLS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toSet()
private val ENROLLMENT_CODE_PATTERN = Regex("^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){6}$")

@Composable
fun SyncSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    controller: SyncSetupController? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val fallbackController = remember { LocalOnlySyncSetupController() }
    val activeController = controller ?: fallbackController
    val state by activeController.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val localView = LocalView.current
    val enrollmentInProgress = state is SyncSetupUiState.Enrolling

    DisposableEffect(localView) {
        val window = localView.context.findActivity()?.window
        val wasSecure = (
            window
                ?.attributes
                ?.flags
                ?.and(WindowManager.LayoutParams.FLAG_SECURE) ?: 0
            ) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    BackHandler(enabled = enrollmentInProgress) {
        // A one-shot enrollment must settle before this surface can be left.
    }

    LaunchedEffect(activeController) {
        activeController.notices.collectLatest { notice ->
            snackbarHostState.showSnackbar(notice.message())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            DetailTopBar(
                title = "Синхронизация",
                onBack = onBack,
                actionsEnabled = !enrollmentInProgress,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val current = state) {
            SyncSetupUiState.Loading -> SyncSetupLoading(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )

            is SyncSetupUiState.LocalOnly -> SyncSetupLocalOnly(
                summary = current.summary,
                onConnect = activeController::showCodeEntry,
                onBack = onBack,
                contentPadding = padding,
            )

            is SyncSetupUiState.CodeEntry -> SyncSetupCodeEntry(
                summary = current.summary,
                onSubmit = activeController::submitEnrollment,
                onCancel = activeController::cancelCodeEntry,
                contentPadding = padding,
            )

            is SyncSetupUiState.Enrolling -> SyncSetupEnrolling(
                summary = current.summary,
                contentPadding = padding,
            )

            is SyncSetupUiState.Error -> SyncSetupError(
                state = current,
                onEnterNewCode = activeController::showCodeEntry,
                onBack = onBack,
                contentPadding = padding,
            )

            is SyncSetupUiState.Ready -> SyncSetupReady(
                summary = current.summary,
                zoneId = zoneId,
                onEnqueue = activeController::enqueueNow,
                onBack = onBack,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun SyncSetupLoading(modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text("Проверяем состояние синхронизации…")
    }
}

@Composable
private fun SyncSetupLocalOnly(
    summary: SyncSetupSummary,
    onConnect: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    SyncSetupList(contentPadding) {
        item {
            StatusCard(
                title = "Только на этом устройстве",
                subtitle = "Локальные записи работают; на сервер ничего не отправляется.",
                icon = Icons.Rounded.CloudOff,
            )
        }
        item { PendingCountCard(summary.pendingCount) }
        item {
            PrimaryActionButton(
                text = "Подключить сервер",
                onClick = onConnect,
                icon = Icons.Rounded.Sync,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton("Назад", onBack)
        }
    }
}

@Composable
private fun SyncSetupCodeEntry(
    summary: SyncSetupSummary,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
    contentPadding: PaddingValues,
) {
    var code by remember { mutableStateOf("") }
    val symbolCount = code.count { it != '-' }
    val valid = isEnrollmentCodeReady(code)
    val submit = {
        if (valid) {
            val ownedCode = code.toCharArray()
            code = ""
            onSubmit(ownedCode)
        }
    }

    SyncSetupList(contentPadding) {
        item {
            StatusCard(
                title = "Одноразовое подключение",
                subtitle = "Код используется только для этого запроса и не сохраняется.",
                icon = Icons.Rounded.Sync,
            )
        }
        item {
            OutlinedTextField(
                value = code,
                onValueChange = { code = normalizeEnrollmentCodeInput(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Код подключения") },
                supportingText = {
                    Text("7 групп по 4 символа · введено $symbolCount из 28")
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }
        item { PendingCountCard(summary.pendingCount) }
        item {
            PrimaryActionButton(
                text = "Подключить",
                onClick = submit,
                enabled = valid,
                icon = Icons.Rounded.Sync,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton("Отмена", onCancel)
        }
    }
}

@Composable
private fun SyncSetupEnrolling(
    summary: SyncSetupSummary,
    contentPadding: PaddingValues,
) {
    SyncSetupList(contentPadding) {
        item {
            StatusCard(
                title = "Подключаем сервер…",
                subtitle = "Не закрывайте приложение до завершения одноразового запроса.",
                icon = Icons.Rounded.Sync,
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }
        item { PendingCountCard(summary.pendingCount) }
    }
}

@Composable
private fun SyncSetupError(
    state: SyncSetupUiState.Error,
    onEnterNewCode: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    SyncSetupList(contentPadding) {
        item {
            StatusCard(
                title = state.reason.title(),
                subtitle = state.reason.description(),
                icon = Icons.Rounded.ErrorOutline,
            )
        }
        item { PendingCountCard(state.summary.pendingCount) }
        item {
            PrimaryActionButton(
                text = "Ввести новый код",
                onClick = onEnterNewCode,
                icon = Icons.Rounded.Sync,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton("Назад", onBack)
        }
    }
}

@Composable
private fun SyncSetupReady(
    summary: SyncSetupSummary,
    zoneId: ZoneId,
    onEnqueue: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    SyncSetupList(contentPadding) {
        item {
            StatusCard(
                title = "Синхронизация подключена",
                subtitle = "Локальные изменения отправляются через защищённую очередь.",
                icon = Icons.Rounded.Sync,
            )
        }
        item { PendingCountCard(summary.pendingCount) }
        item {
            SyncMetricCard(
                title = "Начальная загрузка",
                value = summary.bootstrap.userLabel(),
            )
        }
        item {
            SyncMetricCard(
                title = "Последнее подтверждение сервера",
                value = formatServerConfirmation(summary.lastServerConfirmationAt, zoneId),
            )
        }
        item {
            PrimaryActionButton(
                text = "Синхронизировать сейчас",
                onClick = onEnqueue,
                icon = Icons.Rounded.Sync,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton("Назад", onBack)
        }
    }
}

@Composable
private fun PendingCountCard(pendingCount: Int) {
    SyncMetricCard(
        title = "Ожидают отправки",
        value = pendingCount.toString(),
    )
}

@Composable
private fun SyncMetricCard(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SyncSetupList(
    contentPadding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .padding(contentPadding)
            .fillMaxSize()
            .widthIn(max = 680.dp)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

internal fun normalizeEnrollmentCodeInput(raw: String): String {
    val symbols = raw
        .uppercase(Locale.ROOT)
        .asSequence()
        .filter(ENROLLMENT_CODE_SYMBOLS::contains)
        .take(ENROLLMENT_CODE_SYMBOL_COUNT)
        .toList()
    return symbols.chunked(ENROLLMENT_CODE_GROUP_SIZE).joinToString("-") { it.joinToString("") }
}

internal fun isEnrollmentCodeReady(value: String): Boolean = ENROLLMENT_CODE_PATTERN.matches(value)

internal fun syncSettingsSubtitle(state: SyncSetupUiState): String = when (state) {
    SyncSetupUiState.Loading -> "Проверяем состояние…"
    is SyncSetupUiState.LocalOnly,
    is SyncSetupUiState.CodeEntry,
    -> "Не настроена · локальная работа доступна"

    is SyncSetupUiState.Enrolling -> "Подключаем сервер…"
    is SyncSetupUiState.Error -> "Требует внимания · локальные данные доступны"
    is SyncSetupUiState.Ready -> if (state.summary.pendingCount == 0) {
        "Подключена · очередь пуста"
    } else {
        "Подключена · в очереди ${state.summary.pendingCount}"
    }
}

internal fun formatServerConfirmation(
    instant: Instant?,
    zoneId: ZoneId,
    locale: Locale = Locale.forLanguageTag("ru"),
): String = instant?.let {
    DateTimeFormatter
        .ofPattern("d MMMM yyyy, HH:mm", locale)
        .withZone(zoneId)
        .format(it)
} ?: "Пока нет"

private fun SyncBootstrapUiStatus.userLabel(): String = when (this) {
    SyncBootstrapUiStatus.UNAVAILABLE -> "Недоступна до подключения"
    SyncBootstrapUiStatus.REQUIRED -> "Ожидает запуска"
    SyncBootstrapUiStatus.IN_PROGRESS -> "Выполняется"
    SyncBootstrapUiStatus.READY -> "Готово"
    SyncBootstrapUiStatus.NEEDS_ATTENTION -> "Требует внимания"
}

private fun SyncSetupErrorReason.title(): String = when (this) {
    SyncSetupErrorReason.INVALID_CODE -> "Проверьте код"
    SyncSetupErrorReason.ENROLLMENT_REJECTED -> "Код не принят"
    SyncSetupErrorReason.NEW_CODE_REQUIRED -> "Нужен новый код"
    SyncSetupErrorReason.LOCAL_UNAVAILABLE -> "Подключение сейчас недоступно"
    SyncSetupErrorReason.STATUS_UNAVAILABLE -> "Не удалось прочитать состояние"
    SyncSetupErrorReason.BUSY -> "Запрос уже выполняется"
}

private fun SyncSetupErrorReason.description(): String = when (this) {
    SyncSetupErrorReason.INVALID_CODE -> "Введите 7 групп по 4 допустимых символа."
    SyncSetupErrorReason.ENROLLMENT_REJECTED -> "Одноразовый код отклонён или уже использован."
    SyncSetupErrorReason.NEW_CODE_REQUIRED -> "Предыдущий результат нельзя подтвердить. Создайте новый одноразовый код."
    SyncSetupErrorReason.LOCAL_UNAVAILABLE -> "Локальные записи доступны. Повторите подключение позже."
    SyncSetupErrorReason.STATUS_UNAVAILABLE -> "Локальные записи не затронуты. Повторите после перезапуска."
    SyncSetupErrorReason.BUSY -> "Дождитесь завершения текущего запроса."
}

private fun SyncSetupNotice.message(): String = when (this) {
    SyncSetupNotice.QUEUED -> "Запрос на синхронизацию принят"
    SyncSetupNotice.NOT_CONFIGURED -> "Сервер синхронизации пока не настроен"
    SyncSetupNotice.MISCONFIGURED -> "Настройки сервера неполные"
    SyncSetupNotice.FAILED -> "Не удалось поставить синхронизацию в очередь"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
