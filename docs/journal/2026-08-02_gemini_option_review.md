# Gemini 공급자 옵션 검토 (D-24 근거 문서)

> 본 검토의 결정(D-24)은 2026-08-02 사용자 지시로 철회됨. 아래 내용은 기록 보존용.

- 작성: 2026-08-02, M0 세션 Advisor · 지시: 사용자("gemini를 옵션으로 추가하고 claude 대신 사용 가능한지 검토")
- 사실관계 출처: ai.google.dev 공식 문서 실사(2026-08-02, general-purpose 에이전트 웹 조회) + claude-api 스킬 단가표(캐시 2026-06-24). D-20 §20.3 규율(기억이 아니라 조회로 판정) 준수.
- 범위: **런타임 판단 계층(statemachine.yaml llm_tiering)만.** 개발 하네스(Claude Code Advisor/Worker, D-20 §20.2)는 대상 아님.

## 1. 결론 (요약)

**부분 대체 가능, 전면 대체는 현시점 비권고.** llm_tiering에 공급자 스위치와 역할별 Gemini
후보 ID를 병기하되 기본값은 anthropic을 유지한다. 전환은 아래 §5의 조건 충족 후에만.

| 역할 | Claude 확정(D-20) | Gemini 후보(2026-08 실사) | 대체 판정 |
|---|---|---|---|
| daily_digest | claude-haiku-4-5-20251001 | `gemini-3.5-flash-lite` (GA 2026-07-21) | **가능** — 안정 ID, 요구 기능 충족 |
| amber_summary | claude-sonnet-5 | `gemini-3.6-flash` (GA 2026-07-21) | **가능** — 동일 |
| scenario_report | claude-opus-5 | `gemini-3.1-pro-preview` (**preview뿐** — SSOT 미상주, 안정판 대기) | **불가(현시점)** — 하단 결격 사유 |

주: GA 후보 2종만 configs에 병기한다. scenario_report 후보는 preview 폐기 창(최소 2주 예고)이 재검증
주기보다 짧아 SSOT에 상주시키지 않는다 — 본 문서가 후보 기록의 원본이다(aaa-critic 결함 2, ⓐ안).

## 2. 비용 비교 (표준 단가, $/MTok, 2026-08-02)

| 역할 | Claude | in / out | Gemini 후보 | in / out | 단가 절감 |
|---|---|---|---|---|---|
| scenario_report | opus-5 | 5.00 / 25.00 | 3.1-pro-preview | 2.00 / 12.00 (≤200k 컨텍스트) | ~55% |
| amber_summary | sonnet-5 | 3.00 / 15.00 (인트로 2.00/10.00, ~2026-08-31) | 3.6-flash | 1.50 / 7.50 | ~50% |
| daily_digest | haiku-4-5 | 1.00 / 5.00 | 3.5-flash-lite | 0.30 / 2.50 | ~65% |

- 배치 50% 할인은 양쪽 동일(K-12 경로 등가). Gemini 배치는 24h 목표 턴어라운드(SLA 아님) — 실시간 경로 금지 규율 동일 적용.
- **절대액 관점**: 본 시스템의 고정 호출은 daily_digest 1콜/일뿐(D-20). Haiku 기준 월 $0.5 미만이라
  단가 절감의 절대 효과는 미미하다. 절감이 의미를 갖는 경로는 대량 백필·요약(K-12)과 scenario_report 다발 국면 정도.
- **무료 티어**: flash 계열 존재(3.6-flash·3.5-flash-lite 등). 단 ① 그라운딩·배치·캐싱 **제외** ② 모델별
  RPM/RPD 공식 수치가 문서에서 제거됨(AI Studio 대시보드로 이관 — 계정별 실측 필요) ③ **무료 티어
  데이터는 제품 개선에 활용**된다고 명시 — 시장 판단 텍스트가 학습에 쓰일 수 있어 유료 티어 사용을 전제로 판단한다.

## 3. 기능 패리티 (시스템 요구 기능 기준)

| 요구 | Claude | Gemini | 판정 |
|---|---|---|---|
| 구조화 출력 (scenario-snapshot/1) | output_config.format(json_schema), parse() | responseSchema — pydantic `model_json_schema()` 전달을 공식 시연. 단 "schema subset" 명시, `$defs` 지원 미확인, 深중첩 거부 가능 | 가능 추정, **실측 필수** (§5-①) |
| 웹 검색 (scenario_report의 web_search: true) | web_search 서버 도구 | Google Search grounding — **유료 티어 전용**, 3.x는 쿼리당 과금($14/1k, 월 5k 무료) | 가능하나 과금 구조 상이 |
| 구조화 출력+도구 동시 사용 | 지원 | "Gemini 3 시리즈만" 지원 | 3.x 한정 가능 |
| 배치 (K-12) | 50% | 50%, 24h 목표 | 동등 |
| 프롬프트/컨텍스트 캐싱 | 읽기 ~0.1× | 캐시 토큰 별도 단가 + 저장료 | 대체로 동등 |
| usage 토큰 집계 | usage | usageMetadata (+ thinking 토큰 분리 집계) | 가능 |
| 모바일 네이티브 REST (M2) | x-api-key 헤더 | x-goog-api-key 헤더. 공식 Kotlin SDK 부재(Firebase AI Logic 권유), REST 직접 호출 가능 | 가능 — 키 보안 프로파일은 Claude와 동일(사용자 소유 키를 Keystore 저장, K-17) |
| 서버 Python SDK (S 트랙) | anthropic | google-genai (GA). 구 google-generativeai는 2025-11 deprecated | 가능 |
| **ID 핀 규율 (D-20)** | 무날짜 ID = 고정 스냅샷 | stable="usually don't change"(고정 보장 아님) / preview=**최소 2주 예고 후 폐기 가능** / `-latest`=hot-swap | **상위 티어 결격** (아래) |

## 4. scenario_report 대체 불가 사유 (현시점)

1. **안정 최상위 모델 부재**: Gemini 3.x Pro의 stable ID가 존재하지 않는다(preview뿐). 유일한 안정
   Pro인 gemini-2.5-pro는 **2026-10-16 shutdown 예고**. preview ID를 SSOT에 활성값으로 넣으면
   2주 예고 폐기 리스크를 판단 계층의 핵심 산출물이 지게 된다 — D-20 핀 규율과 정면 충돌.
2. **품질 패리티 미검증**: scenario_report는 시스템 산출물 가치를 좌우한다(D-20). 프롬프트·계약이
   실제 가동되는 M2 이후에만 A/B 평가가 가능하다. 무검증 교체는 D-04 규율 위반.
3. 그라운딩 과금 구조가 다르고(쿼리당) 무료 티어 불가 — 비용 모델 재산정 필요.

## 5. 전환 조건 (gemini_model 활성화의 게이트)

① **스모크**: GEMINI_API_KEY 확보 후 data-verifier 실측 — 모델 목록 확인 + 각 후보 ID 호출 +
   **scenario-snapshot/1 pydantic 스키마의 responseSchema 왕복 파싱 성공** (subset 제약 실측).
   scenario_report가 요구하는 **구조화 출력+도구(그라운딩) 동시 사용은 그 조합 자체가 Gemini에서
   preview 기능**이므로(2026-08-02 재확인), 스모크에서 이 조합의 동작·안정성을 별도 확인한다.
② **scenario_report 한정**: Gemini 3.x Pro **안정판 출시 전 전환 금지**. 후보 ID는 configs에
   상주시키지 않으며 본 문서 §1이 원본이다.
③ **품질 A/B**: M2에서 프롬프트 가동 후 동일 evidence-pack 입력으로 Claude/Gemini 산출 비교,
   품질 게이트(구조 준수·수치 정합) 통과 확인. 채택은 사용자 승인.
④ 분기 C-주기 재검증(D-20 §20.3)에 Gemini 모델 목록·폐기 일정도 포함한다. 단 **전환 검토에 착수하는
   시점에는 주기와 무관하게 즉시 재조회**한다 — preview 폐기 예고 창(최소 2주)이 분기 주기보다 짧다.

## 6. 미확인 항목 (실측 대기)

- 무료·유료 티어의 모델별 RPM/RPD/TPM 공식 수치 (AI Studio 계정 대시보드에서만 확인 가능)
- responseSchema의 `$defs`·깊은 중첩 스키마 수용 여부 (scenario-snapshot/1 실왕복으로 판정)
- `responseJsonSchema` 필드 존재 여부 (문서 표기 불일치 — API 레퍼런스는 responseSchema)
