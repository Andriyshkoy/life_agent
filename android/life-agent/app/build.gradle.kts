import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

val suppliedVersionCode = providers
    .environmentVariable("LIFE_AGENT_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
val suppliedVersionName = providers
    .environmentVariable("LIFE_AGENT_VERSION_NAME")
    .orNull
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
        versionName = suppliedVersionName ?: "0.1.0"
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
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("release")
            signingConfig = distributionSigningConfig ?: signingConfigs.getByName("debug")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    screenshotTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    screenshotTestImplementation(
        "com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15",
    )
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
