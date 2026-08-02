# MT0-01 편집 전 baseline 증빙 (Advisor attestation)

- 작성: 2026-08-02, M0 세션 Advisor · 목적: aaa-critic 결함 4 해소
- 배경: v3 패치가 부모 없는 단일 커밋(348f1b9)으로 기록되어 "기존 값 무변경 이동"을 git diff로
  검증할 수 없다. 디스크 전수 탐색(`D:\wp_2026\` 이하) 결과 v2 원본 사본도 존재하지 않는다.
  본 문서는 Advisor가 **§2 위임 직전(2026-08-02 11:46~12:00 KST) Read 도구로 직접 열람한
  편집 전 파일 원문**을 증빙으로 물질화한 것이다. 세션 기록 기반 증언(attestation)이며,
  git diff와 동급의 기계적 증명이 아님을 명시한다.

## 1. configs/statemachine.yaml — 편집 전 원문 (이동 대상 부분)

```yaml
upgrade:                      # 조건 충족이 sustain_ticks 연속이어야 승격
  sustain_ticks: 2
  rules:
    AMBER:  { composite_gte: 20, or_any_crit: true }
    ORANGE: { composite_gte: 40, distinct_axes_gte: 2 }
    RED:    { composite_gte: 60, distinct_axes_gte: 3 }

downgrade:                    # 히스테리시스: 진입선보다 낮은 이탈선 + 긴 유지
  sustain_ticks: 6
  rules:
    exit_RED:    { composite_lt: 50 }
    exit_ORANGE: { composite_lt: 32 }
    exit_AMBER:  { composite_lt: 14 }

anti_flap:
  min_dwell_ticks: 4          # 어떤 국면이든 최소 유지 틱
  reentry_cooldown_ticks: 6   # 하향 직후 재승격 시 sustain 카운트 리셋 + 쿨다운
```

```yaml
llm_tiering:                   # judgment 서비스가 참조
  daily_digest:     { model: claude-haiku-4-5,  web_search: false }   # 최신 Haiku 세대 확인 후 갱신
  amber_summary:    { model: claude-sonnet-5,   web_search: false }
  scenario_report:  { model: claude-opus-5,     web_search: true, structured_output: scenario-snapshot/1 }
  # 모델 ID는 2026-08-01 기준. 갱신 시 platform.claude.com/docs/en/about-claude/models/overview 확인 (F-01)
```

## 2. configs/indicators.yaml — 편집 전 원문 (개편 대상 부분)

```yaml
engine:
  missing_data_policy: exclude_from_denominator   # 결측 지표는 분모에서 제외 (optional 포함)
  stale_data_max_age: { intraday_30m: 90m, daily_kr: 36h, daily_us: 36h, fred_daily: 72h }
```

## 3. 대조 결론

| 항목 | 편집 전 | 편집 후 (현행) | 판정 |
|---|---|---|---|
| 승격 sustain | `upgrade.sustain_ticks: 2` | `profiles.server_intraday.promote_sustain_ticks: 2` | 값 동일, 이동만 |
| 강등 sustain | `downgrade.sustain_ticks: 6` | `profiles.server_intraday.demote_below_ticks: 6` | 값 동일, 이동만 |
| 최소 체류 | `anti_flap.min_dwell_ticks: 4` | `profiles.server_intraday.min_dwell_ticks: 4` | 값 동일, 이동만 |
| 재승격 쿨다운 | `anti_flap.reentry_cooldown_ticks: 6` | `profiles.server_intraday.reentry_cooldown_ticks: 6` | 값 동일, 이동만 |
| upgrade/downgrade rules | composite_gte 20/40/60, exit 50/32/14 | 동일 | 무변경 |
| llm_tiering 부가 키 | web_search ×3, structured_output ×1 | 동일 | 구조 보존, model 값만 D-20 교체 |
| stale 창 (server) | `stale_data_max_age` 90m/36h/36h/72h | `stale_profiles.server_intraday` 동일 4값 | 값 동일, 키 개편만 |

교차 검증: 전이 4값(2/6/4/6)은 `docs/P0_DESIGN_DECISIONS.md` D-03 서술과도 일치(aaa-critic 확인).
stale 창 4값(90m/36h/36h/72h)은 저장소 문서에 독립 근거 기록이 없던 값으로, 본 문서가 최초의
물질화다. 이 값들은 CHANGES_V3 §2.2가 명시한 대로 **가설 지위**이며 BT-03 스테일 창 스윕의
보정 대상이다 — 근거 확정은 BT-03 산출(0.3.0-rc)에서 이뤄진다.

## 4. 한계

- 본 증빙은 Advisor 세션 기록의 인용이다. 이후 phase에서 baseline 분쟁이 재발하면 이 문서가
  기준점이며, 커밋 348f1b9 이후로는 git 이력이 통상적 검증 경로다.
