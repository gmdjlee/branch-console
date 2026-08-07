package com.branchconsole.engine.parity

import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.ModifierRules
import com.branchconsole.engine.config.RepoConfigSource
import com.branchconsole.engine.config.StaleWindows
import com.branchconsole.engine.config.StatemachineConfigLoader
import com.branchconsole.engine.config.YamlLoader
import com.branchconsole.engine.config.asInt
import com.branchconsole.engine.config.asMap
import java.io.File
import kotlin.test.Test

private const val MOBILE_DAILY = "mobile_daily"
private const val MAX_PARENT_HOPS = 8
private val PHASE_ORDER = listOf("GREEN", "AMBER", "ORANGE", "RED")

/** [RepoConfigSource]와 동일한 상향 탐색 관례 — repo 루트는 `configs/`와 `backtest/`를 모두
 * 가진 첫 조상 디렉터리다(Gradle 정확한 작업 디렉토리 규약에 의존하지 않기 위해). */
private fun findRepoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    repeat(MAX_PARENT_HOPS) {
        val candidate = dir
        if (candidate != null && File(candidate, "configs").isDirectory && File(candidate, "backtest").isDirectory) {
            return candidate
        }
        dir = candidate?.parentFile
    }
    error("repo root (configs/ + backtest/) not found by walking up from ${System.getProperty("user.dir")}")
}

/**
 * MT1-05j — BT-05 패리티 러너. `backtest/parity/<window_id>/{raw.jsonl,grid.json}`를 읽어
 * 사슬 ③~⑦(가시성 색인·severity·modifier·composite·상태기계)을 실행하고 같은 디렉터리에
 * `actual.jsonl`을 쓴다.
 *
 * **판정은 이 테스트의 몫이 아니다** — 브리프 지정 2단계 절차(gradlew 러너 → pytest 판정)에
 * 따라 L0~L6 계층별 허용 오차 비교는 `backtest/test_bt05_parity.py`(Python)가
 * `expected.jsonl` vs `actual.jsonl`로 수행한다. 이 클래스는 raw.jsonl + grid.json**만**
 * 입력으로 받고 나머지를 전부 스스로 계산한다(`expected.jsonl`을 읽지 않는다 — 읽으면
 * 아무것도 검증하지 않는 것과 같다, §9-C).
 *
 * 실행 절차:
 *   1) `uv run python backtest/export_parity.py --window all`  (raw/grid/expected/MANIFEST 생성)
 *   2) `cd mobile && ./gradlew :engine:test --tests "*ParityRunnerTest*"`  (본 클래스 — actual.jsonl 생성)
 *   3) `uv run pytest -q backtest/test_bt05_parity.py`  (L0~L6 판정 + 골든 L6 + w2026 발화 확인)
 */
class ParityRunnerTest {
    @Test
    fun `run all parity windows and write actual jsonl`() {
        val repoRoot = findRepoRoot()
        val parityRoot = File(repoRoot, "backtest/parity")
        val windowDirs = parityRoot.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        check(windowDirs.isNotEmpty()) {
            "no window directories under $parityRoot — run 'uv run python backtest/export_parity.py --window all' first"
        }

        val specs = IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = true)
        val cadences = specs.mapNotNull { it.source["cadence"] as? String }.toSet()
        val config =
            ParityConfig(
                specs = specs,
                weights = IndicatorRegistry.weightMap(specs),
                axes = IndicatorRegistry.axisMap(specs),
                maxSeverities = IndicatorRegistry.maxSeverityMap(specs),
                fredLagDays = fredLagDaysOf(specs),
                staleWindows = cadences.associateWith { StaleWindows.staleWindow(RepoConfigSource, MOBILE_DAILY, it) },
                statemachineConfig = StatemachineConfigLoader.load(RepoConfigSource),
                modifiers = ModifierRules.loadModifiers(RepoConfigSource),
            )

        // §9-C 웜업 일치 assert: assets(여기서는 repo configs — RepoConfigSource, K-16 SHA-256
        // 계측 테스트가 별도로 assets 동기화 자체를 보증한다)의 웜업 키 값이 grid.json의
        // padding_days와 갈리면 z 기준선이 갈리므로, 개별 창을 실행하기 전에 즉시 실패한다.
        val warmupPaddingDays =
            YamlLoader.loadMap(RepoConfigSource, "indicators.yaml").asMap("engine").asInt("warmup_padding_days")

        for (windowDir in windowDirs) {
            ParityIo.verifyManifest(windowDir)
            val raw = ParityIo.loadRaw(windowDir)
            val grid = ParityIo.loadGrid(windowDir)
            check(grid.profile == MOBILE_DAILY) { "${windowDir.name}: unexpected profile '${grid.profile}'" }
            check(grid.paddingDays == warmupPaddingDays) {
                "${windowDir.name}: grid.json padding_days=${grid.paddingDays} != " +
                    "indicators.yaml engine.warmup_padding_days=$warmupPaddingDays (M-42 drift)"
            }

            val ticks = runWindow(raw, grid, config)
            ParityIo.writeActual(windowDir, ticks)

            val maxPhase = ticks.maxByOrNull { PHASE_ORDER.indexOf(it.phase) }?.phase
            val maxComposite = ticks.mapNotNull { it.composite }.maxOrNull()
            println(
                "[${windowDir.name}] n_ticks=${ticks.size} max_phase=$maxPhase max_composite=$maxComposite " +
                    "-> ${File(windowDir, "actual.jsonl")}",
            )
        }
    }
}
