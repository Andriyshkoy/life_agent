import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.compose.screenshot")
}

val suppliedVersionCode = providers
    .environmentVariable("LIFE_AGENT_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
val suppliedVersionName = providers
    .environmentVariable("LIFE_AGENT_VERSION_NAME")
    .orNull
val baseVersionName = "0.1.0"
val internalVersionNameSuffix = suppliedVersionName
    ?.also {
        require(it.startsWith(baseVersionName)) {
            "LIFE_AGENT_VERSION_NAME must start with $baseVersionName"
        }
    }
    ?.removePrefix(baseVersionName)
    .orEmpty()
val suppliedKeystorePath = providers
    .environmentVariable("LIFE_AGENT_KEYSTORE_PATH")
    .orNull
val suppliedKeystorePassword = providers
    .environmentVariable("LIFE_AGENT_KEYSTORE_PASSWORD")
    .orNull
val suppliedKeyAlias = providers
    .environmentVariable("LIFE_AGENT_KEY_ALIAS")
    .orNull
val suppliedKeyPassword = providers
    .environmentVariable("LIFE_AGENT_KEY_PASSWORD")
    .orNull

android {
    namespace = "ru.andriyshkoy.lifeagent"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ru.andriyshkoy.lifeagent"
        minSdk = 28
        targetSdk = 36
        versionCode = suppliedVersionCode ?: 1
        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val distributionSigningConfig = if (
        suppliedKeystorePath != null &&
        suppliedKeystorePassword != null &&
        suppliedKeyAlias != null &&
        suppliedKeyPassword != null
    ) {
        signingConfigs.create("distribution") {
            storeFile = file(suppliedKeystorePath)
            storePassword = suppliedKeystorePassword
            keyAlias = suppliedKeyAlias
            keyPassword = suppliedKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            resValue("string", "app_name", "Life Agent Local")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("internal") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "$internalVersionNameSuffix-dev"
            matchingFallbacks += listOf("release")
            signingConfig = distributionSigningConfig
            resValue("string", "app_name", "Life Agent Dev")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "GradleDependency"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").resources.srcDirs(
            "$rootDir/../../schemas",
            "$rootDir/../../examples",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric needs a writable lock and Maven cache under arbitrary container UIDs.
        unitTests.all { testTask ->
            testTask.systemProperty(
                "user.home",
                gradle.gradleUserHomeDir.absolutePath,
            )
        }
        managedDevices {
            localDevices {
                create("m1Api35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    val roomVersion = "2.8.4"

    implementation(composeBom)
    androidTestImplementation(composeBom)
    screenshotTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    screenshotTestImplementation(
        "com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15",
    )
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
