# CLAUDE.md — branch-console 프로젝트 지침

이 저장소는 "글로벌 분기점 감지 → 한국 증시 조건부 시나리오" 시스템이다.
설계 근거는 `docs/P0_DESIGN_DECISIONS.md`, 전체 시퀀스와 게이트는 `docs/MASTER_PLAN.md`를 따른다.
시스템 분리·프로파일은 docs/ARCHITECTURE_SPLIT.md, 품질·비평 절차는 docs/AAA_QUALITY_STANDARD.md, 백테스트는 docs/BACKTEST_PLAN.md.
**현재 phase의 TASK 문서 범위 밖 작업 금지. 다음 phase는 게이트 리포트 + 사용자 승인 후에만 착수한다.**

## 0. 운영 모델: Advisor / Worker

- **메인 세션 = Advisor.** 요구사항 분석, 작업 분해, 브리프 작성, 결과 검증, 사용자 보고만 수행한다. 구현 코드를 직접 작성하지 않는다.
- **구현 노동은 전부 서브에이전트(Worker)에게 위임한다.** Task 도구로 위임하고, 작업 난이도에 맞는 하위 모델을 지정한다: 정형 구현·테스트 작성은 `sonnet`, 단순 반복(픽스처 생성, 보일러플레이트)은 `haiku`, 까다로운 알고리즘·디버깅만 `opus`(모델 문자열은 최신 사용 가능 버전으로).
- 서로 독립적인 작업은 **한 메시지에 다중 Task 호출로 병렬 위임**한다. TASK 문서의 의존성 그래프를 따른다.
- **서브에이전트는 대화 이력을 볼 수 없다.** 브리프에는 반드시 포함: 관련 파일 경로, 근거(참조할 결정 번호 D-xx), 프로젝트 컨벤션 요약, 알려진 함정(K-xx), 완료 기준(통과해야 할 테스트 명령). 파일에 없는 결정은 존재하지 않는 결정이다 — 새 결정은 즉시 문서에 물질화한다.
- Worker 완료 후 Advisor는 반드시 검증한다: 테스트 실행 결과 확인 → SSOT 위반 grep(아래 1장) → PROGRESS.md 갱신.

## 1. SSOT 규칙 (위반 시 리뷰 반려)

- 임계값·가중치·전이 조건·스케줄은 오직 `configs/*.yaml`. 코드에 숫자 하드코딩 금지. 검증: `grep -rn "2\.5\|0\.4" --include="*.py" src/`류로 매직넘버 점검.
- 판단 계층 입출력 스키마는 오직 `contracts/*.py`. dict 즉석 조립 금지, 모델 생성·검증 필수.
- lake는 append-only. `lake/` 모듈 외부에서 Parquet 쓰기 금지, 수정·삭제 API를 만들지 않는다.
- LLM 프롬프트는 오직 `prompts/*.md`에서 로드. 코드 내 프롬프트 문자열 금지. 슬롯 수정은 TASK가 허가한 범위만.
- 문서(설계·보고)는 한국어, 코드·식별자·커밋 메시지는 영어.
- 위임 시 .claude/agents/의 전용 서브에이전트를 사용한다: 계획=plan-architect, 품질 판정=aaa-critic(수정 권한 없음),
  Python 구현=python-implementer, Kotlin/Android 구현=kotlin-implementer, UI·그래픽=ui-craftsman,
  백테스트 실행·분석=backtest-analyst, 산출물 기계 검증=qa-verifier, 외부 API 실측=data-verifier.
- 모든 서브태스크는 qa-verifier → aaa-critic 2단 판정을 PASS해야 완료다(AAA_QUALITY_STANDARD §1).
- 모델 배정은 D-20 §20.2 고정: Advisor=claude-opus-5, 계획·비평=claude-opus-5, 구현·검증=claude-sonnet-5.
  에이전트 model 필드는 전체 ID로 쓰고, CLAUDE_CODE_SUBAGENT_MODEL은 설정하지 않는다(프론트매터를 덮어씀).

## 2. 기술 컨벤션

- Python 3.12, `uv` 패키지 관리, `ruff` (lint+format), `pytest`. 타입힌트 필수.
- 시간대: 저장은 전부 UTC aware datetime, 표시·스케줄만 KST. naive datetime 금지.
- 외부 API 호출은 collectors 어댑터 안에서만. 재시도 정책은 `configs/sources.yaml` 준수.
- 비밀값은 `.env`(git 제외) + `os.environ`. 코드·yaml에 키 노출 금지.
- 테스트는 네트워크 금지: 픽스처(parquet/json)로만. 실수집은 `scripts/`의 수동 실행 스크립트로 분리.
- 커밋 단위 = TASK의 서브태스크 단위. 메시지 형식: `p1-03: implement lake writer (append-only)`.

## 3. 알려진 함정 (Worker 브리프에 해당 항목 복사할 것)

- **K-01 yfinance**: 비공식 API. `^MOVE`·`KRW=X`는 결측/지연 잦음. stale 정책(indicators.engine)으로 흡수하고, 실패를 예외로 전파하지 말 것(결측 기록 후 계속).
- **K-02 VKOSPI**: pykrx로 조회 불가할 수 있음. 구현 시 실제 조회 검증 후, 불가하면 `fallback: realized_vol_kospi_20d`(KOSPI 20일 실현변동성 연율화의 z)로 대체하고 sources.yaml에 결과를 기록.
- **K-03 pykrx**: KRX 스크레이핑 기반. 호출 간 최소 1초 간격, 장중 30분 주기 초과 금지, 휴장일은 `exchange_calendars`('XKRX')로 사전 스킵.
- **K-04 ECOS item_code**: `721Y001` 하위 item_code(국고3y, 회사채AA-3y)는 API 메타 조회로 **실측 확인 후** sources.yaml의 VERIFY를 실제 코드로 교체하는 것까지가 과업.
- **K-05 FRED 지연**: T+1. as-of join으로 정렬. "오늘 값"을 요구하는 코드는 PIT 위반.
- **K-06 tz/휴장**: KST cron과 미국 서머타임이 겹치는 지표(daily_us)는 크론을 KST 고정으로 두고 데이터는 as_of로 정렬한다. 크론을 ET로 바꾸지 말 것.
- **K-07 float**: z-score·드로다운 계산은 float64 고정, 반올림은 표시 계층에서만.
- **K-08 bge-m3**: 최초 로드 시 대용량 다운로드. 모델 리비전 고정(pin), CPU 스레드 수 명시, 배치 임베딩. 테스트는 소형 더미 임베더로 대체.
- **K-09 GDELT**: 노이즈·중복 큼. 원시 volume/tone 저장만 하고 판정은 z-score 계층에서. 질의 규칙은 news_topics.yaml 하단 주석 준수.
- **K-10 네이버 뉴스 API**: 일 25,000콜 쿼터. 호출 예산 계산 로직 필수, 초과 전 차단.
- **K-11 HMM look-ahead**: 리플레이·백테스트에서 미래 데이터로 적합된 파라미터 사용 금지. 월별 파라미터 스냅샷 walk-forward 구조 강제.
- **K-12 Batch API**: 대량 백필·요약 생성은 Batch API 사용(비용 50% 절감). 실시간 경로에 배치 지연을 섞지 말 것.
- **K-13 KRX 업종지수 코드**: pykrx 메타로 실측 확정 후 설정에 기록. 추측 코드값 금지.
- **K-14 WorkManager**: 일일 작업은 정시 보장 없음 — 지연 허용 + 앱 실행 시 캐치업(멱등)이 설계다. 정확 알람으로 우회하지 마라.
- **K-15 OEM 절전**: 제조사 절전 관리자가 작업을 죽일 수 있다. 온보딩에서 예외 등록 안내, 틱 누락은 실행 이력에 노출.
- **K-16 assets 드리프트**: configs/prompts는 syncConfigs Gradle task로만 복사, SHA-256 계측 테스트로 일치 강제. 수동 복사 금지.
- **K-17 모바일 키 보안**: API 키는 Keystore/EncryptedSharedPreferences. 코드·assets·로그·백업 포함 금지.
- **K-18 야후계 비공식**: 엔드포인트 변경·차단 상시 가정. Stooq 폴백 경로와 지표별 결측 처리를 함께 구현.

## 4. 상태 추적

- `PROGRESS.md`가 유일한 진행 상태다. 서브태스크 완료 시 즉시 체크 + 한 줄 결과(테스트 통과 여부, 특이사항).
- 세션 시작 시 Advisor는 PROGRESS.md → TASK 문서 → 본 파일 순으로 읽고 재개한다.

## 5. 검증 게이트

각 phase의 완료 기준은 해당 TASK 문서가 정의하고, 게이트 절차(리포트 + 사용자 승인)는
MASTER_PLAN.md를 따른다. P1의 최소 게이트는 아래 3줄이며, 이후 모든 phase에서도
①②는 공통 회귀 게이트로 유지된다(골든 무회귀 원칙):

```bash
uv run ruff check . && uv run pytest -q               # ① 전부 green
uv run pytest -q tests/test_replay_golden.py           # ② D-08 골든 2케이스 (전 phase 공통 무회귀)
docker compose up -d && uv run python -m service.once  # ③ 1틱 실행: DB 기록·텔레그램 발신 확인
```

## 6. 워크플로 v3 (요약)
세션 시작 프롬프트에 ultracode 활성 지시(세션 한정). 설계·비평·회귀분석 턴에 ultrathink.
phase 착수 전 plan council(plan-architect 병렬 → aaa-critic 라운드 → Advisor 병합 → 사용자 승인).
골든 무회귀 × 2프로파일 + (M1 이후) Kotlin 패리티가 전 phase 공통 회귀 게이트다.

## 7. SSOT 보호 훅 (선택 강화)
워크플로 서브에이전트는 편집이 자동 승인될 수 있으므로, .claude/settings.json의 PreToolUse 훅으로
configs/·contracts/·prompts/ 경로의 Write|Edit를 차단하는 가드를 둘 수 있다(현재 phase TASK가 허가한 경우
Advisor가 일시 해제). 훅 스키마는 사용 중인 Claude Code 버전 문서로 확인 후 작성한다.
