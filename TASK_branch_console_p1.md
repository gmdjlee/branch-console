# TASK: branch-console P1 — 시장 트리거 + 상태기계 + 다이제스트 + 알림

> **[v3 델타]** (CHANGES_V3 §4)
> ① ST-05·ST-06은 자체 구현 대신 `engine_ref/` import로 변경한다(중복 로직 금지, D-18·D-19).
> ② 상태기계는 `server_intraday` 프로파일을 명시 주입한다(D-16).
> ③ 명칭 매핑: 본 문서의 P1=S1, G1=GS1 (MASTER_PLAN v3 Track S).

버전: 1.0 · 근거: docs/P0_DESIGN_DECISIONS.md (D-01~D-10) · 규칙: CLAUDE.md

## 목표

`configs/` 3개 파일과 `contracts/` 2개 파일(제공됨, 수정 금지 — 변경 필요 시 Advisor 승인)을 소비하는
P1 시스템을 완성한다: 수집 → PIT lake → 지표 엔진 → 상태기계 → (텔레그램 알림 + Haiku 일일 다이제스트),
그리고 리플레이 하니스로 골든 케이스(D-08)를 통과시킨다.

## 최종 산출 구조

```
src/
├── lake/            # ST-03
├── collectors/      # ST-04a(yfinance) ST-04b(fred) ST-04c(pykrx) ST-04d(ecos)
├── detection/       # ST-05 (transforms, registry, engine)
├── statemachine/    # ST-06
├── judgment/        # ST-08 (P1은 daily_digest만)
├── delivery/        # ST-07 (telegram)
└── service/         # ST-09 (APScheduler 상주 + once 모드)
tests/  scripts/  docker-compose.yml  Makefile
```

## 서브태스크와 의존성

```
ST-01 ──> ST-02 ──> ST-03 ──> ST-04a/b/c/d (병렬) ──> ST-05 ──> ST-06 ──> ST-07/08 (병렬) ──> ST-09 ──> ST-10
```

### ST-01 스캐폴드
uv 프로젝트 초기화(python 3.12), ruff/pytest 설정, Makefile(`make test`, `make lint`, `make once`, `make replay`),
.env.example(FRED_API_KEY, ECOS_API_KEY, ANTHROPIC_API_KEY, TELEGRAM_*, HEALTHCHECK_URL).
docker-compose.yml: app / `timescale/timescaledb-ha:pg16`(pgvector 포함 이미지 확인) / grafana. 완료: `make lint` green.

### ST-02 계약 통합
제공된 contracts/를 src 패키지에서 import 가능하게 배치, `ScenarioSnapshot.model_json_schema()` 스냅샷 테스트 작성
(스키마가 의도치 않게 변하면 실패하도록 golden json 고정). 완료: `pytest tests/test_contracts.py`.

### ST-03 lake writer
`lake.append(records)`: Parquet, 파티션 `source=/date=`, 레코드 (observed_at, as_of, source, revision, payload).
append-only 강제: 기존 파일 overwrite 경로가 코드에 존재하지 않아야 함. `lake.read_asof(source, asof)` 제공(DuckDB).
완료: 왕복 테스트 + "동일 as_of 재수집 → revision 증가" 테스트. 함정: K-05, K-07.

### ST-04a~d 수집 어댑터 (병렬 위임 가능)
공통 인터페이스 `Collector.fetch(since) -> list[Record]`, sources.yaml의 재시도·rate limit 준수, 결과는 lake.append만 호출.
- 04a yfinance: 심볼 6종. 함정 K-01. 픽스처 기반 파서 테스트.
- 04b fred: 2시리즈. 함정 K-05.
- 04c pykrx: index_ohlcv(1001), investor_trading_value(외국인), vkospi(검증→불가 시 fallback 구현+sources.yaml 갱신). 함정 K-02, K-03.
- 04d ecos: 721Y001 메타 조회로 item_code 실측 확정 → sources.yaml VERIFY 교체 → 국고3y·회사채AA-3y 수집. 함정 K-04.
완료 기준(각자): 픽스처 파싱 테스트 green + `scripts/collect_<name>.py` 수동 1회 실행 성공 로그.

### ST-05 지표 엔진
registry 로더(스키마 검증), transforms 라이브러리(zscore, delta_bp, ratio, drawdown_from_high, rolling_corr,
neg_zscore, gated, max_severity), severity 매퍼, modifier 규칙 2종, 결측 분모 제외(D-02), stale 정책.
**골든 유닛테스트**: 합성 시계열로 각 transform의 기대 severity를 표로 고정.
완료: `pytest tests/test_engine.py` (지표 15종 전부 케이스 존재).

### ST-06 상태기계
statemachine.yaml 소비. 순수 함수 `step(state, tick_result) -> (state, transitions)`.
전이표 테스트 필수 케이스: sustain 미충족 승격 거부 / skip_levels(GREEN→RED) / 강등 6틱 / min_dwell / 쿨다운 재승격.
완료: `pytest tests/test_statemachine.py`.

### ST-07 텔레그램
on_enter/on_exit 액션 발신. 메시지 포맷: 국면 전이, composite, 발화 지표 상위 5. 네트워크는 어댑터 mock 테스트.

### ST-08 일일 다이제스트
17:10 KST: 당일 composite 추이·발화 지표를 Haiku(claude-haiku-4-5)로 5문장 한국어 요약 → 텔레그램.
Anthropic 호출부는 인터페이스로 격리(mock 테스트). ORANGE/RED 심층 리포트는 **P3 범위 — 구현하지 말 것.**

### ST-09 상주 서비스
APScheduler로 statemachine.yaml의 schedules 구동, `python -m service.once`(단일 틱, 통합테스트·수동확인용),
healthcheck ping, 휴장일 스킵(K-03), 예외 시 해당 소스만 결측 처리하고 서비스 생존.

### ST-10 리플레이 하니스 + 골든 테스트
`make replay START END`: lake 픽스처만 읽어 틱 시퀀스 재현(수집 없음), 국면 타임라인 출력.
동봉할 픽스처: 2024-07-25~08-09, 2024-05-13~05-24 구간의 실데이터를 scripts/build_fixtures.py로 1회 수집해
tests/fixtures/에 고정(이후 네트워크 불필요).
완료: `pytest tests/test_replay_golden.py` — D-08 양성·음성 2케이스.

## 완료 기준 (전체)

CLAUDE.md 5장의 검증 게이트 3줄 전부 통과. PROGRESS.md 전 항목 체크.

## Advisor 시작 프롬프트 (메인 세션에 붙여넣기)

```
CLAUDE.md, PROGRESS.md, TASK_branch_console_p1.md, docs/P0_DESIGN_DECISIONS.md, configs/, contracts/를 읽어라.
너는 Advisor다. 직접 구현하지 말고, PROGRESS.md 기준 미완료 서브태스크를 의존성 그래프에 따라
서브에이전트에게 위임하라. 독립 작업(ST-04a~d, ST-07/08)은 병렬 위임하라.
각 브리프에는 대상 파일 경로, 참조 결정(D-xx), 해당 함정(K-xx), 완료 기준 테스트 명령을 포함하라.
각 서브태스크 완료 시: 테스트 실행 결과 검증 → SSOT 위반 점검 → PROGRESS.md 갱신 → 커밋.
전 과정에서 configs/와 contracts/의 수정이 필요하다고 판단되면 멈추고 나에게 보고하라.
```
