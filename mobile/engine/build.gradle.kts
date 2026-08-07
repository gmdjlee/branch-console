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
        verify {
            rule("engine minimum line coverage 90%") {
                minBound(90)
            }
        }
    }
}
