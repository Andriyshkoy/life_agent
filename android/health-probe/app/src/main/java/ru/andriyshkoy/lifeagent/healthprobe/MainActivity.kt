package ru.andriyshkoy.lifeagent.healthprobe

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.toColorInt
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.contracts.HealthPermissionsRequestContract
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private lateinit var providerStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var extendedPermissionStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var reportView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var grantButton: Button
    private lateinit var grantExtendedButton: Button
    private lateinit var scan48hButton: Button
    private lateinit var scan30dButton: Button
    private lateinit var scanExtendedButton: Button
    private lateinit var shareButton: Button

    private var client: HealthConnectClient? = null
    private var grantedPermissions: Set<String> = emptySet()
    private var currentReport: String? = null
    private var isBusy = false

    private val corePermissionLauncher =
        registerForActivityResult(HealthPermissionsRequestContract()) { granted ->
            handlePermissionResult(granted)
        }

    private val extendedPermissionLauncher =
        registerForActivityResult(HealthPermissionsRequestContract()) { granted ->
            handlePermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(buildContent())
        lifecycleScope.launch { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        if (::providerStatus.isInitialized) {
            lifecycleScope.launch { refreshStatus() }
        }
    }

    private fun buildContent(): View {
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor("#F6F7FB".toColorInt())
            }
        applySystemBarInsets(content)

        content.addView(textView("Life Agent · Health Connect day-0", 24f, bold = true))
        content.addSpaced(
            textView(
                "Read-only core probe for sleep and heart rate, plus a separate optional " +
                    "discovery scan for additional vitals and activity records. " +
                    "It does not upload or store data.",
                15f,
            ),
            topDp = 6,
        )

        providerStatus = textView("Health Connect: checking…", 15f, bold = true)
        permissionStatus = textView("Permissions: checking…", 14f)
        extendedPermissionStatus = textView("Optional discovery permissions: checking…", 14f)
        operationStatus = textView("", 14f)
        content.addSpaced(providerStatus, 18)
        content.addSpaced(permissionStatus, 5)
        content.addSpaced(extendedPermissionStatus, 5)
        content.addSpaced(operationStatus, 5)

        grantButton = actionButton("Grant core read permissions").also { button ->
            button.setOnClickListener {
                if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
                    corePermissionLauncher.launch(ProbePermissions.core)
                } else {
                    operationStatus.text = "Health Connect is not available or needs an update."
                }
            }
        }
        grantExtendedButton =
            actionButton("Grant optional discovery permissions").also { button ->
                button.setOnClickListener {
                    if (
                        HealthConnectClient.getSdkStatus(this) ==
                            HealthConnectClient.SDK_AVAILABLE
                    ) {
                        extendedPermissionLauncher.launch(ProbePermissions.extended)
                    } else {
                        operationStatus.text =
                            "Health Connect is not available or needs an update."
                    }
                }
            }
        scan48hButton =
            actionButton("Scan last 48 hours").also { button ->
                button.setOnClickListener { scan(Duration.ofHours(48)) }
            }
        scan30dButton =
            actionButton("Scan last 30 days").also { button ->
                button.setOnClickListener { scan(Duration.ofDays(30)) }
            }
        scanExtendedButton =
            actionButton("Run optional 30-day discovery scan").also { button ->
                button.setOnClickListener { scanExtended() }
            }
        shareButton =
            actionButton("Share capability report").also { button ->
                button.setOnClickListener { shareReport() }
            }
        progress =
            ProgressBar(this).apply {
                isIndeterminate = true
                visibility = View.GONE
            }

        content.addSpaced(grantButton, 16)
        content.addSpaced(scan48hButton)
        content.addSpaced(scan30dButton)
        content.addSpaced(grantExtendedButton, 18)
        content.addSpaced(scanExtendedButton)
        content.addSpaced(shareButton)
        content.addSpaced(progress, 8)

        content.addSpaced(textView("Capability report", 18f, bold = true), 20)
        reportView =
            textView(
                "Run a scan. The report contains counts, origins, rounded coverage, " +
                    "stage/exercise types and metadata presence — never measurement values, " +
                    "routes or exact timestamps.",
                13f,
            ).apply {
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor("#283248".toColorInt())
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor("#FFFFFF".toColorInt())
            }
        content.addSpaced(reportView, 8)

        updateButtons()

        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private suspend fun refreshStatus() {
        val status = HealthConnectClient.getSdkStatus(this)
        when (status) {
            HealthConnectClient.SDK_AVAILABLE -> {
                providerStatus.text = "Health Connect: available"
                try {
                    val healthClient = client ?: HealthConnectClient.getOrCreate(this).also {
                        client = it
                    }
                    val updatedPermissions =
                        healthClient.permissionController.getGrantedPermissions()
                    if (currentReport != null && updatedPermissions != grantedPermissions) {
                        clearReport(
                            "Permissions changed. Run a new scan before sharing a report.",
                        )
                    }
                    grantedPermissions = updatedPermissions
                    val sleep = ProbePermissions.sleep in grantedPermissions
                    val heart = ProbePermissions.heartRate in grantedPermissions
                    val restingHeart = ProbePermissions.restingHeartRate in grantedPermissions
                    permissionStatus.text =
                        "Permissions · Sleep: ${stateLabel(sleep)} · " +
                            "Heart rate: ${stateLabel(heart)} · " +
                            "RHR: ${stateLabel(restingHeart)}"
                    extendedPermissionStatus.text =
                        "Optional · HRV: ${permissionState(ProbePermissions.heartRateVariability)}" +
                            " · SpO2: ${permissionState(ProbePermissions.oxygenSaturation)}" +
                            " · Resp: ${permissionState(ProbePermissions.respiratoryRate)}\n" +
                            "Exercise: ${permissionState(ProbePermissions.exercise)}" +
                            " · Steps/cadence: ${permissionState(ProbePermissions.steps)}" +
                            " · Distance: ${permissionState(ProbePermissions.distance)}\n" +
                            "Active kcal: ${permissionState(ProbePermissions.activeCalories)}" +
                            " · Total kcal: ${permissionState(ProbePermissions.totalCalories)}" +
                            " · Speed: ${permissionState(ProbePermissions.speed)}"
                    operationStatus.text =
                        if (ProbePermissions.core.all { it in grantedPermissions }) {
                            "Ready. Core and optional scans remain separate."
                        } else {
                            "Grant only the read permissions you want. Partial grants remain usable."
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    permissionStatus.text = "Permissions: unable to query"
                    extendedPermissionStatus.text =
                        "Optional discovery permissions: unable to query"
                    operationStatus.text =
                        "Health Connect permission error: ${safeErrorLabel(error)}"
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                providerStatus.text = "Health Connect: install or update required"
                permissionStatus.text = "Permissions: unavailable until provider update"
                extendedPermissionStatus.text =
                    "Optional discovery permissions: unavailable until provider update"
                operationStatus.text =
                    "Update the Health Connect system component/app, then reopen this probe."
                client = null
                grantedPermissions = emptySet()
                clearReport("Health Connect changed state. Run a new scan after it is available.")
            }
            else -> {
                providerStatus.text = "Health Connect: unsupported on this device"
                permissionStatus.text = "Permissions: unavailable"
                extendedPermissionStatus.text = "Optional discovery permissions: unavailable"
                operationStatus.text =
                    "This probe requires Android 9+ with a compatible Health Connect provider."
                client = null
                grantedPermissions = emptySet()
                clearReport("Health Connect changed state. Run a new scan after it is available.")
            }
        }
        updateButtons()
    }

    private fun scan(window: Duration) {
        val healthClient = client
        if (healthClient == null) {
            operationStatus.text = "Health Connect is not ready."
            return
        }
        if (grantedPermissions.none { it in ProbePermissions.core }) {
            operationStatus.text = "Grant at least one read permission before scanning."
            return
        }

        lifecycleScope.launch {
            clearReport("Scan in progress…")
            setBusy(true)
            operationStatus.text = "Scanning all Health Connect pages…"
            try {
                val report =
                    CapabilityScanner(healthClient).scanCore(window, grantedPermissions)
                currentReport = report
                reportView.text = report
                operationStatus.text =
                    "Scan complete. Review and share this privacy-minimized report."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operationStatus.text = "Scan failed: ${safeErrorLabel(error)}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun scanExtended() {
        val healthClient = client
        if (healthClient == null) {
            operationStatus.text = "Health Connect is not ready."
            return
        }
        if (grantedPermissions.none { it in ProbePermissions.extended }) {
            operationStatus.text =
                "Grant at least one optional discovery permission before scanning."
            return
        }

        lifecycleScope.launch {
            clearReport("Optional discovery scan in progress…")
            setBusy(true)
            operationStatus.text = "Scanning 30 days across all permitted optional data types…"
            try {
                val report =
                    CapabilityScanner(healthClient).scanExtended(grantedPermissions)
                currentReport = report
                reportView.text = report
                operationStatus.text =
                    "Optional scan complete. Review and share this privacy-minimized report."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operationStatus.text = "Optional scan failed: ${safeErrorLabel(error)}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun shareReport() {
        val report = currentReport
        if (report == null) {
            operationStatus.text = "Run a scan before sharing."
            return
        }

        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Life Agent Health Connect capability report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
        startActivity(Intent.createChooser(intent, "Share capability report"))
    }

    private fun setBusy(value: Boolean) {
        isBusy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        updateButtons()
    }

    private fun clearReport(message: String) {
        currentReport = null
        if (::reportView.isInitialized) {
            reportView.text = message
        }
        updateButtons()
    }

    private fun handlePermissionResult(newlyGranted: Set<String>) {
        val provisionalPermissions = grantedPermissions + newlyGranted
        if (currentReport != null && provisionalPermissions != grantedPermissions) {
            clearReport("Permissions changed. Run a new scan before sharing a report.")
        }
        grantedPermissions = provisionalPermissions
        lifecycleScope.launch { refreshStatus() }
    }

    private fun updateButtons() {
        if (!::grantButton.isInitialized) return
        val available =
            HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        val hasAnyCorePermission = grantedPermissions.any { it in ProbePermissions.core }
        val hasAnyExtendedPermission = grantedPermissions.any { it in ProbePermissions.extended }
        grantButton.isEnabled = available && !isBusy
        grantExtendedButton.isEnabled = available && !isBusy
        scan48hButton.isEnabled = available && hasAnyCorePermission && !isBusy
        scan30dButton.isEnabled = available && hasAnyCorePermission && !isBusy
        scanExtendedButton.isEnabled = available && hasAnyExtendedPermission && !isBusy
        shareButton.isEnabled = currentReport != null && !isBusy
    }

    private fun stateLabel(granted: Boolean): String = if (granted) "granted" else "missing"

    private fun permissionState(permission: String): String =
        stateLabel(permission in grantedPermissions)

    private fun safeErrorLabel(error: Exception): String =
        when (error) {
            is SecurityException -> "permission or policy denied"
            is java.io.IOException -> "provider storage error"
            is android.os.RemoteException -> "provider IPC error"
            is IllegalStateException -> "provider unavailable or rate-limited"
            else -> error.javaClass.simpleName
        }
}
