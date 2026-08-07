// :app — Android application (docs/plans/M1_PLAN_A.md AD-A1). 실측 확정 툴체인은
// docs/journal/2026-08-07_MT1-00e_toolchain_matrix.md §3·§6. jvmToolchain() DSL은 의도적으로
// 쓰지 않는다 — 이 머신(JBR 21 단독)에서 엄격한 toolchain 자동탐색이 실패한다(동 문서 §5 실증).
// 대신 compileOptions/kotlinOptions로 target=17만 지정한다(실행 JDK는 그대로 21).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.branchconsole.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.branchconsole.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("UnstableApiUsage")
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // AD-A9 (§2.2): release 변형 단위테스트는 비활성해 로컬/CI 실행 시간을 줄인다 — check는
    // debug 변형 커버리지·검증으로 충분하다(M1은 기능판, 릴리스 서명 파이프라인 미대상).
    androidComponents {
        beforeVariants(selector().withBuildType("release")) {
            it.enableUnitTest = false
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":krx"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

kover {
    reports {
        verify {
            rule("app minimum line coverage 70%") {
                minBound(70)
            }
        }
    }
}
