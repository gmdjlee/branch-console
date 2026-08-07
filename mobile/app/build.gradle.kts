import org.gradle.api.tasks.PathSensitivity
import java.security.MessageDigest

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

// syncConfigs + 해시 검증 (MT1-01b, docs/plans/M1_PLAN_A.md §2.4 AD-A3·AD-A4,
// docs/plans/M1_PLAN_FINAL.md §1.1 M-03·M-04). assets는 생성물이다 — 편집 가능한 사본이
// 저장소에 없으므로 K-16 드리프트가 구조적으로 불가능해진다. 저장소 원본(configs/·prompts/)은
// 읽기만 한다, 절대 쓰지 않는다.
val repoRoot = rootProject.layout.projectDirectory.dir("..")
val ssotAssets = layout.buildDirectory.dir("generated/ssot-assets")

// 복사·검증 양쪽에서 재사용 — 대상은 configs/*.yaml 5종 + prompts/*.md 2종(M1_PLAN_A.md §2.4).
val ssotSubdirExtensions = mapOf("configs" to "yaml", "prompts" to "md")

fun sha256Hex(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

val syncConfigs by tasks.registering {
    description = "루트 configs/*.yaml·prompts/*.md(SSOT)를 generated assets로 복사하고 " +
        "SHA-256 매니페스트(ssot.sha256)를 생성한다."
    inputs.dir(repoRoot.dir("configs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(repoRoot.dir("prompts")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(ssotAssets)
    doLast {
        val destRoot = ssotAssets.get().asFile
        destRoot.deleteRecursively()
        val manifest = mutableListOf<Pair<String, String>>()
        ssotSubdirExtensions.forEach { (sub, ext) ->
            val destDir = destRoot.resolve(sub).apply { mkdirs() }
            repoRoot.dir(sub).asFile
                .listFiles { candidate -> candidate.isFile && candidate.extension == ext }
                .orEmpty()
                .sortedBy { it.name }
                .forEach { source ->
                    val target = destDir.resolve(source.name)
                    source.copyTo(target, overwrite = true)
                    manifest += "$sub/${source.name}" to sha256Hex(source)
                }
        }
        val manifestText =
            manifest.sortedBy { it.first }
                .joinToString(separator = "\n", postfix = "\n") { (path, hash) -> "$hash  $path" }
        destRoot.resolve("ssot.sha256").writeText(manifestText)
    }
}

// 계층 ① (M-04): 저장소 원본 ↔ generated assets SHA-256을 직접 대조한다. syncConfigs에
// dependsOn을 걸지 않는다 — 걸면 Gradle이 "출력이 last-execution 스냅샷과 다르다"고 보고
// 재실행(재동기화)해 버려, generated assets를 수동 변조하는 K-16 드리프트 증인 시나리오가
// 검증 전에 자동 치유돼 버린다. check 배선에서는 mustRunAfter로 순서만 보장한다.
val verifyConfigHashes by tasks.registering {
    description = "저장소 configs/·prompts/ SHA-256이 generated assets·매니페스트와 일치하는지 검증한다(K-16)."
    mustRunAfter(syncConfigs)
    doLast {
        val destRoot = ssotAssets.get().asFile
        val manifestFile = destRoot.resolve("ssot.sha256")
        check(manifestFile.exists()) {
            "ssot.sha256 매니페스트가 없다($manifestFile) — 먼저 :app:syncConfigs를 실행했는가?"
        }
        val manifestEntries =
            manifestFile.readLines()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val (hash, path) = line.split("  ", limit = 2)
                    path to hash
                }
        val problems = mutableListOf<String>()
        manifestEntries.forEach { (relPath, expectedHash) ->
            val sourceFile = repoRoot.file(relPath).asFile
            val assetFile = destRoot.resolve(relPath)
            when {
                !sourceFile.exists() -> problems += "$relPath: 저장소 원본 없음($sourceFile)"
                !assetFile.exists() -> problems += "$relPath: generated asset 없음($assetFile)"
                else -> {
                    val sourceHash = sha256Hex(sourceFile)
                    val assetHash = sha256Hex(assetFile)
                    if (sourceHash != expectedHash) {
                        problems += "$relPath: 원본 해시($sourceHash) != 매니페스트($expectedHash)"
                    }
                    if (assetHash != expectedHash) {
                        problems += "$relPath: generated asset 해시($assetHash) != 매니페스트($expectedHash)"
                    }
                }
            }
        }
        ssotSubdirExtensions.forEach { (sub, ext) ->
            val sourceNames =
                repoRoot.dir(sub).asFile
                    .listFiles { candidate -> candidate.isFile && candidate.extension == ext }
                    .orEmpty()
                    .map { "$sub/${it.name}" }
                    .toSet()
            val manifestNames = manifestEntries.keys.filter { it.startsWith("$sub/") }.toSet()
            (sourceNames - manifestNames).forEach {
                problems += "$it: 저장소에는 있으나 매니페스트에 없음(sync 누락)"
            }
            (manifestNames - sourceNames).forEach {
                problems += "$it: 매니페스트에는 있으나 저장소에서 삭제됨"
            }
        }
        check(problems.isEmpty()) { "K-16 SSOT 드리프트 발견:\n" + problems.joinToString("\n") }
    }
}

val verifyNoCheckedInAssets by tasks.registering {
    description = "src/main/assets/configs·prompts 체크인 사본이 없는지 확인한다(AD-A3, K-16 구조적 차단)."
    doLast {
        val checkedIn =
            listOf(
                project.file("src/main/assets/configs"),
                project.file("src/main/assets/prompts"),
            ).filter { it.exists() }
        check(checkedIn.isEmpty()) {
            "K-16 위반: 체크인된 assets 사본 발견 — ${checkedIn.joinToString()} " +
                "(assets는 syncConfigs 생성물이어야 한다, AD-A3)"
        }
    }
}

android.sourceSets["main"].assets.srcDir(ssotAssets)
tasks.named("preBuild") { dependsOn(syncConfigs) }
tasks.named("check") {
    // MT1-01f 잔여 4: check<-koverVerify 자동 배선에 기대지 않고 명시한다(버전 상향 대비).
    dependsOn(syncConfigs, verifyConfigHashes, verifyNoCheckedInAssets, "koverVerify")
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
    // engine/build.gradle.kts와 동일 사유(주석 참고)로 junit-jupiter 런타임 누출을 제외한다 —
    // registry_version 로드 스모크(ConfigsManifestJvmTest)에서만 쓰는 test-only 의존이다.
    testImplementation(libs.snakeyaml.engine) {
        exclude(group = "org.junit.jupiter")
    }

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
        filters {
            excludes {
                // MT1-01f 제외 규칙(docs/plans/M1_PLAN_B.md §3.2.1) — 생성 코드·서드파티만,
                // 자체 로직 제외 금지(R-B15). 이 모듈에는 Room 엔티티/DAO(MT1-03)·추가
                // kotlinx.serialization 사용처·@Preview 컴포저블(MT1-08)이 아직 없어 지금은
                // 매치 0건(무해)이지만, 뒤 서브태스크가 해당 코드를 추가할 때 재논의 없이 바로
                // 적용되도록 정책을 지금 고정한다.
                // *_Impl / *Dao_Impl: Room이 생성하는 구현체(예: AppDatabase_Impl).
                // *$$serializer / *$Companion: kotlinx.serialization 생성 직렬화기·
                // Companion.serializer() 접근자(:engine과 동일 정책).
                // *BuildConfig: AGP 생성 BuildConfig(현재 buildFeatures.buildConfig 비활성).
                classes(
                    "*_Impl",
                    "*Dao_Impl",
                    "*\$\$serializer",
                    "*\$Companion",
                    "*BuildConfig",
                )
                // @Preview 컴포저블 — 개발 편의용 미실행 코드.
                annotatedBy("*Preview")
            }
        }
        verify {
            rule("app minimum line coverage 70%") {
                minBound(70)
            }
        }
    }
}

// MT1-01f 잔여 2 (docs/plans/M1_PLAN_B.md §3.2.1 모듈 표의 :lake 90% 행) — 의도적 이연.
// :lake는 아직 패키지조차 없다(MT1-03 선행 필요, settings.gradle.kts에 include(":lake") 없음).
// 계획의 모듈 표는 :engine·:krx처럼 :lake도 "별도 Gradle 모듈"로 그린다(§3.2 의존성 그래프
// 레인 C) — :app 내부의 패키지 스코프 규칙이 아니다. 설사 지금 :app 안에 lake 패키지를 만들어
// 규칙을 걸고 싶어도, Kover 0.9.9의 KoverVerifyRule/KoverVerifyBound(kover-gradle-plugin-0.9.9.jar
// 실측: javap로 두 인터페이스 전 메서드 확인)에는 rule 단위 필터가 없다 — filters는 리포트
// 전체(total/variant) 단위로만 걸리므로 "lake 패키지만 90%, 나머지는 70%"를 :app 하나의
// 리포트 안에서 동시에 표현할 방법이 없다(전체에 90%를 걸면 :app 전체가 90%를 요구받고,
// 필터를 lake로 좁히면 다른 규칙과 총량을 공유할 수 없다). TODO(MT1-03a): :lake 모듈 생성 시
// mobile/lake/build.gradle.kts에 :engine·:krx와 동일한 kover { reports { verify { rule(...) {
// minBound(90) } } } } 패턴을 적용하고 settings.gradle.kts에 include(":lake")를 추가한다.
