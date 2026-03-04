plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.gradle.license)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.xingheyuzhuan.classflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xingheyuzhuan.classflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.0.0.0"

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
    kotlin {
        jvmToolchain(21)
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
    sourceSets {
        getByName("main") {
            withGroovyBuilder {
                "proto" {
                    "srcDir"("src/main/proto")
                }
            }
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    debugImplementation(libs.okhttp.logging.interceptor)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.jgit)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)
    implementation(libs.androidx.compose.animation)
    implementation(libs.coil.compose)
    implementation(libs.haze)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.protobuf.java.lite)
    implementation(libs.javax.inject)
    implementation(libs.jsoup)
    implementation(libs.intro.showcase)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

protobuf {
    protoc {
        // 从版本目录中获取 protoc 编译器
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }

    // 配置代码生成任务
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
