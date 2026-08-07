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

    // MT1-01f 측정 생존 증인(docs/plans/M1_PLAN_B.md §3.2.1, aaa CONDITIONAL D-1 해소) —
    // :engine에만 있던 개별 태스크를 kover가 적용된 모든 모듈(:engine·:krx·:app)에 공통
    // 적용한다. 글롭을 손으로 관리하는 :krx(25종)·:app(5종+annotatedBy)에서 과매치하면
    // koverVerify가 0/0(vacuous)으로 조용히 통과할 수 있다는 것이 각 모듈에 개별 방어가
    // 필요한 이유 — :krx에서 "com.krxkt.*" 한 줄만으로도 실제로 재현됐다. pluginManager.
    // withPlugin은 subprojects{}가 각 모듈의 kover 플러그인 적용보다 먼저 평가돼도 늦게
    // 평가돼도 안전하게 동작한다(플러그인 적용 시점에 콜백 발화).
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        val verifyKoverInstrumented by tasks.registering {
            group = "verification"
            description = "koverXmlReport의 전체 LINE 카운터 합이 0보다 큰지 확인한다(계측 생존 증인)."
            dependsOn("koverXmlReport")
            val reportFile = layout.buildDirectory.file("reports/kover/report.xml")
            val modulePath = path
            inputs.file(reportFile)
            doLast {
                val xml = reportFile.get().asFile.readText()
                val lineCounters =
                    Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
                        .findAll(xml)
                        .toList()
                check(lineCounters.isNotEmpty()) {
                    "$modulePath: koverXmlReport에 LINE 카운터가 없다 — 리포트 형식이 바뀌었거나 " +
                        "생성되지 않았다($reportFile)."
                }
                // 파일 마지막 LINE 카운터가 <report> 최상위 총합이다(패키지·클래스 총합 다음에 온다).
                val (missed, covered) = lineCounters.last().destructured
                val total = missed.toInt() + covered.toInt()
                check(total > 0) {
                    "$modulePath: MT1-01f 계측 생존 증인 실패 — koverXmlReport의 총 LINE 수가 0이다 " +
                        "— 제외 필터가 전체 소스를 삼켰거나 계측이 끊겼다(koverVerify가 0/0으로 " +
                        "조용히 통과하는 상태). $reportFile 확인."
                }
            }
        }
        tasks.named("check") {
            dependsOn(verifyKoverInstrumented)
        }
    }
}
