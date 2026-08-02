# P0 설계 결정 기록 (Design Decisions)

작성일: 2026-07-19 · 상태: 확정 (백테스트 보정 조항 포함)

## D-01. 지표 세트: 5축 15종 + 뉴스 2종 예약

vol_global(3) · credit(3) · rates_fx(3) · global_price(2) · kr_flow_price(4)로 총 15종을 활성화한다. 축 구성의 근거: 역사적 분기점(2011 미국등급, 2015 위안화, 2018Q4, 2020 COVID, 2022 긴축, 2024-08 캐리 청산)은 예외 없이 변동성·신용·환율 중 2개 축 이상이 동시 발화했다. 단일 축 발화는 노이즈일 확률이 높아 ORANGE 이상 승격에 `distinct_axes >= 2`를 요구한다. 뉴스 축 2종은 레지스트리에 선언만 하고 `enabled: false`로 P2까지 잠근다 — 스키마 변경 없이 켤 수 있게 하기 위함.

## D-02. 복합 점수 공식

`composite = 100 × Σ(wᵢ·sᵢ) / Σ(wᵢ·3)`, s ∈ {0,1,2,3}. 결측 지표(optional 포함)는 분모에서 제외해 결측이 점수를 왜곡하지 않게 한다. 가중치는 "해당 지표가 한국 증시 분기점을 얼마나 직접 가리키는가"로 배분했고(USD/KRW·VIX·HY OAS = 3.0 최상위), 초기값은 가설이다 (D-07).

## D-03. 상태기계 파라미터

승격 sustain 2틱 / 강등 sustain 6틱 / 최소 체류 4틱 / 재승격 쿨다운 6틱. 비대칭의 이유: 분기점 감지에서 오탐(잘못된 승격)의 비용은 리포트 1건이지만, 플래핑(승격-강등 반복)의 비용은 신뢰 상실이다. 강등을 어렵게 만들어 "한 번 켜진 경계는 천천히 꺼지는" 보수적 계기판으로 설계한다. `skip_levels: true`는 2024-08-05형 갭 이벤트(하루 만에 GREEN→RED 조건 충족)를 위해 필수.

## D-04. 임계값 출처와 성격

VIX 백워데이션(VIX/VIX3M ≥ 1.0), HY OAS 5일 +40bp, KOSPI 60일 고점 대비 -4.5% 등은 실무 관례와 과거 사건 관측치에서 가져온 **초기 가설**이다. 정밀 보정은 P1 완료 후 리플레이 하니스에서 수행한다. 임계값의 유일한 저장소는 `configs/indicators.yaml`이며 코드 하드코딩은 금지한다.

## D-05. HMM은 P2로 이연

기존 7-알고리즘 앙상블의 HMM을 RED 승격 보조 확인(고변동 국면 사후확률 ≥ 0.8)으로 쓰되, P1은 규칙 기반만으로 출시한다. 이유: HMM 파라미터 추정에는 리플레이 하니스가 먼저 필요하고, 규칙 기반만으로도 골든 케이스(D-08)를 통과할 수 있어야 시스템이 건강하다.

## D-06. PIT 원장 규율

lake는 append-only Parquet, 레코드는 (observed_at, as_of, source, revision, raw). 백테스트·리플레이는 lake만 읽는다(운영 DB 무접촉). FRED류 T+1 지연 데이터는 as-of join으로 정렬해 "그날 알 수 없었던 값"의 유입을 물리적으로 차단한다.

## D-07. 임계값 보정 조항

P1 리플레이 하니스 완성 후, 2015·2018·2020·2022·2024 창에서 (리드타임, 오탐률) 곡선을 산출해 warn/crit를 재조정한다. 조정 결과는 registry_version을 올리고 본 문서에 추가 기록한다.

## D-08. 골든 케이스 (수용 기준)

- **양성**: 2024-07-25 ~ 2024-08-09 리플레이 → 2024-08-05 kr_close 틱까지 **ORANGE 이상 도달** (RED 허용). 발화 축에 vol_global과 kr_flow_price가 반드시 포함.
- **음성**: 2024-05-13 ~ 2024-05-24 리플레이 → 전 구간 **AMBER 이하 유지**.
- 이 두 조건이 P1의 최종 완료 기준이며 `tests/test_replay_golden.py`로 고정한다.

## D-09. 하드웨어 확정: Ryzen 7 8745HS

8C/16T로 전 워크로드에 여유. 구성 확정: **RAM 32GB(16×2 듀얼채널 — 780M iGPU 대역폭·Postgres 캐시), NVMe 1TB(주) + M.2 슬롯2에 백업용 SSD 분리, Ubuntu Server 24.04 LTS + Docker Compose, BIOS에서 AC 전원 복구 자동 부팅 활성화, 유선 2.5GbE 고정 IP**. bge-m3는 P2부터 로드하며 8745HS CPU 배치 임베딩으로 충분(GPU 불요). 소형 LLM 실험(Ollama)은 시스템 여유분으로 가능하나 운영 컨테이너와 리소스 격리(cgroup limit) 후 실행.

## D-10. 비채택 재확인

Kafka·Airflow·LangGraph는 아키텍처 문서 4장의 기각 사유 유지. P1 저장소는 처음부터 Postgres(TimescaleDB+pgvector 확장)로 시작한다 — SQLite로 시작해 갈아타는 비용이 더 크다.

---

# 정합성 점검 반영 (2026-08-01)

배경: P0 설계 확정 이후 실제 시장에서 사상 최대 규모의 국내 증시 급락이 발생했고, LLM 모델 세대도 교체되었다.
아래는 그 결과 확정된 추가 결정이다. 상세 근거는 `docs/CONSISTENCY_AUDIT_2026-08.md` 참조.

## D-11. 모델 티어링 갱신 (F-01)
> [2026-08-02] 본 결정의 모델 ID는 유효함이 재확인됨. 운용 규칙은 D-20이 승계한다(런타임 티어링 + 개발 시 배정 + 분기 재검증).
llm_tiering의 모델 ID를 현행 세대로 교체: 심층 리포트 `claude-opus-5`, AMBER 요약 `claude-sonnet-5`,
일일 다이제스트는 Haiku 계열 유지. 모델 ID는 시간이 지나면 반드시 낡으므로, C-주기마다 공식 모델 문서로
재확인하고 statemachine.yaml만 수정한다(코드 수정 금지).

## D-12. USD/KRW 방향 규칙 변경 (F-03) — 설계상 가장 중요한 수정
`usdkrw_z`의 direction을 `higher_is_risk` → `abs`(양방향 급변)로 변경한다.
근거: 2026년 7월 국내 증시 폭락 국면에서 원화는 약세가 아니라 **강세**를 보였다(수출대금·해외상장 대금
환전 등 달러 공급 요인이 외국인 매도발 달러 수요를 상쇄). 최고가중(3.0) 지표가 최대 폭락에서 침묵하는
구성은 rates_fx 축 발화를 막아 `distinct_axes` 요건 충족을 방해한다. 방향 가정을 데이터에 맞춘다.
**단, 이는 가설 수정이며 C1에서 양방향 임계를 재보정해야 확정된다.**

## D-13. KR 구조 지표 2종 예약 (F-04)
`krx_halt_events`(사이드카·서킷브레이커 발동 횟수), `margin_leverage_stress`(신용융자·레버리지 쏠림)를
레지스트리에 `enabled: false`로 선언한다. 전자는 관측 즉시 확정적 스트레스 신호이고, 후자는 2026 폭락의
구조적 증폭 요인(단일종목 레버리지 ETF·빚투)에 대한 사전 경고 지표다. 활성화 여부는 C1에서 판정한다.

## D-14. 골든 케이스 확장 제안 (F-06, 사용자 승인 대기)
D-08의 골든 2케이스는 유지하되, C1 완료 후 **2026-07-28을 세 번째 골든(양성, 극단 케이스)로 승격**할 것을
제안한다. 2024-08-05가 "빠른 외생 충격"의 대표라면 2026-07은 "국내 구조 증폭형 대폭락"의 대표로 성격이 다르다.
승격은 사용자 승인 후 D-08을 개정하는 방식으로만 한다.

---

# 아키텍처 v3 결정 (2026-08-02)

배경: 모바일(`branch-console-android`)·서버(`branch-console`) 이원 체계로 아키텍처를 분리하며 확정한 결정이다.
각 결정의 전문은 `docs/ARCHITECTURE_SPLIT.md` 참조. 아래는 요약(각 3줄 이내)이다.

## D-15. 시스템 분리 — 역할 정의 (확정)
모바일과 서버는 동일 SSOT를 공유하는 독립 실행체이며, 어느 한쪽이 죽어도 다른 쪽은 완결적으로 동작한다.
모바일은 일 1틱 확정+온디맨드 프리뷰, 서버는 24/7 30분 틱 감시로 역할이 나뉘고, 연동은 INT 단계에서
서버 스냅샷을 모바일이 추가 소스로 소비하는 단방향뿐이다.

## D-16. 상태기계 프로파일 이원화 (확정)
기존 히스테리시스 파라미터(승격 2틱·강등 6틱·dwell 4틱)는 30분 틱 전제값이라 일 단위엔 부적합해,
statemachine.yaml에 `profiles:` 블록(server_intraday/mobile_daily)을 신설하고 엔진이 프로파일을 주입받는다.
전이 구조·composite 공식·distinct_axes·skip_levels는 동일하고 틱 단위 카운트만 다르며, 골든 케이스는 두 프로파일 모두 통과해야 한다.

## D-17. 프리뷰(온디맨드 갱신) 비확정 규율 (확정)
온디맨드 프리뷰는 잠정 composite·지표 심각도를 표시만 하며 국면 전이를 커밋하지 않는다(확정은 일일 확정 틱에서만).
crit 초과 시 잠정 경보는 허용하되 UI에 PREVIEW 배지와 as_of를 항상 표기하고, LLM은 자동 호출하지 않는다.

## D-18. SSOT 공유 방식 — 단일 저장소, 자동 동기화, 패리티 게이트 (확정)
단일 모노레포에 configs/contracts/prompts SSOT를 두고 양 시스템이 공용하며, syncConfigs+SHA-256 계측
테스트로 모바일 assets 동기화 드리프트를 차단한다. engine_ref(Python)가 계산 명세의 실행 가능한 정의이고,
Kotlin 엔진은 동일 픽스처에 대해 |Δcomposite|≤0.05 패리티(BT-05)를 만족해야 한다.

## D-19. 개발 순서 역전과 백테스트 하니스 신설 (확정)
모바일 우선 개발엔 검증된 레지스트리가 필요하지만 실측 PIT 보정(C1)은 서버 lake가 있어야 가능한 순환을
끊기 위해, M0에서 Python 백테스트 하니스를 개발기 계기로 신설한다. 소급 수집 근사-PIT로 레지스트리 0.3.0을
산출하되, 최종 확정은 여전히 C1(서버 실측 lake)이다.

## D-20. 모델 ID·역할 배정 확정 (확정)
1차 판정 오류를 정정 — `claude-opus-5`/`claude-sonnet-5`는 실재하는 현행 ID다. 런타임 티어링은 심층
리포트=opus-5, AMBER 요약=sonnet-5, 다이제스트=haiku-4-5 고정, 개발 시 배정은 Advisor·계획·비평=opus-5,
구현·검증=sonnet-5다. 분기 C-주기마다 모델 목록을 실조회해 재검증한다(기억이 아니라 조회로 판정).

## D-21. 서버↔모바일 연동(INT) 범위 사전 정의 (범위만 확정, TASK는 S3 게이트 후 작성)
서버→모바일 단방향 연동으로, 서버가 매 틱 `scenario-snapshot/1`·상태 요약 JSON을 발행(Drive 동기화 또는
Tailscale HTTP)하고 모바일은 이를 추가 provider로 등록해 있으면 우선 표시, 없으면 자체 수집으로 폴백한다.
P4 모듈 C는 INT로 흡수·폐지되고 모듈 A/B는 선택 확장으로 존치한다.

D-22(기능 동등성 매트릭스)·D-23(온디맨드 결과 동등성 규율)도 `docs/ARCHITECTURE_SPLIT.md`에 확정 기록되어 있다.

## D-24. LLM 공급자 옵션화 — Gemini 병기 (2026-08-02, 사용자 지시 → 철회)
런타임 llm_tiering에 `provider:` 스위치(기본 anthropic)를 신설하고 **GA 후보 2종만** `gemini_model:`로
병기한다: daily_digest=`gemini-3.5-flash-lite`(GA), amber_summary=`gemini-3.6-flash`(GA).
scenario_report 후보 `gemini-3.1-pro-preview`는 **preview(최소 2주 예고 폐기 가능)라 SSOT에 상주시키지
않는다** — 3.x Pro 안정판 출시 시 기입하며, 후보 기록은 검토서가 담당한다(aaa-critic 결함 2 해소, ⓐ안).
sources.yaml에 `gemini_api` provider(enabled: false)를 추가한다. 기본 비활성 불변식(provider=anthropic,
gemini_api.enabled=false)은 스키마 테스트가 가드한다.
판정: **부분 대체 가능, 전면 대체 비권고(현시점)** — 근거·비용표·전환 조건은
`docs/journal/2026-08-02_gemini_option_review.md`. 전환 게이트: ① GEMINI_API_KEY 스모크
(scenario-snapshot/1 responseSchema 왕복 실측) ② scenario_report는 3.x Pro 안정판 출시 전 금지
③ M2 이후 품질 A/B + 사용자 승인. 분기 C-주기 재검증(D-20 §20.3)에 Gemini 목록·폐기 일정 포함.
개발 하네스(D-20 §20.2)는 본 결정의 대상이 아니다.

**철회(2026-08-02, 사용자 지시)**: Gemini 사용 제외 결정. SSOT(statemachine.yaml provider 스위치·
gemini_model 병기, sources.yaml gemini_api)에서 제거하고, 기본 비활성 불변식을 가드하던 스키마 테스트
2건(tests/test_configs_schema.py)도 함께 제거(가드 대상 소멸). 위 본문의 "스키마 테스트가 가드한다"는
철회 전 서술이다. 검토서(docs/journal/2026-08-02_gemini_option_review.md)는 기록으로 존치.

## D-25. 상태기계·복합점수 실행 의미론 확정 (2026-08-02, MT0-02)

engine_ref 구현(MT0-02)에서 기존 SSOT 문언이 확정하지 않던 실행 의미 3건을 확정한다.
aaa-critic 반려(REVIEW_M0 MT0-02 라운드 1, 결함 D-1~D-3)의 해소이며, 골든(MT0-04)·패리티(BT-05)·
스윕(MT0-05)은 이 의미 위에서 수행한다. configs 값 변경 없음 — 해석의 확정이다.

1. **승격 sustain은 레벨별 연속 충족이다.** 레벨 L로의 승격은 "L의 조건(composite_gte·distinct_axes_gte·
   or_any_crit)이 promote_sustain_ticks 연속 충족"을 요구한다(statemachine.yaml `upgrade:` 주석 문언 그대로).
   skip_levels=true는 이 요건을 충족한 **최고** 레벨로의 직행을 뜻한다. "현재 국면보다 높은 아무 레벨이든
   충족이면 스트릭 유지" 해석은 기각 — 단일 틱 근거로 RED 액션(알림·리포트)이 발화해 D-03의 오탐·플래핑
   방어 취지에 반한다. 레벨별 스트릭은 국면 전이와 무관하게 연속 충족으로 누적되고, 강등 직후에는
   reentry_cooldown_ticks 동안 승격 커밋·스트릭 누적이 모두 정지·리셋된다(기존 규칙 유지).
2. **min_dwell_ticks는 명목값 = 실효 체류다.** 전이가 커밋된 틱을 그 국면의 1틱째로 세고, 강등은
   min_dwell_ticks 틱을 채운 뒤(min_dwell_ticks+1틱째부터) 커밋될 수 있다. 강등 스트릭(demote_below_ticks)
   카운트는 dwell 충족 전에도 누적되며 커밋만 지연된다. server_intraday의 "최소 체류 4틱"은 실효 4틱이다
   — BT-03 스윕 결과 해석의 전제.
   > **부기(2026-08-02, aaa-critic 라운드 3 관찰 O3-1)**: 위 카운터 의미론 하에서 dwell 게이트는
   > `min_dwell_ticks > demote_below_ticks`일 때만 거동에 영향을 준다(강등 스트릭이 차는 시점에 체류
   > 틱수는 항상 스트릭+1 이상이므로). 현행 두 프로파일(server 4<6, mobile 2<3)에서 min_dwell은
   > **무효(inert)**이며 실효 최소 체류는 demote_below_ticks+1(서버 7틱)이다 — 랜덤 4만+전수 48.8만
   > 시퀀스 실측으로 확인. **BT-03 규율**: min_dwell 스윕에서 `min_dwell ≤ demote_below` 구간은 거동
   > 동일 구간이므로 하나의 값으로 취급하고, 평탄한 응답을 "보정 완료"로 오독하지 말 것.
   > **부기(2026-08-03, MT0-05④ BT-03 선정)**: 0.3.0-rc에서 mobile_daily가 `min_dwell_ticks 5 >
   > demote_below_ticks 3`으로 확정되어 이 프로파일에서 min_dwell은 더 이상 inert가 아니다 —
   > §6 플래핑 게이트를 지탱하는 활성 노브다. server_intraday(4<6)는 여전히 inert.
3. **전 지표 결측은 GREEN이 아니라 평가 불능이다.** composite 계산은 (score, coverage=유효가중/전체가중)을
   함께 산출하고, 유효 가중 0(분모 0)이면 score는 None이다. 상태기계는 score None 틱에서 국면·스트릭·
   카운터를 동결한다(전이 없음, 틱 미소비). 근거: D-23 §23.3의 커버리지 규율, PRINCIPLES
   "Fail Fast / Never Suppress Silently". 부분 결측의 분모 제외(D-02)는 불변.
