# M1 실기기 1일 스모크 절차서 (MT1-08c)

- 정본: `docs/plans/M1_PLAN_D.md` §11(S-0~S-6 절차표), M1_PLAN_FINAL.md §1.3(스모크 범위 —
  확정 1회 + 시계 조건 상이 프리뷰 3회, 사용자 실작업 ≈25분, 확장 4종은 M1 비채택)
- 증명 대상: JVM·Robolectric이 증명할 수 없는 것만 — 실제 네트워크 수집값이 사슬을 통과해
  원장에 남는지, WorkManager가 실제로 깨어나 노티를 띄우는지, assets가 APK에 제대로 실리는지.
  사슬의 수치 정합(③~⑦)은 BT-05가, ①②는 MT1-05k가 이미 덮으므로 여기서 재검증하지 않는다.
- 판정: 사람이 화면을 읽고 "정상"이라고 판단하지 않는다. 각 단계에서 앱의 "진단 내보내기"
  (MT1-08d, 설정 화면)로 JSON을 뽑아 `docs/gates/evidence/GM1/`에 저장하고,
  `scripts/check_smoke_evidence.py`가 그 디렉터리를 읽어 기계 판정한다(§3).

## 0. 선행 조건 (착수 전 반드시 확인)

**`configs/statemachine.yaml`의 `profiles.mobile_daily.confirm_time_kst`가 채워져 있어야
S-2를 실행할 수 있다.** 이 값이 비어 있으면 `ConfirmTickConfigLoader.load`가 즉시 명시
실패하고(`profiles.mobile_daily.confirm_time_kst missing ...`), `BranchConsoleApplication`은
`schedulePeriodic`·`triggerCatchupNow` 양쪽을 모두 건너뛴다 — 확정 틱 파이프라인 자체가
돌지 않는다. MT1-00g(3거래일×6시점 폴링, 사전등록 판정)가 이 키를 SSOT에 기입하기 전에는
S-2 이후 단계에 착수하지 마라(작성 시점 PROGRESS.md 기준 미기입 — MT1-06 블록 상태).

그 밖의 선행 조건: MT1-08(노티·홈·자격증명·온보딩) 구현이 병합되어 있을 것, 실기기(또는
에뮬레이터가 아닌 실물 — WorkManager·OEM 절전·실네트워크 증명이 목적이므로) 1대, USB 연결(S-6용).

## 1. 절차표 (S-0~S-6)

수행 주체 열의 **굵은 글씨**는 사용자가 직접 수행해야 하는 항목이다. 소요는 설계 추정이며
(D §13-7, 실측 아님), 실제 부담은 최초 1회 수행 후 확정된다 — 어느 요일에 수행했는지를
§4의 관측표에 기록해 해석 가능하게 한다(S-3(a) 스테일 억제 여부가 요일에 좌우된다, §2.5.4).

| # | 단계 | 수행 주체 | 소요(추정) | 통과 판정(§3의 어느 체크에 대응하는지) | 증빙 |
|---|---|---|---|---|---|
| S-0 | 설치·온보딩: 사이드로드, 키 4종 입력(FRED·KRX ID/PW·ECOS 선택·(KIS 미보유 시 생략, M-28)), **OEM 절전 예외 등록**(K-15, 설정 화면 안내), 기기 시간대 KST 확인 | **사용자** | 5분 | 온보딩 완료·키 저장 확인(설정 화면에 값이 아니라 상태만 표시됨을 확인) | 스크린샷 1장 |
| S-1 | 초기 백필 → 웜업 완료 대기 | 앱(자동) | 자동(수 분~) | 웜업 완료 후, **아직 확정 틱이 커밋되기 전** 진단 내보내기 실행 → `diag-s1.json` | diag JSON (`check_smoke_evidence.py` S-1) |
| S-2 | **확정 시각 이후 자동 실행**(§0 선행 조건 충족 후) | 앱 / **사용자는 노티 수신만 확인** | 5분 | 확정 틱 커밋 확인 후 진단 내보내기 → `diag-s2.json`. 노티 1건(전이가 있을 때만 — 전이가 없으면 미발신이 정상) | diag JSON (S-2) + 노티 스크린샷 |
| S-3 | **프리뷰 3회 — 서로 다른 시계 조건에 배치**: (a) 장중 ≈13:00, (b) 확정 틱 직후 ≈+10분, (c) 저녁 ≈21:00. 각 탭 직후 진단 내보내기 | **사용자**(각 1탭 + 내보내기) | 각 2분 | 3회 모두 국면·`tick_input` 행수 불변, `PREVIEW` 배지·`as_of`·coverage가 화면에 표시됨을 육안 확인 → `diag-s3a/b/c.json` | diag JSON ×3 (S-3(a/b/c)) + 스크린샷 3장 |
| S-4 | 같은 날짜 확정 틱 수동 재실행(설정 화면) 후 진단 내보내기 | **사용자**(1탭 + 내보내기) | 1분 | 상태·행수·노티 전부 불변(멱등) → `diag-s4.json` | diag JSON (S-4) |
| S-5 | *(권고, 게이트 조건 밖 — D §13-D13)* 앱 강제 종료 후 다음 거래일 캐치업 관찰 | 사용자 | 다음날 5분 | 누락 거래일 1건이 오름차순 커밋, `is_catchup=true` — 홈/이력 화면 육안 확인으로 충분(기계 판정 대상 아님) | 스크린샷(선택) |
| S-6 | 계측 테스트(USB 연결) | **사용자**(연결) + 에이전트(실행) | 5분 | `./gradlew :app:connectedDebugAndroidTest` green — assets 패키징 SHA-256 포함(`ConfigsAssetsInstrumentedTest`) | 실행 로그 |

사용자 실작업 합계 ≈ 25분(당일 분산: 아침 5 + 13:00 2 + 확정 후 7 + 21:00 2 + 연결 5).

## 2. 판정식 개정 — `phase_commit` 대체 (브리프 aaa 요건 1)

`docs/plans/M1_PLAN_D.md` §11.2의 원안은 S-2/S-3 통과 판정에 "`phase_commit` 행 +1/불변"을
쓴다. **`phase_commit` 테이블은 MT1-03 확정 아키텍처(§2 룸 스키마)에 존재하지 않는다** —
국면은 저장되지 않고 매번 `tick_input` 전량을 fold해 재도출한다
(`com.branchconsole.app.tick.PhaseDerivation.currentPhase`, KDoc: "오늘의 국면은 매번
`StateMachine.run`으로 재도출한다 — 상태를 이어받지 않는다"). 따라서 이 절차서와
`scripts/check_smoke_evidence.py`는 그 기준을 **`tick_input` 행수 + `PhaseDerivation.currentPhase`
값**으로 치환한다:

- S-2: `tick_input` 행수가 정확히 +1 되고, 그 시점의 `current_phase`가 null이 아니게 된다.
- S-3(각 프리뷰 탭): `tick_input` 행수와 `current_phase`가 S-2 시점과 **완전히 동일**해야
  한다(프리뷰는 커밋도, 국면 이동도 만들지 않는다).

## 3. 진단 JSON — 스키마·수집·기계 판정 (MT1-08d)

### 3.1 스키마(브리프 범위 축소)

`docs/plans/M1_PLAN_D.md` §11.3 원안은 `app`/`tick_input[]`/`phase_commit[]`/`tick_run[]`/
`indicators[]`/`preview[]` 6블록(전 행 덤프 포함)을 제안한다. 이 서브태스크의 브리프는 범위를
**카운트 + registry_version + 파생 국면 1개**로 좁혔다(Advisor 판단, PROGRESS.md MT1-08c
항목) — `phase_commit[]`은 부재 테이블이라 애초에 만들지 않고(§2), `indicators[]`/`preview[]`의
지표별 전체 덤프도 만들지 않는다. 실제 내보내기(`com.branchconsole.app.diagnostics.
DiagnosticExport`)가 만드는 스키마:

```json
{
  "app": {
    "version_name": "0.1.0",
    "registry_version": "0.3.1-rc",
    "assets_manifest_sha256": "<64자 hex — 패키징된 assets/ssot.sha256 자체의 SHA-256>"
  },
  "exported_at_epoch_millis": 1234567890123,
  "counts": { "tick_input": 1, "run_log": 2, "observation": 512 },
  "current_phase": "GREEN",
  "last_tick": { "trading_date": "2026-08-10", "coverage": 1.0, "is_catchup": false, "gap_reason": null },
  "last_run": { "trading_date": "2026-08-10", "status": "noop", "detail": "no candidate trading days" },
  "last_success_run": { "trading_date": "2026-08-10", "status": "success", "detail": "committed=1" }
}
```

`last_tick`/`last_run`/`last_success_run`은 아직 해당하는 행이 없으면 `null`이다.
`current_phase`의 값 도메인은 `configs/statemachine.yaml`의 `phases:`(GREEN/AMBER/ORANGE/RED,
`PhaseDerivation.currentPhase`가 반환) — 홈 화면의 `HomeState`(NORMAL/PARTIAL/...)와는 다른
축이니 혼동하지 말 것(aaa C-2). `last_run.detail`은 부트스트랩 게이트가 `WARMUP_INSUFFICIENT`로
막았을 때 `WarmupGate`의 리포트(지표별 원계열 행수·요건)를 그대로 담고 있어(`ConfirmTickRunner`의
`WARMUP_INSUFFICIENT` 분기), S-1에서 "어느 지표가 왜 부족한지"를 볼 수 있다 — 별도 지표별
덤프를 만들지 않고도 §11.3의 취지(웜업 상태 노출)를 충족한다.

**`last_run` vs `last_success_run` (aaa C-1)**: 앱을 여는 행위 자체가 캐치업 1회를 유발한다 —
`BranchConsoleApplication.onCreate`가 콜드 스타트마다 `triggerCatchupNow`를 호출하기 때문이다.
S-2 진단을 내보내려고 앱을 다시 열면(콜드 스타트), 그 캐치업이 "오늘 이미 커밋됨 → 할 일 없음"을
발견하고 `noop` 행을 확정 틱의 `success` 행보다 **나중에** 남긴다 — 그래서 `last_run`(가장 최근
실행)은 실제로는 건강한 실행에서도 `noop`으로 보일 수 있다(콜드/웜 스타트 여부에 좌우돼
비결정적). `last_success_run`은 상태와 무관하게 "`status="success"`인 가장 최근 행"만 가리켜
이 레이스를 피한다 — 위 샘플의 `last_run.status="noop"`이 바로 이 정상 시나리오다.

**K-17**: 위 필드가 전부다(화이트리스트 방식) — 자격증명 값·파생물은 어디에도 없다.
`DiagnosticExportTest`(`mobile/app/src/test/kotlin/com/branchconsole/app/diagnostics/`)가
실제 자격증명을 저장한 상태에서 내보내 값이 섞이지 않음을 단언한다.

### 3.2 수집 절차

설정 화면 하단 "진단 내보내기 (MT1-08d)" 버튼 → SAF `CreateDocument`로 저장 위치를 고른다
(파일명 기본값 `branchconsole-diag-<yyyyMMddHHmm>.json`, UTC). §1의 각 단계 직후 실행하고,
`docs/gates/evidence/GM1/`에 **역할 접두사를 붙여** 복사한다 — `scripts/check_smoke_evidence.py`는
파일명으로 역할을 식별한다(내용의 타임스탬프가 아니라):

```
docs/gates/evidence/GM1/
  diag-s1.json          # S-1 (웜업 완료, 첫 확정 틱 이전)
  diag-s2.json          # S-2 (확정 틱 직후)
  diag-s3a.json         # S-3 (a) 장중
  diag-s3b.json         # S-3 (b) 확정 직후
  diag-s3c.json         # S-3 (c) 저녁
  diag-s4.json          # S-4 (수동 재실행 후)
  shot-*.png            # 스크린샷(S-0/S-2/S-3×3)
  connected-test.log    # S-6 실행 로그
```

파일명은 `diag-<역할>` 뒤에 자유 라벨을 붙여도 된다(`diag-s2-1705.json` 등) — 판정기는
`diag-<role>` 접두사만 본다. 같은 역할에 파일이 2개 이상 있으면(재시도로 다시 저장한 경우 등)
판정기는 모호함 자체를 실패로 보고한다 — 오래된 사본은 지우고 최종본 하나만 남긴다.

**운영 참고(권고)**: 앱을 여는 행위 자체가 캐치업 1회를 유발해 `run_log` 카운트가 내보내기마다
증가한다(§3.1의 "last_run vs last_success_run" 참고) — 진단 내보내기를 위해 앱을 여러 번
열고 닫아도 정상이며, `counts.run_log`가 매번 늘어나는 것은 이상 신호가 아니다.

### 3.3 기계 판정

```bash
uv run python scripts/check_smoke_evidence.py docs/gates/evidence/GM1/
```

exit 0 = 전부 통과. 각 체크는 `PASS`/`FAIL` 한 줄씩 stdout에 찍힌다. 판정 항목(브리프
aaa 요건 2 반영 — 부트스트랩 최초 실행은 +1행/gap 0, 2일차 이후도 +1행/일. 20틱 소급은
앱 미실행이 20거래일을 초과했을 때만 발생하므로 1일 스모크에서는 관측되지 않는다):

- **evidence_files / schema[role]** — 6개 역할 파일이 모두 있고, 각 파일이 필수 키를 갖는지.
- **registry_consistency** — `registry_version`·`assets_manifest_sha256`이 6개 스냅숏 전부
  동일한지(K-16 — 같은 설치 세션 안에서 드리프트가 있으면 그 자체가 이상 신호).
- **S-1** — `diag-s1.json`의 `counts.tick_input == 0`이고, `last_run.status`가
  `WARMUP_INSUFFICIENT`가 **아님**(있다면 웜업이 실제로는 아직 안 끝난 것).
- **S-2** — `tick_input`이 S-1 대비 정확히 +1, `last_tick.gap_reason == null`(gap 0),
  `last_success_run.trading_date == last_tick.trading_date`(그 거래일에 대해 성공 행이
  실제로 존재), `current_phase`가 null이 아님. **`last_run.status`는 보지 않는다**(aaa
  C-1 — 위 "last_run vs last_success_run" 참고, 콜드 스타트 캐치업이 남기는 뒤늦은 `noop`에
  비결정적으로 흔들리기 때문).
- **S-3(s3a/b/c)** — 각각 S-2 대비 `tick_input` 행수·`current_phase`가 완전히 동일(§2).
- **S-4** — S-2 대비 `tick_input` 행수·`last_tick.trading_date`가 동일(재실행이 새 행을
  만들지 않음). `run_log` 행수 증가는 정상이다(아래 참고).

판정기 자체는 `tests/test_check_smoke_evidence.py`가 통과·실패 양방향 픽스처(스키마 결손,
registry 드리프트, S-1~S-4 각 위반 케이스, 역할 파일 누락·중복)로 고정한다.

## 4. GM1 기록 항목 (GATE_GM1.md에 그대로 전재할 것)

이전 라운드(REVIEW_M0, W5 aaa 판정)에서 인계된 항목 — 이번 스모크 결과와 무관하게 게이트
리포트에 승계 기록한다:

1. **채널명 표기 불일치** — 코드 상수는 `provisional_alert`이나 UI/문서에서
   `preview_alert`로 부르는 곳이 섞여 있었다(MT1-08 재작업 중 정리 대상, 스모크 시점에
   실제 알림 채널 설정에서 표시되는 이름을 스크린샷으로 확인해 기록).
2. **구조적 결측 2종(A-1, MT1-04d aaa M-1 정정)** — `krx_credit_spread_delta`(ECOS 배선
   자체는 MT1-04d로 완료됐으나 이 지표의 severity 계산은 Python 정본에 builder가 없어
   여전히 상시 결측, `EcosCollector.kt`/`EcosCoverageTest` 참조) + CDS 미수집 확정
   (M-20 (b))이 겹쳐도, 실측 상한은 27.5/31.0=**0.8871**로 `preview_coverage_min`(0.80)을
   **넘는다** — 이 두 구조적 결측만으로는 억제되지 않는 것이 정상 동작이다. S-3에서
   `suppressed=true`가 나온다면 이 구조적 결측이 아니라 **다른 계열의 실제 런타임 결측**
   (항목 3 참조)이 원인이다 — 그 계열들의 상태를 함께 기록.
3. **무폴백 4계열**(`KRW=X`·`DXY`·`MOVE`·`VIX3M`) — 이 계열들은 실패 시 대체 경로가 없다.
   00a 저널 실측대로 `^MOVE`·`^VIX3M`가 갱신 정지 상태면 `move_index_z`+`vix_term_structure`
   까지 결측되어 23.5/31.0=0.7581<0.80 — 항목 2의 구조적 결측과 겹쳐야 실제로 억제된다.
   S-1 웜업 리포트(`diag-s1.json`의 `last_run.detail`, 있다면)에 이 계열들의 결측이
   보이는지 확인해 기록.
4. **credit 축 발화 표면 축소** — CDS 미수집(M-20 (b))으로 credit 축은 사실상 회사채
   스프레드(`krx_credit_spread_delta`) 단일 계열에 의존한다. 이 지표는 Python builder
   부재로 상시 결측이므로(항목 2) credit 축이 사실상 항상 결측임을 기록(정상 동작 — 버그
   아님, ECOS 키 발급 여부와 무관).

## 5. 관측 항목(노티 트리거 구조 결정 근거 — 판정 대상 아님, 기록만)

- **배터리·기상 프로파일**: S-2~S-4 구간의 배터리 소모(설정 → 배터리 사용량, 수동 확인)와
  기기 제조사(OEM 절전 정책 차이, K-15)를 기록. 실기기 1대분 표본이지만 향후 노티 트리거
  설계(주기적 폴링 vs 이벤트 기반)의 1차 근거가 된다.
- **노티 발화 시각과 틱 완료 시각의 간격**: `run_log`의 확정 틱 완료 시각(`ran_at`)과
  실제 노티가 화면에 뜬 시각의 차이를 기록(초 단위 정밀도 불필요, 체감 지연 유무만).
  이 항목은 노티가 "확정 틱 완료를 즉시 트리거"하든 "주기적 폴링으로 뒤늦게 발견"하든
  **구조 중립적으로 관측 가능**하도록 일부러 구현 방식을 언급하지 않는다 — 어느 쪽이든
  이 간격 하나로 비교할 수 있다.
- **요일**: 스모크를 수행한 요일을 기록(S-3(a) 장중 프리뷰의 "KR 스테일 → 억제" 발현 여부가
  요일에 좌우된다, D §13-7).

## 6. 완료 기준

- `docs/gates/evidence/GM1/`에 §3.2의 6개 diag JSON + 스크린샷 + `connected-test.log`가
  모두 있다.
- `uv run python scripts/check_smoke_evidence.py docs/gates/evidence/GM1/` exit 0.
- §4 GM1 기록 항목 4건이 실측값으로 채워져 있다(추정이 아니라 이번 스모크의 실제 관측).
- GATE_GM1.md가 각 증빙 파일을 **파일명 + SHA-256**으로 인용한다(사후 대체 방지 —
  `sha256sum docs/gates/evidence/GM1/*` 또는 `Get-FileHash`로 생성).
