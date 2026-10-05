import org.gradle.api.tasks.compile.JavaCompile
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.gradle.license)
    alias(libs.plugins.wire)
    alias(libs.plugins.hilt.android)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

android {
    namespace = "com.xingheyuzhuan.shiguangschedule"
    compileSdk = 37

    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            FileInputStream(file).use { load(it) }
        }
    }

    val updateApiUrl: String = (project.findProperty("CLASSFLOW_UPDATE_API_URL") as? String)
        ?: localProperties.getProperty("CLASSFLOW_UPDATE_API_URL")
        ?: System.getenv("CLASSFLOW_UPDATE_API_URL")
        ?: ""

    // U净 NFC/DeepLink 域名属于私有配置，不写入仓库代码：
    // 通过 Gradle 属性 / local.properties（本机配置，Git 忽略）/ 环境变量注入；
    // 均未提供（或为空）时直接报错，避免构建出指向错误域名的包。
    val ujingNfcHost: String = sequenceOf(
        project.findProperty("CLASSFLOW_UJING_NFC_HOST") as? String,
        localProperties.getProperty("CLASSFLOW_UJING_NFC_HOST"),
        System.getenv("CLASSFLOW_UJING_NFC_HOST"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim()
        ?: throw GradleException(
            "缺少 CLASSFLOW_UJING_NFC_HOST：请在 local.properties、Gradle -P 参数或环境变量中配置 U净 NFC/DeepLink 域名"
        )

    // 通用链接节点（/url/{code}、/u/{code}）的 hub 域名，同属私有配置，同样不写入仓库代码。
    // 与 U净 域名不同：此项可缺省——未提供时回落到 U净 域名，保证 CI / 协作者无需新增密钥即可构建。
    val linkHubHostRaw: String? = sequenceOf(
        project.findProperty("CLASSFLOW_LINK_HUB_HOST") as? String,
        localProperties.getProperty("CLASSFLOW_LINK_HUB_HOST"),
        System.getenv("CLASSFLOW_LINK_HUB_HOST"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim()

    val linkHubHost: String = linkHubHostRaw?.let { raw ->
        val normalized = raw
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .substringBefore('/')
            .lowercase()
        // 只接受主机名：不允许端口、路径、通配符等（NFC/DeepLink 过滤器与 host 白名单都按精确匹配使用）
        if (!normalized.matches(Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$"))) {
            throw GradleException(
                "CLASSFLOW_LINK_HUB_HOST 非法：$raw（应为主机名，如 hub.example.com，不要带 scheme/端口/路径）"
            )
        }
        normalized
    } ?: ujingNfcHost

    // 通用链接节点的本地联调地址（仅 debug 构建生效），例如 http://127.0.0.1:8090。
    // 配合 `adb reverse tcp:8090 tcp:8090`，可以不部署公网就把 Stage B（服务端 JSON 解析）跑通。
    val linkHubDebugBase: String = sequenceOf(
        project.findProperty("CLASSFLOW_LINK_HUB_DEBUG_BASE") as? String,
        localProperties.getProperty("CLASSFLOW_LINK_HUB_DEBUG_BASE"),
        System.getenv("CLASSFLOW_LINK_HUB_DEBUG_BASE"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim()?.trimEnd('/') ?: ""

    defaultConfig {
        applicationId = "com.shiro.classflow"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "1.1.2"

        buildConfigField("String", "UPDATE_API_URL", "\"$updateApiUrl\"")
        buildConfigField("String", "UJING_NFC_HOST", "\"$ujingNfcHost\"")
        buildConfigField("String", "LINK_HUB_HOST", "\"$linkHubHost\"")
        buildConfigField("String", "LINK_HUB_DEBUG_BASE", "\"$linkHubDebugBase\"")
        manifestPlaceholders["ujingNfcHost"] = ujingNfcHost
        manifestPlaceholders["linkHubHost"] = linkHubHost

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 与上游一致：release 默认使用 debug 签名；
            // CI 通过 -Pandroid.injected.signing.* 注入正式签名（见 android-build.yml）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "version"

    productFlavors {
        create("dev") {
            dimension = "version"
            // 开发者版本的包名后缀，使其可以和正式版共存
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            // 环境标识变量
            buildConfigField("String", "CURRENT_FLAVOR_ID", "\"dev\"")

            // 注入开关：开发者版本不隐藏，显示自定义/私有仓库
            buildConfigField("Boolean", "HIDE_CUSTOM_REPOS", "false")
            // 注入开关：开发者版本关闭基准灯塔标签验证
            buildConfigField("Boolean", "ENABLE_LIGHTHOUSE_VERIFICATION", "false")

            // 开发者版本：允许在 UI 中显示 DevTools 选项
            buildConfigField("Boolean", "ENABLE_DEV_TOOLS_OPTION_IN_UI", "true")

            // 允许在 UI 中显示地址栏切换按钮
            buildConfigField("Boolean", "ENABLE_ADDRESS_BAR_TOGGLE_BUTTON", "true")


        }

        create("prod") {
            dimension = "version"

            // 环境标识变量
            buildConfigField("String", "CURRENT_FLAVOR_ID", "\"prod\"")
            // 注入开关：正式版本隐藏自定义/私有仓库
            buildConfigField("Boolean", "HIDE_CUSTOM_REPOS", "true")
            // 注入开关：正式版本开启基准灯塔标签验证
            buildConfigField("Boolean", "ENABLE_LIGHTHOUSE_VERIFICATION", "true")
            // 正式版本：禁止在 UI 中显示 DevTools 选项
            buildConfigField("Boolean", "ENABLE_DEV_TOOLS_OPTION_IN_UI", "false")

            // 禁止在 UI 中显示地址栏切换按钮
            buildConfigField("Boolean", "ENABLE_ADDRESS_BAR_TOGGLE_BUTTON", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    splits {
        // 启用对 ABI (CPU 架构) 的分包
        abi {
            isEnable = true
            exclude("mips", "mips64", "armeabi", "riscv64", "x86")
            isUniversalApk = false
            include("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

afterEvaluate {
    tasks.named("assembleProdRelease") {
        dependsOn("licenseProdReleaseReport")
    }
    tasks.named("assembleDevRelease") {
        dependsOn("licenseDevReleaseReport")
    }
}


dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.haze)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging.interceptor)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.jgit)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)
    implementation(libs.androidx.compose.animation)
    implementation(libs.coil.compose)
    implementation(libs.wire.runtime)
    implementation(libs.javax.inject)
    implementation(libs.jsoup)
    implementation(libs.intro.showcase)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

wire {
    sourcePath {
        srcDir("src/main/proto")
    }

    kotlin {
        escapeKotlinKeywords = true
        enumMode = "enum_class"
        rpcRole = "none"
    }
}
