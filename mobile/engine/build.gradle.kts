import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// :engine — 순수 Kotlin/JVM, Android 의존 0 (docs/plans/M1_PLAN_A.md AD-A1, §2.1).
// jvmToolchain() DSL은 의도적으로 쓰지 않는다 — 이 머신(JBR 21 단독)에서 엄격한 toolchain
// 자동탐색이 실패한다(docs/journal/2026-08-07_MT1-00e_toolchain_matrix.md §5 실증).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    useJUnitPlatform()
}

dependencies {
    // snakeyaml-engine 3.1의 published POM이 org.junit.jupiter:junit-jupiter-api를 런타임
    // 의존성으로 선언한다(실측 발견, docs/journal/2026-08-07_MT1-00e_toolchain_matrix.md에는
    // 없던 사실 — 2026-08-07 :app:dependencies 실행으로 확인). YAML 파서에 JUnit 런타임이
    // 딸려 들어가 :app 패키징 시 META-INF/LICENSE.md가 Robolectric/JUnit5 테스트 아티팩트와
    // 충돌한다(mergeDebugJavaResource 실패, 실증). 프로덕션에 불필요하므로 제외한다.
    implementation(libs.snakeyaml.engine) {
        exclude(group = "org.junit.jupiter")
    }

    // MT1-02b (docs/plans/M1_PLAN_B.md §6): contracts/*.py Kotlin mirror wire format.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test"))
    testImplementation(libs.konsist)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

kover {
    reports {
        filters {
            excludes {
                // MT1-01f 제외 규칙(docs/plans/M1_PLAN_B.md §3.2.1) — 생성 코드 전용, 자체 로직
                // 제외 금지(R-B15). kotlinx.serialization 컴파일러 플러그인이 매 @Serializable
                // 클래스에 주입하는 Companion.serializer() 접근자를 배제한다.
                // 실측(build/reports/kover/report.xml): 사용자가 직접 선언한 컴패니언은
                // ScenarioSnapshot 1건뿐이고 그 유일한 측정 대상(<init>, DEFAULT_DISCLAIMER 상수)은
                // 이미 100% 커버돼 있어 이 배제가 미검증 로직을 숨기지 않는다. write$Self$engine의
                // require() 검증 분기(예: TriggerBlock의 composite_score 범위 체크)는 컴패니언이
                // 아니라 클래스 본체에 남으므로 배제 대상이 아니다.
                classes("*\$Companion")
                // 컴파일러가 별도 클래스(Foo$$serializer)를 생성하는 경우를 대비한 선언 — 이
                // 모듈의 현재 데이터 클래스는 write$Self를 클래스 본체에 인라인해 지금은 매치
                // 0건(무해)이지만, AAA §2.3 제외 정책(생성 코드)을 고정해 둔다.
                classes("*\$\$serializer")
            }
        }
        verify {
            rule("engine minimum line coverage 90%") {
                minBound(90)
            }
        }
    }
}

// MT1-01f 측정 생존 증인은 루트 mobile/build.gradle.kts의 subprojects{}로 승격됐다(aaa
// CONDITIONAL D-1 해소 — :engine 전용 개별 태스크였던 것을 kover 적용 3모듈 공통으로 확장).
tasks.named("check") {
    dependsOn("koverVerify")
}
