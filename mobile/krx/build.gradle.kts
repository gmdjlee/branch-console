import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// :krx — kotlin_krx 벤더링 수용 스켈레톤 (docs/plans/M1_PLAN_A.md AD-A1·§2.3). 순수 Kotlin/JVM.
// 소스 벤더링(PROVENANCE.md·krx-manifest.sha256 포함)은 MT1-01g 별도 서브태스크의 몫이다 —
// 이 모듈은 그 착지를 위한 빈 스켈레톤 + 전이 의존성 정렬만 갖춘다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
    // kotlin_krx 실측 전이 의존성 정렬 (MT1-00e §2, MT1-01g 대비) — 아직 소비 코드는 없다.
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

kover {
    reports {
        verify {
            rule("krx minimum line coverage 70%") {
                minBound(70)
            }
        }
    }
}
