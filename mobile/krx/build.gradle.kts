import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.security.MessageDigest

// :krx — kotlin_krx 벤더링 (docs/plans/M1_PLAN_A.md AD-A1·§2.3, MT1-01g). 순수 Kotlin/JVM.
// 소스는 PROVENANCE.md에 기록된 커밋에서 복사됐고, 우리가 가한 변경은 전부 PROVENANCE.md §3에
// 등재돼 있다. detekt-baseline.xml은 벤더링 시점 기존 위반(우리 코드 스타일이 아닌 upstream
// 스타일)을 동결한다 — 신규 :krx 코드는 이 베이스라인에 기대지 않는다(MT1-01g 완료 보고 참고).
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
    // kotlin_krx 실측 전이 의존성 정렬 (MT1-00e §2, MT1-01g 벤더링). kotlinx-datetime은
    // kotlin_krx 코드 자체는 소비하지 않는다(java.time 사용) — 카탈로그 핀 유지, 소비처는
    // 향후 :app 어댑터(MT1-04c)가 될 수 있어 제거하지 않는다.
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
    // 벤더링 시점(MT1-01g) upstream 스타일 위반을 동결 — 억제 범위를 :krx로 정직하게 한정한다
    // (M1_PLAN_A §2.1: "여기는 수입품"이 빌드 파일에 남아야 한다). 새 위반이 추가되면
    // detektCheck가 그대로 잡는다 — 베이스라인은 줄어들 수만 있고 늘어나면 리뷰 대상이다.
    baseline = file("detekt-baseline.xml")
}

kover {
    reports {
        filters {
            excludes {
                // MT1-01f 정밀화(01g가 남긴 "벤더 글롭" 잔여 항목, docs/plans/M1_PLAN_B.md
                // §3.2.1) — 이전 판은 com.krxkt.* 패키지 전체를 뭉뚱그려 제외해 우리가 실제로
                // 수정한 3파일(PROVENANCE.md §3.1: InvestorTrading.kt D-1 로직 수정,
                // KrxClient.kt 재시도 파라미터화, IndexOhlcv.kt KDoc 정정)까지 측정 밖으로
                // 밀어냈다(자체 로직 제외 금지, R-B15 위반 소지). Kover의 classes() 필터는
                // 클래스명 glob을 지원하므로 패키지 단위 대신 "수정 3파일을 제외한 나머지
                // 벤더 파일"을 파일명 기준으로 열거해 정밀 배제한다 — InvestorTrading·
                // IndexOhlcv·KrxClient(및 그 안의 InMemoryCookieJar)는 이 목록에 없으므로
                // 그대로 측정된다. 각 패턴은 해당 파일이 선언하는 최상위 타입(+ 중첩 클래스는
                // "$"로 시작하는 컴파일러 명명 규칙을 활용한 접두 와일드카드)과 1:1 대응한다.
                classes(
                    "com.krxkt.api.KrxEndpoints*",
                    "com.krxkt.cache.TickerCache*",
                    "com.krxkt.error.KrxError*",
                    "com.krxkt.KrxEtf*",
                    "com.krxkt.KrxIndex*",
                    "com.krxkt.KrxStock*",
                    "com.krxkt.model.DerivativeIndex*",
                    "com.krxkt.model.EtfInfo*",
                    "com.krxkt.model.EtfOhlcvHistory*",
                    "com.krxkt.model.EtfPortfolio*",
                    "com.krxkt.model.EtfPrice*",
                    "com.krxkt.model.IndexFundamentalHistory*",
                    "com.krxkt.model.IndexInfo*",
                    "com.krxkt.model.IndexMarket*",
                    "com.krxkt.model.IndexOhlcvByTicker*",
                    "com.krxkt.model.IndexPortfolio*",
                    // Market*는 같은 접두를 공유하는 MarketCap·MarketOhlcv(둘 다 벤더 원본)도
                    // 함께 배제한다 — 셋 다 순수 벤더 파일이므로 의도된 겹침이다.
                    "com.krxkt.model.Market*",
                    "com.krxkt.model.OptionVolume*",
                    // ShortSelling*는 ShortSellingHistory를, ShortBalance*는 ShortBalanceHistory를
                    // 함께 배제한다(model/ShortSelling.kt 한 파일 안의 4개 벤더 데이터 클래스).
                    "com.krxkt.model.ShortSelling*",
                    "com.krxkt.model.ShortBalance*",
                    // StockFundamental*는 StockFundamentalHistory(별도 파일)도 함께 배제한다.
                    "com.krxkt.model.StockFundamental*",
                    "com.krxkt.model.StockOhlcvHistory*",
                    "com.krxkt.model.TickerInfo*",
                    "com.krxkt.parser.KrxJsonParser*",
                    "com.krxkt.util.DateUtils*",
                )
            }
        }
        verify {
            rule("krx minimum line coverage 70%") {
                minBound(70)
            }
        }
    }
}

// PROVENANCE.md·krx-manifest.sha256 대조 — 등재되지 않은 벤더 파일 변경을 빌드 실패로 잡는다
// (M1_PLAN_A §2.3 "벤더링 실행 규율"). sha256sum 등 외부 바이너리에 의존하지 않고 순수
// Kotlin/JDK MessageDigest로 계산해 재현성을 지킨다(AD-A9).
val krxManifestFile = layout.projectDirectory.file("krx-manifest.sha256")
val krxVendorDirs =
    listOf(
        layout.projectDirectory.dir("src/main/kotlin/com/krxkt"),
        layout.projectDirectory.dir("src/test/kotlin/com/krxkt"),
    )

val verifyKrxProvenance by tasks.registering {
    group = "verification"
    description = "krx-manifest.sha256과 벤더링된 소스 파일의 실제 해시를 대조한다(PROVENANCE.md)."
    inputs.file(krxManifestFile)
    krxVendorDirs.forEach { inputs.dir(it) }

    doLast {
        val projectDir = layout.projectDirectory.asFile
        val manifestLines = krxManifestFile.asFile.readLines().filter { it.isNotBlank() }
        val manifestPaths = mutableSetOf<String>()
        val problems = mutableListOf<String>()

        manifestLines.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) {
                problems += "MALFORMED manifest line: $line"
                return@forEach
            }
            val expectedHash = parts[0]
            val relPath = parts[1].removePrefix("*")
            manifestPaths += relPath

            val file = projectDir.resolve(relPath)
            if (!file.exists()) {
                problems += "MISSING: $relPath (매니페스트에 있으나 파일 없음)"
                return@forEach
            }
            val actualHash =
                MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                problems += "CHANGED: $relPath (매니페스트 미등재 변경 — REIMPORT.md 절차로 갱신 필요)"
            }
        }

        val actualFiles =
            fileTree(projectDir) {
                krxVendorDirs.forEach { dir ->
                    include("${dir.asFile.relativeTo(projectDir).invariantSeparatorsPath}/**/*.kt")
                }
            }.files.map { it.relativeTo(projectDir).invariantSeparatorsPath }.toSet()

        (actualFiles - manifestPaths).forEach { extra ->
            problems += "UNTRACKED: $extra (신규 벤더 파일이 매니페스트에 없음)"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyKrxProvenance FAILED — krx-manifest.sha256 불일치 ${problems.size}건:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n의도된 변경/재이식이면 REIMPORT.md 절차대로 매니페스트·PROVENANCE.md를 갱신하라.",
            )
        }
    }
}

tasks.named("check") {
    // MT1-01f 잔여 4: Kover 0.9.9의 check<-koverVerify 자동 배선에 기대지 않고 명시한다
    // (버전 상향 시 조용히 풀리는 것을 방지, docs/plans/M1_PLAN_B.md §3.2.1).
    dependsOn(verifyKrxProvenance, "koverVerify")
}
