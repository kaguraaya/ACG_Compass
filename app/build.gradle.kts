import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// RC.02 4.6 / RC.00：从 gitignored 的 local.properties 读取内置 Bangumi OAuth 应用凭据（App 自身凭据，
// 非用户凭据）注入 BuildConfig：不进源码、不进 git。留空时 App 回退到「用户在设置页自填 App ID/Secret」模式。
val acgLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun acgLocalProp(key: String): String = (acgLocalProperties.getProperty(key) ?: "").trim()

android {
    namespace = "com.acgcompass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acgcompass"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.18.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // RC.02 4.6：内置 Bangumi OAuth 应用凭据（来自上方 local.properties）。最终用户无需注册即可一键登录；
        // 留空则回退到设置页自填。值反引号使生成 `String BANGUMI_CLIENT_ID = "..."`。
        buildConfigField("String", "BANGUMI_CLIENT_ID", "\"${acgLocalProp("bangumi.clientId")}\"")
        buildConfigField("String", "BANGUMI_CLIENT_SECRET", "\"${acgLocalProp("bangumi.clientSecret")}\"")
    }

    // Room schema export (RC.16.03 / design: exportSchema=true for migration tests).
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    // Expose the exported Room schema json to instrumented tests so
    // androidx.room.testing.MigrationTestHelper can load each version's schema
    // (task 5.4 — Property 2: 数据库迁移保留所有行).
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            // kotest uses the JUnit5 platform.
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // P：动态开屏（androidx core-splashscreen，兼容 API 23+，承接 O 的 DayNight 启动底色）。
    implementation(libs.androidx.core.splashscreen)

    // Compose (BOM aligns all Compose artifact versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Async
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Network / serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Images
    implementation(libs.coil.compose)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Unit testing (JVM) — JUnit4 platform host + kotest (incl. kotest-property)
    testImplementation(libs.junit)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented / UI tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Room migration testing (MigrationTestHelper) + property generators (Property 2)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotest.property)
}
