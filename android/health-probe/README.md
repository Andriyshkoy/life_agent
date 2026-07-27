# Life Agent Health Connect day-0 probe

A temporary, read-only Android app for discovering what OnePlus Watch 2 and OHealth actually
publish to Health Connect on a OnePlus Open. The core scan reads:

- `SleepSessionRecord`;
- `HeartRateRecord`;
- `RestingHeartRateRecord`.

An optional, separately granted 30-day discovery scan reads:

- `HeartRateVariabilityRmssdRecord`, `OxygenSaturationRecord`, and
  `RespiratoryRateRecord`;
- `ExerciseSessionRecord` without requesting or inspecting exercise routes;
- `StepsRecord` and `StepsCadenceRecord`;
- `DistanceRecord`, `ActiveCaloriesBurnedRecord`, and `TotalCaloriesBurnedRecord`;
- `SpeedRecord`.

The app has no `INTERNET` permission, no write permissions, no background permission or worker,
and no API tokens. It does not persist health records. Sharing happens only through the Android
share sheet after an explicit tap.

## What the report contains

The capability report is deliberately privacy-minimized:

- record counts and sample counts for heart rate, steps cadence, and speed, never their values;
- `com.heytap.health.international` attribution is retained for the expected OHealth origin;
- every non-OHealth origin is combined into aggregate counts and coverage, so its package name is
  never included in a shared report;
- earliest/latest coverage rounded down to a UTC hour;
- sleep-stage type counts, and numeric exercise-type enum counts with lap/segment counts;
- presence counts for data-origin, device/manufacturer/model, and recording-method metadata;
- standardized error codes per data type.

It never includes record IDs, exact timestamps, measurement values, routes, titles, notes,
non-OHealth package names, or stack traces. Reads use `pageToken` until every Health Connect page
is exhausted.

## Prerequisites on the phone

1. Sync OnePlus Watch 2 in OHealth and confirm that sleep/heart data is visible there.
2. Open **Settings → Security & privacy → Health Connect** (the exact OxygenOS wording can vary).
3. In Health Connect's app permissions, allow OHealth to write/share the relevant data.
4. Use a recent Android Studio with JDK 17 and Android SDK 36 for the build.

No server, domain, token, Google Cloud project, or Play Console account is needed for this probe.

## Ready Day-0 APK

The prebuilt debug APK for the personal device test is:

```text
dist/life-agent-health-probe-day0-v0.2.0.apk
SHA-256: 763ce4298f85c8f81f65d76588ec9d7d94119e0715e456ea0cb656bc8c1237f9
```

It is debug-signed for manual installation and is not a production release artifact.

## Reproducible Docker build

No host JDK, Gradle, or Android SDK is required. Build the pinned toolchain image
and then the APK:

```bash
docker build --file Dockerfile.build --tag life-agent-health-probe-builder .
docker run --rm \
  --volume "$PWD:/workspace" \
  life-agent-health-probe-builder
```

The builder uses JDK 17, Gradle 8.13, Android SDK 36 and checksum-verifies Google's
Android command-line tools archive.

## Android Studio build

Open this directory as a project in Android Studio and run the `app` configuration, or build from
a shell that has JDK 17, Android SDK 36 and Gradle 8.13:

```bash
gradle :app:assembleDebug
```

The APK will be:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with Android Studio or ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Day-0 test procedure

1. Open **Life Agent Health Probe**.
2. Verify `Health Connect: available`.
3. Tap **Grant core read permissions** and allow Sleep, Heart rate and Resting heart rate.
4. Run **Scan last 48 hours** first.
5. Immediately share or save that report with **Share capability report**.
6. Run **Scan last 30 days** to test historical coverage and pagination, then immediately share
   or save the second report. Each new scan replaces the report currently shown in the app.
7. Tap **Grant optional discovery permissions**. Grant any subset you are comfortable testing;
   denied types remain `permission_missing` and do not stop the other sections.
8. Run **Run optional 30-day discovery scan** and immediately share or save the third report.
9. If OHealth is absent, open Health Connect's **Data and access** screens and verify whether
   OHealth has written any records; then sync OHealth and rerun the probe.

Because Health Connect normally limits newly granted readers to the preceding 30 days, this probe
does not request the broader health-history permission.

## Expected report shape

```text
LIFE_AGENT_HEALTH_CONNECT_CAPABILITY_REPORT
format_version=1
probe_version=0.2.0
scan_kind=core
window=last_48h
window_start_utc_hour=2026-07-25T08:00:00Z
window_end_utc_hour=2026-07-27T08:00:00Z
privacy=no_hr_values,no_record_ids,no_exact_timestamps,no_titles_or_notes,no_non_ohealth_package_names
expected_ohealth_origin=com.heytap.health.international

[SLEEP]
status=ok
record_count=2
stage_count=19
coverage_utc_hour=...
ohealth_origin_found=yes
stage_type_counts=awake:2,deep:3,light:8,rem:6
...

[HEART_RATE]
status=ok
record_count=...
sample_count=...
...

[RESTING_HEART_RATE]
status=ok
record_count=...
...
```

Counts in this example are illustrative. `status=permission_missing` means that the corresponding
Health Connect permission was not granted. `status=read_error` plus an `error_code` distinguishes
permission/policy, provider storage, IPC, provider/rate-limit, and unexpected failures without
leaking an exception message.

The optional report uses `scan_kind=extended` and adds sections for HRV, oxygen saturation,
respiratory rate, exercise sessions, steps/cadence, distance, calories, and speed. It reports only
record/sample/enum/lap/segment counts and rounded coverage. For example:

```text
[EXERCISE_SESSION]
status=ok
record_count=...
lap_count=...
segment_count=...
exercise_type_enum_counts=enum_56:2,enum_79:4
ohealth_origin_records=...
other_origin_count=...
other_origin_records=...

[SPEED]
status=ok
record_count=...
sample_count=...
...
```

Exercise enum numbers preserve the Health Connect type without exposing session titles or notes.
The app neither declares `READ_EXERCISE_ROUTE` nor accesses a route field.

## Interpreting day-0

- `ohealth_origin_found=yes` proves OHealth inserted readable records for that type.
- OHealth present for sleep but absent for heart rate means the production app must treat those
  capabilities separately.
- `record_count>0` with `device_present_records=0` is valid: metadata is optional and must not be
  invented.
- Sleep `ohealth_stage_count=0` means OHealth sessions exist but OHealth did not expose stage
  intervals; total `stage_count` can include other origins.
- Records must not be assumed to originate directly from the watch merely because they appear in
  Health Connect. The shared day-0 report intentionally aggregates non-OHealth origins instead of
  disclosing their package names.

This probe is not the production Life Agent app. It performs foreground, explicit scans only and
has no upload path by design; verified reader code will move into the single product APK.
