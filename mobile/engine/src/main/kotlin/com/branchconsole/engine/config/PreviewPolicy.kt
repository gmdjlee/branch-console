package com.branchconsole.engine.config

/**
 * `configs/indicators.yaml` `engine.preview_coverage_min` 로더 — 프리뷰 raw coverage 억제 임계
 * (D-23 §23.3-3, docs/plans/M1_PLAN_FINAL.md §1.1 M-09b). [YamlLoader]와 그 `asXxx` 확장은
 * `:engine` 모듈 내부(`internal`) 전용이라 `:app`에서 이 값을 직접 읽을 수 없다 — 기존
 * [ModifierRules]/[StaleWindows]와 동일한 "engine.* SSOT 공개 로더" 패턴으로 신설한다
 * (CLAUDE.md §1: 임계값 코드 리터럴 금지 — 이 값을 `:app`이 하드코딩하지 않기 위한 진입점).
 */
object PreviewPolicy {
    fun previewCoverageMin(source: ConfigSource): Double =
        YamlLoader.loadMap(source, "indicators.yaml").asMap("engine").asDouble("preview_coverage_min")
}
