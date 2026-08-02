# TASK: branch-console P3 — 시나리오 엔진

> **[v3 델타]** (CHANGES_V3 §4)
> ① ST-P3-07 신설 예고 — 스냅샷 발행 모듈(INT 대비, 상세는 INT TASK 문서에서 정의).
> ② 명칭 매핑: G4=GS4 (MASTER_PLAN v3 Track S).

근거: D-02, D-08, 아키텍처 L3, 타당성 검토 6장 · 선행: G3(P2+AL) 통과 · 규칙: CLAUDE.md
소비: contracts/(snapshot·evidence·analogue), prompts/scenario_report.md, statemachine.yaml llm_tiering

## 목표
ORANGE/RED 진입 시 evidence pack을 조립해 Claude API 구조화 출력으로 `scenario-snapshot/1`을
생성하고, 검증·저장·렌더·발송까지 완결한다. **핵심 품질 규칙: analogue 인용 강제와 무효화 조건 검증.**

## 서브태스크

```
ST-P3-01 ──▶ ST-P3-02 ──▶ ST-P3-03 ──▶ ST-P3-04 ──▶ ST-P3-06
                               └────▶ ST-P3-05 (병렬)
```

### ST-P3-01 evidence 조립기
`judgment/assemble.py`: 틱 결과 + MarketSnapshot + news_clusters + `judgment/analogue.find()` top-3
+ macro_calendar_7d(수동 유지 yaml `configs/macro_calendar.yaml` 신설 — 허가된 추가, FOMC·금통위·CPI 일정)
→ EvidencePack 생성·검증·lake 기록. 완료: 픽스처 조립 테스트(2024-08-05 리플레이 evidence 골든 고정).

### ST-P3-02 리포트 생성기
`judgment/report.py`: prompts/scenario_report.md 로드(코드 내 프롬프트 문자열 금지) →
Messages API 호출, `output_config.format = ScenarioSnapshot.model_json_schema()`,
모델·web_search 여부는 statemachine.yaml llm_tiering을 따름. prompt caching: 시스템 프롬프트+스키마 설명 고정 블록.
재시도 1회(검증 실패 시 오류 요지를 피드백에 포함). 완료: mock 클라이언트 테스트(정상/검증실패/타임아웃 3경로).

### ST-P3-03 품질 게이트 (코드 검증기)
LLM 출력의 기계 검증 — 계약 통과에 더해:
① 각 scenario의 kr_impact.kospi_range_pct가 인용 analogue들의 해당 horizon 경로 min/max ±50% 밴드 내,
이탈 시 narrative에 "편차 사유" 문구 존재 ② invalidation에 지표 id 또는 수치+기간 포함(정규식) ③ Σprob ≤ 1.1.
실패 시 재생성 1회 → 재실패 시 스냅샷을 draft로 저장하고 텔레그램에 "검증 실패" 플래그 발송.
완료: 위반 픽스처 3종이 각각 정확히 걸리는 테스트.

### ST-P3-04 저장·렌더·발송
Postgres JSONB + lake 기록, jinja2 → `reports/<ts>_<phase>.html`(시나리오 카드·트리거 표·analogue 표),
텔레그램: 요약 5줄 + HTML 파일 첨부. RED 이탈 시 postmortem 스텁 md 생성(무효화 조건 적중 여부 표 포함 — 7장 검증 방법론).
완료: 2024-08-05 evidence 골든으로 HTML 생성 스냅샷 테스트.

### ST-P3-05 다이제스트 승격 (병렬)
AMBER 이상에서 다이제스트에 "현재 국면·직전 스냅샷 요약·무효화 조건 관찰 현황" 섹션 추가
(코드가 계산, LLM은 서술만). 완료: once 통합 테스트.

### ST-P3-06 라이브 스모크
`make report-once` — 2024-08-05 리플레이 evidence로 **실제 API 1회 호출**(Sonnet 티어로 비용 절감 허용),
계약+품질 게이트 통과 스냅샷과 HTML 산출. 결과물을 게이트 리포트에 첨부.

## 완료 기준
전 테스트 green + ST-P3-06 산출물 + 운영 전환 체크(OPERATIONS_RUNBOOK 3장 배포 절차 리허설 로그).
`docs/gates/GATE_G4.md` 작성 → 사용자 승인 → **상시 운영 개시** (P4는 선택).
