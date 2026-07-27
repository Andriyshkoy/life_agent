package ru.andriyshkoy.lifeagent.healthprobe

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord

object ProbePermissions {
    val sleep: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val heartRate: String = HealthPermission.getReadPermission(HeartRateRecord::class)

    val restingHeartRate: String =
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)

    val core: Set<String> = setOf(sleep, heartRate, restingHeartRate)

    val heartRateVariability: String =
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)

    val oxygenSaturation: String =
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)

    val respiratoryRate: String =
        HealthPermission.getReadPermission(RespiratoryRateRecord::class)

    val exercise: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val steps: String = HealthPermission.getReadPermission(StepsRecord::class)
    val stepsCadence: String = HealthPermission.getReadPermission(StepsCadenceRecord::class)
    val distance: String = HealthPermission.getReadPermission(DistanceRecord::class)

    val activeCalories: String =
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)

    val totalCalories: String =
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)

    val speed: String = HealthPermission.getReadPermission(SpeedRecord::class)

    // Steps and steps cadence intentionally resolve to the same Health Connect permission.
    val extended: Set<String> =
        setOf(
            heartRateVariability,
            oxygenSaturation,
            respiratoryRate,
            exercise,
            steps,
            stepsCadence,
            distance,
            activeCalories,
            totalCalories,
            speed,
        )
}
