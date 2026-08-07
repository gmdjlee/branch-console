// :lake — Android library, append-only Room ledger (MT1-03).
// Refs: docs/plans/M1_PLAN_D.md §2.1·§2.2.2 (schema·SQL 리터럴), M1_PLAN_A.md §2.12 (b-0)
// (읽기 지점 전수표), M1_PLAN_B.md §5.1, M1_PLAN_FINAL.md §1.1~1.2 (M-43·M-49·M-43b·M-34·M-22).
//
// Room 필요 → 순수 JVM(:engine·:krx와 동일한 kotlin("jvm"))으로는 만들 수 없다(Room-KMP의
// bundled-SQLite JVM 타깃은 이 카탈로그의 Room 2.8.4 구성에서 별도 배선이 필요해 단일
// 소비자(:app) 모듈에 들이기엔 과함 — 기존 app/build.gradle.kts TODO(MT1-03a)가 지정한 대로
// :app·:engine 옆의 세 번째 Android 모듈로 둔다.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.branchconsole.lake"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("UnstableApiUsage")
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // release 변형 단위테스트 비활성 (AD-A9, :app과 동일 정책) — :lake는 릴리스 서명 대상이 아니다.
    androidComponents {
        beforeVariants(selector().withBuildType("release")) {
            it.enableUnitTest = false
        }
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.konsist)
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
        filters {
            excludes {
                // Room 생성 구현체만 제외한다(자체 로직 제외 금지, R-B15 — :app과 동일 정책).
                classes(
                    "com.branchconsole.lake.*_Impl",
                    "com.branchconsole.lake.*_Impl\$*",
                )
            }
        }
        verify {
            // docs/plans/M1_PLAN_B.md §3.2.1 — 코어 모듈(lake) 최소 라인 커버리지 90%.
            rule("lake minimum line coverage 90%") {
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
