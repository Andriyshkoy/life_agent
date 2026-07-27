package ru.andriyshkoy.lifeagent.healthprobe

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException

class CapabilityScanner(private val client: HealthConnectClient) {
    suspend fun scanCore(window: Duration, grantedPermissions: Set<String>): String {
        val scanEnd = Instant.now()
        val scanStart = scanEnd.minus(window)

        val sleep =
            if (ProbePermissions.sleep in grantedPermissions) {
                readSleep(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val heartRate =
            if (ProbePermissions.heartRate in grantedPermissions) {
                readHeartRate(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val restingHeartRate =
            if (ProbePermissions.restingHeartRate in grantedPermissions) {
                readRestingHeartRate(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        return buildString {
            appendLine("LIFE_AGENT_HEALTH_CONNECT_CAPABILITY_REPORT")
            appendLine("format_version=1")
            appendLine("probe_version=0.2.0")
            appendLine("scan_kind=core")
            appendLine("window=${windowLabel(window)}")
            appendLine("window_start_utc_hour=${roundedHour(scanStart)}")
            appendLine("window_end_utc_hour=${roundedHour(scanEnd)}")
            appendLine(
                "privacy=no_hr_values,no_record_ids,no_exact_timestamps,no_titles_or_notes," +
                    "no_non_ohealth_package_names",
            )
            appendLine("expected_ohealth_origin=$OHEALTH_PACKAGE")
            appendLine()
            appendSection("SLEEP", sleep)
            appendLine()
            appendSection("HEART_RATE", heartRate)
            appendLine()
            appendSection("RESTING_HEART_RATE", restingHeartRate)
        }.trimEnd()
    }

    suspend fun scanExtended(grantedPermissions: Set<String>): String {
        val window = Duration.ofDays(30)
        val scanEnd = Instant.now()
        val scanStart = scanEnd.minus(window)

        val heartRateVariability =
            if (ProbePermissions.heartRateVariability in grantedPermissions) {
                readHeartRateVariability(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val oxygenSaturation =
            if (ProbePermissions.oxygenSaturation in grantedPermissions) {
                readOxygenSaturation(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val respiratoryRate =
            if (ProbePermissions.respiratoryRate in grantedPermissions) {
                readRespiratoryRate(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val exercise =
            if (ProbePermissions.exercise in grantedPermissions) {
                readExercise(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val steps =
            if (ProbePermissions.steps in grantedPermissions) {
                readSteps(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val stepsCadence =
            if (ProbePermissions.stepsCadence in grantedPermissions) {
                readStepsCadence(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val distance =
            if (ProbePermissions.distance in grantedPermissions) {
                readDistance(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val activeCalories =
            if (ProbePermissions.activeCalories in grantedPermissions) {
                readActiveCalories(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val totalCalories =
            if (ProbePermissions.totalCalories in grantedPermissions) {
                readTotalCalories(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        val speed =
            if (ProbePermissions.speed in grantedPermissions) {
                readSpeed(scanStart, scanEnd)
            } else {
                SectionResult.PermissionMissing
            }

        return buildString {
            appendLine("LIFE_AGENT_HEALTH_CONNECT_CAPABILITY_REPORT")
            appendLine("format_version=1")
            appendLine("probe_version=0.2.0")
            appendLine("scan_kind=extended")
            appendLine("window=last_30d")
            appendLine("window_start_utc_hour=${roundedHour(scanStart)}")
            appendLine("window_end_utc_hour=${roundedHour(scanEnd)}")
            appendLine(
                "privacy=no_measurement_values,no_routes,no_record_ids," +
                    "no_exact_timestamps,no_titles_or_notes,no_non_ohealth_package_names",
            )
            appendLine("expected_ohealth_origin=$OHEALTH_PACKAGE")
            appendLine()
            appendSection("HEART_RATE_VARIABILITY_RMSSD", heartRateVariability)
            appendLine()
            appendSection("OXYGEN_SATURATION", oxygenSaturation)
            appendLine()
            appendSection("RESPIRATORY_RATE", respiratoryRate)
            appendLine()
            appendSection("EXERCISE_SESSION", exercise)
            appendLine()
            appendSection("STEPS", steps)
            appendLine()
            appendSection("STEPS_CADENCE", stepsCadence)
            appendLine()
            appendSection("DISTANCE", distance)
            appendLine()
            appendSection("ACTIVE_CALORIES_BURNED", activeCalories)
            appendLine()
            appendSection("TOTAL_CALORIES_BURNED", totalCalories)
            appendLine()
            appendSection("SPEED", speed)
        }.trimEnd()
    }

    private suspend fun readSleep(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SleepSummary()
            forEachPage<SleepSessionRecord>(start, end) { records ->
                records.forEach(summary::accept)
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readHeartRate(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = HeartRateSummary()
            forEachPage<HeartRateRecord>(start, end) { records ->
                records.forEach(summary::accept)
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readRestingHeartRate(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = RestingHeartRateSummary()
            forEachPage<RestingHeartRateRecord>(start, end) { records ->
                records.forEach(summary::accept)
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readHeartRateVariability(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<HeartRateVariabilityRmssdRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.time, record.time)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readOxygenSaturation(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<OxygenSaturationRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.time, record.time)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readRespiratoryRate(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<RespiratoryRateRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.time, record.time)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readExercise(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = ExerciseSummary()
            forEachPage<ExerciseSessionRecord>(start, end) { records ->
                records.forEach(summary::accept)
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readSteps(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<StepsRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.startTime, record.endTime)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readStepsCadence(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SampledSummary()
            forEachPage<StepsCadenceRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(
                        record.metadata,
                        record.startTime,
                        record.endTime,
                        record.samples.size,
                    )
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readDistance(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<DistanceRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.startTime, record.endTime)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readActiveCalories(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<ActiveCaloriesBurnedRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.startTime, record.endTime)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readTotalCalories(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SimpleSummary()
            forEachPage<TotalCaloriesBurnedRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(record.metadata, record.startTime, record.endTime)
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend fun readSpeed(start: Instant, end: Instant): SectionResult =
        safelyRead {
            val summary = SampledSummary()
            forEachPage<SpeedRecord>(start, end) { records ->
                records.forEach { record ->
                    summary.accept(
                        record.metadata,
                        record.startTime,
                        record.endTime,
                        record.samples.size,
                    )
                }
            }
            SectionResult.Success(summary.lines())
        }

    private suspend inline fun <reified T : Record> forEachPage(
        start: Instant,
        end: Instant,
        crossinline consume: (List<T>) -> Unit,
    ) {
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()

        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest<T>(
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = PAGE_SIZE,
                        pageToken = pageToken,
                    ),
                )
            consume(response.records)

            val next = response.pageToken
            if (next != null && !seenTokens.add(next)) {
                throw RepeatedPageTokenException()
            }
            pageToken = next
        } while (pageToken != null)
    }

    private suspend fun safelyRead(block: suspend () -> SectionResult): SectionResult =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            SectionResult.Error("permission_or_policy_denied")
        } catch (io: IOException) {
            SectionResult.Error("provider_storage_error")
        } catch (remote: RemoteException) {
            SectionResult.Error("provider_ipc_error")
        } catch (repeated: RepeatedPageTokenException) {
            SectionResult.Error("provider_repeated_page_token")
        } catch (state: IllegalStateException) {
            SectionResult.Error("provider_unavailable_or_rate_limited")
        } catch (unexpected: Exception) {
            SectionResult.Error("unexpected_${unexpected.javaClass.simpleName}")
        }

    private fun StringBuilder.appendSection(name: String, result: SectionResult) {
        appendLine("[$name]")
        when (result) {
            SectionResult.PermissionMissing -> appendLine("status=permission_missing")
            is SectionResult.Error -> {
                appendLine("status=read_error")
                appendLine("error_code=${result.code}")
            }
            is SectionResult.Success -> {
                appendLine("status=ok")
                result.lines.forEach(::appendLine)
            }
        }
    }

    private sealed interface SectionResult {
        data object PermissionMissing : SectionResult
        data class Error(val code: String) : SectionResult
        data class Success(val lines: List<String>) : SectionResult
    }

    private class SleepSummary {
        private val common = CommonSummary()
        private val stageTypes = mutableMapOf<String, Long>()
        private val stagesByOrigin = mutableMapOf<String, Long>()
        private val stageTypesByOrigin = mutableMapOf<String, MutableMap<String, Long>>()
        private var stageCount = 0L

        fun accept(record: SleepSessionRecord) {
            val origin = common.accept(record.metadata, record.startTime, record.endTime)
            record.stages.forEach { stage ->
                val label = stageLabel(stage.stage)
                stageCount++
                stageTypes.merge(label, 1L, Long::plus)
                stagesByOrigin.merge(origin, 1L, Long::plus)
                stageTypesByOrigin
                    .getOrPut(origin) { mutableMapOf() }
                    .merge(label, 1L, Long::plus)
            }
        }

        fun lines(): List<String> =
            buildList {
                add("record_count=${common.recordCount}")
                add("stage_count=$stageCount")
                add("coverage_utc_hour=${common.coverage()}")
                add("ohealth_origin_found=${common.hasOrigin(OHEALTH_PACKAGE).yesNo()}")
                add("stage_type_counts=${formatCounts(stageTypes)}")
                add("ohealth_stage_count=${stagesByOrigin[OHEALTH_PACKAGE] ?: 0L}")
                add(
                    "ohealth_stage_type_counts=" +
                        formatCounts(stageTypesByOrigin[OHEALTH_PACKAGE].orEmpty()),
                )
                addAll(common.metadataLines())
                addAll(common.originLines(extraName = "stages", extraByOrigin = stagesByOrigin))
            }
    }

    private class HeartRateSummary {
        private val common = CommonSummary()
        private val samplesByOrigin = mutableMapOf<String, Long>()
        private var sampleCount = 0L

        fun accept(record: HeartRateRecord) {
            val origin = common.accept(record.metadata, record.startTime, record.endTime)
            val samples = record.samples.size.toLong()
            sampleCount += samples
            samplesByOrigin.merge(origin, samples, Long::plus)
        }

        fun lines(): List<String> =
            buildList {
                add("record_count=${common.recordCount}")
                add("sample_count=$sampleCount")
                add("coverage_utc_hour=${common.coverage()}")
                add("ohealth_origin_found=${common.hasOrigin(OHEALTH_PACKAGE).yesNo()}")
                addAll(common.metadataLines())
                addAll(common.originLines("samples", samplesByOrigin))
            }
    }

    private class RestingHeartRateSummary {
        private val common = CommonSummary()

        fun accept(record: RestingHeartRateRecord) {
            common.accept(record.metadata, record.time, record.time)
        }

        fun lines(): List<String> =
            buildList {
                add("record_count=${common.recordCount}")
                add("coverage_utc_hour=${common.coverage()}")
                add("ohealth_origin_found=${common.hasOrigin(OHEALTH_PACKAGE).yesNo()}")
                addAll(common.metadataLines())
                addAll(common.originLines())
            }
    }

    private class SimpleSummary {
        private val common = CommonSummary()

        fun accept(metadata: Metadata, start: Instant, end: Instant) {
            common.accept(metadata, start, end)
        }

        fun lines(): List<String> =
            buildList {
                add("record_count=${common.recordCount}")
                add("coverage_utc_hour=${common.coverage()}")
                add("ohealth_origin_found=${common.hasOrigin(OHEALTH_PACKAGE).yesNo()}")
                addAll(common.metadataLines())
                addAll(common.originLines())
            }
    }

    private class SampledSummary {
        private val common = CommonSummary()
        private val samplesByOrigin = mutableMapOf<String, Long>()
        private var sampleCount = 0L

        fun accept(metadata: Metadata, start: Instant, end: Instant, samples: Int) {
            val origin = common.accept(metadata, start, end)
            val sampleCountForRecord = samples.toLong()
            sampleCount += sampleCountForRecord
            samplesByOrigin.merge(origin, sampleCountForRecord, Long::plus)
        }

        fun lines(): List<String> =
            buildList {
                add("record_count=${common.recordCount}")
                add("sample_count=$sampleCount")
                add("coverage_utc_hour=${common.coverage()}")
                add("ohealth_origin_found=${common.hasOrigin(OHEALTH_PACKAGE).yesNo()}")
                addAll(common.metadataLines())
                addAll(common.originLines("samples", samplesByOrigin))
            }
    }

    private class ExerciseSummary {
        private val common = CommonSummary()
        private val exerciseTypes = mutableMapOf<String, Long>()
        private val exerciseTypesByOrigin =
            mutableMapOf<String, MutableMap<String, Long>>()
        private val lapsByOrigin = mutableMapOf<String, Long>()
        private val segmentsByOrigin = mutableMapOf<String, Long>()
        private var lapCount = 0L
        private var segmentCount = 0L

        fun accept(record: ExerciseSessionRecord) {
            val origin = common.accept(record.metadata, record.startTime, record.endTime)
            val exerciseType = "enum_${record.exerciseType}"
            val laps = record.laps.size.toLong()
            val segments = record.segments.size.toLong()

            exerciseTypes.merge(exerciseType, 1L, Long::plus)
            exerciseTypesByOrigin
                .getOrPut(origin) { mutableMapOf() }
                .merge(exerciseType, 1L, Long::plus)
            lapCount += laps
            segmentCount += segments
            lapsByOrigin.merge(origin, laps, Long::plus)
            segmentsByOrigin.merge(origin, segments, Long::plus)
        }

        fun lines(): List<String> {
            val otherExerciseTypes = mutableMapOf<String, Long>()
            exerciseTypesByOrigin
                .filterKeys { it != OHEALTH_PACKAGE }
                .values
                .forEach { counts ->
                    counts.forEach { (type, count) ->
                        otherExerciseTypes.merge(type, count, Long::plus)
                    }
                }
            val otherLaps =
                lapsByOrigin.filterKeys { it != OHEALTH_PACKAGE }.values.sum()
            val otherSegments =
                segmentsByOrigin.filterKeys { it != OHEALTH_PACKAGE }.values.sum()

            return buildList {
                add("record_count=${common.recordCount}")
                add("lap_count=$lapCount")
                add("segment_count=$segmentCount")
                add("coverage_utc_hour=${common.coverage()}")
                add("ohealth_origin_found=${common.hasOrigin(OHEALTH_PACKAGE).yesNo()}")
                add("exercise_type_enum_counts=${formatCounts(exerciseTypes)}")
                add("ohealth_lap_count=${lapsByOrigin[OHEALTH_PACKAGE] ?: 0L}")
                add("ohealth_segment_count=${segmentsByOrigin[OHEALTH_PACKAGE] ?: 0L}")
                add(
                    "ohealth_exercise_type_enum_counts=" +
                        formatCounts(exerciseTypesByOrigin[OHEALTH_PACKAGE].orEmpty()),
                )
                add("other_lap_count=$otherLaps")
                add("other_segment_count=$otherSegments")
                add(
                    "other_exercise_type_enum_counts=" +
                        formatCounts(otherExerciseTypes),
                )
                addAll(common.metadataLines())
                addAll(common.originLines())
            }
        }
    }

    private class CommonSummary {
        var recordCount: Long = 0
            private set
        private var earliest: Instant? = null
        private var latest: Instant? = null
        private var dataOriginPresent = 0L
        private var devicePresent = 0L
        private var manufacturerPresent = 0L
        private var modelPresent = 0L
        private val recordingMethods = mutableMapOf<String, Long>()
        private val origins = mutableMapOf<String, OriginSummary>()

        fun accept(metadata: Metadata, start: Instant, end: Instant): String {
            recordCount++
            earliest = minInstant(earliest, start)
            latest = maxInstant(latest, end)

            val packageName =
                metadata.dataOrigin.packageName.ifBlank { UNKNOWN_ORIGIN }
            if (packageName != UNKNOWN_ORIGIN) {
                dataOriginPresent++
            }

            val device = metadata.device
            if (device != null) {
                devicePresent++
                if (!device.manufacturer.isNullOrBlank()) manufacturerPresent++
                if (!device.model.isNullOrBlank()) modelPresent++
            }

            val recordingMethod = recordingMethodLabel(metadata.recordingMethod)
            recordingMethods.merge(recordingMethod, 1L, Long::plus)
            origins
                .getOrPut(packageName, ::OriginSummary)
                .accept(start, end, device != null, recordingMethod)
            return packageName
        }

        fun coverage(): String =
            if (earliest == null || latest == null) {
                "none"
            } else {
                "${roundedHour(earliest!!)}..${roundedHour(latest!!)}"
            }

        fun hasOrigin(packageName: String): Boolean = packageName in origins

        fun metadataLines(): List<String> =
            listOf(
                "data_origin_present_records=$dataOriginPresent",
                "device_present_records=$devicePresent",
                "device_manufacturer_present_records=$manufacturerPresent",
                "device_model_present_records=$modelPresent",
                "recording_method_counts=${formatCounts(recordingMethods)}",
            )

        fun originLines(
            extraName: String? = null,
            extraByOrigin: Map<String, Long>? = null,
        ): List<String> {
            if (origins.isEmpty()) return listOf("origins=none")

            val ohealth = origins[OHEALTH_PACKAGE] ?: OriginSummary()
            val otherEntries = origins.filterKeys { it != OHEALTH_PACKAGE }
            val other = OriginSummary().apply {
                otherEntries.values.forEach { summary -> merge(summary) }
            }
            return buildList {
                add("origin_count=${origins.size}")
                add("ohealth_origin_package=$OHEALTH_PACKAGE")
                add("ohealth_origin_records=${ohealth.records}")
                if (extraName != null && extraByOrigin != null) {
                    add(
                        "ohealth_origin_${extraName}=" +
                            (extraByOrigin[OHEALTH_PACKAGE] ?: 0L),
                    )
                }
                add("ohealth_origin_coverage_utc_hour=${ohealth.coverage()}")
                add("ohealth_origin_device_present_records=${ohealth.devicePresent}")
                add(
                    "ohealth_origin_recording_method_counts=" +
                        formatCounts(ohealth.recordingMethods),
                )

                add("other_origin_count=${otherEntries.size}")
                add("other_origin_records=${other.records}")
                if (extraName != null && extraByOrigin != null) {
                    val otherExtra =
                        extraByOrigin
                            .filterKeys { it != OHEALTH_PACKAGE }
                            .values
                            .sum()
                    add("other_origin_${extraName}=$otherExtra")
                }
                add("other_origin_coverage_utc_hour=${other.coverage()}")
                add("other_origin_device_present_records=${other.devicePresent}")
                add(
                    "other_origin_recording_method_counts=" +
                        formatCounts(other.recordingMethods),
                )
            }
        }
    }

    private class OriginSummary {
        var records = 0L
            private set
        var devicePresent = 0L
            private set
        var earliest: Instant? = null
            private set
        var latest: Instant? = null
            private set
        val recordingMethods = mutableMapOf<String, Long>()

        fun accept(start: Instant, end: Instant, hasDevice: Boolean, recordingMethod: String) {
            records++
            if (hasDevice) devicePresent++
            earliest = minInstant(earliest, start)
            latest = maxInstant(latest, end)
            recordingMethods.merge(recordingMethod, 1L, Long::plus)
        }

        fun merge(other: OriginSummary) {
            records += other.records
            devicePresent += other.devicePresent
            if (other.earliest != null) {
                earliest = minInstant(earliest, other.earliest!!)
            }
            if (other.latest != null) {
                latest = maxInstant(latest, other.latest!!)
            }
            other.recordingMethods.forEach { (method, count) ->
                recordingMethods.merge(method, count, Long::plus)
            }
        }

        fun coverage(): String =
            if (earliest == null || latest == null) {
                "none"
            } else {
                "${roundedHour(earliest!!)}..${roundedHour(latest!!)}"
            }
    }

    private class RepeatedPageTokenException : IllegalStateException()

    companion object {
        private const val PAGE_SIZE = 1000
        private const val OHEALTH_PACKAGE = "com.heytap.health.international"
        private const val UNKNOWN_ORIGIN = "<unknown-origin>"

        private fun roundedHour(instant: Instant): String =
            instant.truncatedTo(ChronoUnit.HOURS).toString()

        private fun windowLabel(duration: Duration): String =
            when (duration) {
                Duration.ofHours(48) -> "last_48h"
                Duration.ofDays(30) -> "last_30d"
                else -> "seconds_${duration.seconds}"
            }

        private fun minInstant(current: Instant?, candidate: Instant): Instant =
            if (current == null || candidate.isBefore(current)) candidate else current

        private fun maxInstant(current: Instant?, candidate: Instant): Instant =
            if (current == null || candidate.isAfter(current)) candidate else current

        private fun Boolean.yesNo(): String = if (this) "yes" else "no"

        private fun formatCounts(values: Map<String, Long>): String =
            if (values.isEmpty()) {
                "none"
            } else {
                values.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
            }

        private fun recordingMethodLabel(value: Int): String =
            when (value) {
                Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "active"
                Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "automatic"
                Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "manual"
                Metadata.RECORDING_METHOD_UNKNOWN -> "unknown"
                else -> "unrecognized_$value"
            }

        private fun stageLabel(value: Int): String =
            when (value) {
                SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "unknown"
                SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping_unspecified"
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
                SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
                else -> "unrecognized_$value"
            }
    }
}
