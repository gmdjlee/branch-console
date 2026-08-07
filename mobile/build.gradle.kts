// mobile/ Gradle root — 플러그인 버전은 gradle/libs.versions.toml(SSOT)에서만 선언한다.
// docs/plans/M1_PLAN_A.md AD-A9 / §2.2.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

subprojects {
    // AD-A9: 동적 버전·변경 버전 차단 — 버전 카탈로그가 유일한 버전 선언 지점이 되도록 강제한다.
    configurations.all {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}
