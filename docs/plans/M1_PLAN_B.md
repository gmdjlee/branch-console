# M1 실행 계획 — 관점 B (데이터·정합성·백테스트)

- 작성일: 2026-08-06 · 작성: plan-architect(관점 B) · 절차: AAA_QUALITY_STANDARD §3 · 입력: `docs/plans/M1_COUNCIL_BRIEF.md`
- 지위: **관점 B를 대표하되 그 자체로 실행 가능한 전체 계획**. A(아키텍처)·C(UX·운영) 관점 요구사항도 §17에 최소선으로 명시했다.
- 범위 규율: MT1-01~08 유지 + 세분화(축소 없음). SSOT(`configs/`·`contracts/`·`prompts/`) 및 기존 코드는 본 계획에서 **수정하지 않는다** — 필요한 변경은 §15 "변경 제안"으로만 기록.

---

## 0. 관점 B 핵심 결론 (먼저 읽을 6줄)

1. **가시 시각(visible_at)은 저장값이 아니라 결정적 함수다.** `f(입력 계열들, as_of, calendar_kind, 거래일 그리드, 확정 틱 시각)` — 2계열 이상 지표는 각 계열 가시 시각의 **최댓값**(worst-of-inputs, §5.2). 이 한 함수가 run_replay의 근사-PIT 가시성 규칙과 모바일 실운영의 물리적 사실을 동시에 만족한다. 스테일 판정의 기준 시각을 as_of(달력 자정)가 아니라 이 visible_at으로 두는 것이 `engine_ref` 일치의 급소이며(§5.3), 여기서 갈리면 결측이 있는 모든 틱에서 패리티가 깨진다.
1-b. **엔진에 넘기는 것은 1행이 아니라 시계열이다.** 활성 15지표의 롤링 창(최대 272행 — `vkospi_z` 폴백 체인) 때문에 확정 틱은 계열별로 **N행을 읽어 전체를 변환한 뒤** 가시성으로 1건을 고른다. 순서를 뒤집으면(선택 후 변환) BT-05 L1/L2가 확정 실패한다(§5.4.1).
2. **확정 틱은 (D, lake 상태)의 순수 함수여야 한다.** 계열 종류별 as_of 컷오프(KR/FX = D, US/FRED = D−1)를 명시적으로 적용하면 **라이브 틱과 캐치업 틱이 같은 산출**을 내고, 멱등·재계산·감사·패리티가 한꺼번에 해결된다(§5.4~5.6).
3. **상태기계는 증분 상태가 아니라 전량 재계산(fold)으로 커밋한다.** `statemachine.run`이 틱 리스트에 대한 fold이므로, 틱 입력 이력(append-only)에서 매번 전량 재생하면 D-25 §1~4 의미론이 구조적으로 보장되고 이중 실행·중단 복구가 자동으로 멱등해진다(§5.6).
4. **BT-05 패리티를 골든 2창으로 한정하면 이번에 채택한 기능이 통째로 미검증으로 남는다.** 골든 창의 최대 낙폭은 15.557% < extreme 20.0이라 `or_any_extreme`이 **0회 발화**한다(MT0-08 실측). 따라서 패리티 범위 = 9창 × mobile_daily + 합성 config 증인(F2류·D-26 3종)이어야 한다(§8.2).
5. **프리뷰 coverage는 이월 전(raw)이 정본이다.** 이월값을 유효가중에 계상하면 D-23 §23.3-3의 `<80%` 억제가 죽은 조문이 되고 TASK 완료 기준 ③(67.7%)이 재현 불가가 된다(§10.1.1). 임계 0.80은 SSOT에 **아직 없으므로** 신설 제안이 MT1-07 착수의 선행 조건이다(§15 제안 6).
6. **확정 틱 시각은 17:00 KST를 권고**한다 — 하니스 무감(BT-03) + 물리 하한(15:30 마감) + SSOT 정합(`daily_kr 16:50` 수집이 16:20 확정보다 늦다는 모순 해소) + K-14(WorkManager 비정시)로 명목 시각을 앞당겨도 실익 0. 단 사전등록된 실측(§9.2)이 결정을 확정한다.

---

## 1. 계획 전제 (브리프 §2 확정 사실의 반영 지점)

| # | 확정 사실 | 본 계획의 반영 지점 |
|---|---|---|
| 1 | registry **0.3.1-rc** 현행, assets에 이를 굽는다 | MT1-01b syncConfigs 대상, MT1-05a 파서가 `thresholds.extreme`·`or_any_extreme` 파싱, §8 패리티 기준 |
| 2 | D-26 짝지음 + `or_any_extreme`은 **프로덕션 경로** | MT1-05d 상태기계 포팅 범위, §8.2 패리티 범위, §8.4 합성 config 증인 |
| 3 | 확정 틱 시각 재확인 필수(AD-3b) | **MT1-06a 신설**(결정 메모 + 사전등록 측정 프로토콜), §9 |
| 4 | G-4 CDS 모바일 경로 없음 | MT1-00d 실측 → MT1-04f 판정, §14 미해결 D-B4 |
| 5 | KRX = kotlin_krx, 야후 ^KS11 폴백 **비채택** | MT1-04c, MT1-00a(로그인 정책 실측). 폴백 재제안 없음 |
| 6 | M1은 LLM 미호출 | MT1-02는 계약 **미러+스냅샷까지만**(소비자는 M2). 단 `TriggerBlock`은 MT1-05가 실제 생산(§6.4) |
| 7 | 뉴스 2지표 `enabled:false`, kr_cds `optional:true` | MT1-05a 로더가 **enabled-only**로 분모 구성(§5.7-2), coverage 분모 = 31.0 |
| 8 | D-23 커버리지 규율은 MT1-07 완료 기준 4항 그대로 | MT1-07c·d, §10 |
| 9 | contracts 스냅샷은 **Python 측도 신규** | MT1-02a(생성기+테스트) 신설, §6 |
| 10 | REVIEW_M0 신설 규율 4건 | §16.3 Worker 브리프 공통 규약에 편입 |
| 11 | 모델 배정 D-20 §20.2 | §3 표의 "위임" 열 |
| 12 | CI 기본 = JVM, 계측은 실기기 | §3 완료 명령이 JVM/계측을 분리 표기, §11.2 |

**M0 승계 4건(REVIEW_M0 MT0-08 종결 소견)** 은 전부 본 계획에 실체가 있다: ① 패리티 범위 확대 → §8.2 ② Kotlin F2-2류 증인은 합성 config 필수 → §8.4 ③ 확정 틱 17:00 재확인 → §9 ④ 잔여 FAIL 3건은 C1 소관, **M1에서 재보정 금지** → §13 R-B7.

---

## 2. 데이터 계층 구조 (B 관점에서 본 최소 아키텍처)

```
[collectors]  Yahoo/Stooq · FRED · KRX(kotlin_krx) · ECOS · KIS(opt)
     │  범위 조회 (from,to) — 백필·캐치업 공용
     ▼
[lake]  Room append-only: observation(series_id, field, as_of, observed_at, revision, source, value)
     │  ① 변경분만 append(값 동일이면 미기록) ② UPDATE/DELETE 트리거 차단
     ▼
[series]  readSeriesForTick(D): 계열종류별 as_of 컷오프까지, as_of당 최신 revision,
     │     **오름차순 N행**(N = 그 지표의 합성 룩백 + 여유) — 1행이 아니라 시계열이다
     ▼
[engine]  ① 원계열 → causal transform 전체 계산 → 출력 시계열
     │     ② 출력 행마다 combinedVisibleAt(...) 색인 → ③ visible_at ≤ evaluated_at 중 최신 1건 선택
     │     ④ 스테일 판정 → severity(+modifier) → composite/coverage/distinct_axes/any_crit/any_extreme
     │  = Tick 레코드(append-only, 확정 시 동결)
     ▼
[statemachine]  tick_input 전량 fold(D-25 §1~4, D-26) → phase timeline → 마지막 원소 커밋
     ▼
[commit]  phase_commit(tick_date UNIQUE) + tick_run(실행 이력) → 노티
```

**계층 규율 3조**
- L1(collectors)은 원값만 안다. 임계·가중·프로파일을 모른다.
- L2(engine)는 시각을 모른다 — `evaluated_at`과 계열별 `visible_at`을 주입받는다(테스트 가능성·패리티의 전제).
- carry-forward는 L2 밖 **프리뷰 use-case 전용**이며 확정 경로에서 컴파일·구조적으로 도달 불가(§10.2).

---

## 3. 서브태스크 분해

표기: **P** = 병렬 가능(같은 레인 내 동시 위임 가능), **S** = 선행 완료 필요.
완료 명령의 Gradle 호출은 git-bash 기준 `./gradlew …`, PowerShell에서는 `.\gradlew.bat …`(§11.3).

### 3.1 MT1-00 실측 선행 묶음 (신설 — TASK의 "data-verifier 선행" 조항들을 착수 가능한 단위로 물질화)

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준(실행 가능) |
|---|---|---|---|---|---|
| **MT1-00a** | KRX 실측: ① `KrxClient.login()` 2026 정책 하 성공률(3일 × 2회) ② 필요 데이터셋 4종 가용성(index OHLCV `1001`/`2001`, investor trading `MDCSTAT02203` 외국인 순매수, derivative `MDCSTAT01201` VKOSPI, business days) ③ **확정 시각 프로파일링**(15:35/16:00/16:20/16:50/17:00/17:30/18:00 각 시점의 당일 값 유무·이후 변경 여부) ④ 동시 로그인(CD011) 거동 | — | P | data-verifier | `docs/journal/2026-08-XX_MT1-00a_krx_probe.md`에 원 응답 발췌 + 시각별 표. 재현 스크립트 `scripts/probe_krx.kt(or .md 절차)` 존재 |
| **MT1-00b** | 야후계 실측: chart v8 엔드포인트 7심볼(^VIX·^VIX3M·^MOVE·^GSPC·DX-Y.NYB·KRW=X) 응답 스키마·거래일→as_of 규약·차단 여부, Stooq 폴백 CSV 스키마·심볼 매핑 | — | P | data-verifier | 저널 + **픽스처 대조 표**: 동일 기간 파싱 결과 (as_of, value)가 `backtest/fixtures/*.parquet`와 일치(불일치 건은 전건 사유 기록) |
| **MT1-00c** | **K-04 ECOS item_code 실측**(현행 `indicators.yaml`에 `VERIFY` 잔존): `721Y001` 하위 item_code 메타 조회로 국고3y·회사채AA-3y 확정 | — | P | data-verifier | 저널 + §15 SSOT 변경 제안 1건(코드값). `curl`/스크립트 재현 절차 포함 |
| **MT1-00d** | **G-4 CDS 접근성 실측**: worldgovernmentbonds KR CDS 5Y의 모바일(OkHttp/UA) 접근 가능성·응답 안정성·구조 변경 내성 | — | P | data-verifier | 저널 + (a)수집 구현/(b)미수집 확정 상신문 |
| **MT1-00e** | KIS 가용성 확인: 앱키·계좌 보유 여부(사용자 확인) + OAuth 토큰 발급 1회 실측 | — | P | data-verifier | 저널. 미보유 시 **M2 이연 결정문**(§14 D-B5) |

> 실측 4건은 **첫 메시지에서 동시 위임**한다(네트워크 대기가 지배적, 상호 의존 없음). MT1-00a는 MT1-04c·MT1-06a를, MT1-00b는 MT1-04a·MT1-04h를, MT1-00c는 MT1-04d를 블록한다(§12).

### 3.2 MT1-01 스캐폴드 + SSOT 동기화

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-01a | Gradle 스캐폴드(버전 카탈로그, ktlint+detekt, minSdk 29, JVM/계측 소스셋 분리, `check` 구성) | — | P | kotlin-implementer | `./gradlew check` green (JVM만 포함, connected 미포함 확인) |
| MT1-01b | `syncConfigs` task: `configs/*.yaml`(5) + `prompts/*.md`(2) → `app/src/main/assets/{configs,prompts}/`, 동시에 `MANIFEST.sha256`(바이트 다이제스트) 생성 | 01a | S | kotlin-implementer | `./gradlew syncConfigs && ls app/src/main/assets/configs \| wc -l` = 5, prompts = 2 |
| MT1-01c | **드리프트 차단 배선**: `preBuild`(및 `processDebugResources`)가 `syncConfigs`에 의존 — 수동 복사·구버전 패키징 불가 | 01b | S | kotlin-implementer | 배선 테스트: SSOT 1바이트 수정 후 `./gradlew :app:assembleDebug` → assets 자동 갱신 확인(테스트로 고정) |
| MT1-01d | 해시 검증 **JVM 테스트**(CI 게이트): 저장소 SSOT 파일 바이트 SHA-256 vs assets 사본 vs MANIFEST 3자 일치 | 01b | S | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*SsotHash*"` green |
| MT1-01e | 해시 검증 **계측 테스트**(패키징 검증): `AssetManager`로 읽은 실제 APK 자산 다이제스트 vs 빌드시 생성된 androidTest 리소스 기대값 | 01d | S | kotlin-implementer | `./gradlew :app:connectedDebugAndroidTest --tests "*SsotHashInstrumented*"` green (실기기) |
| **MT1-01f** | **커버리지 게이트(신설, AAA §2.3 강제)**: Kover 플러그인 + 모듈별 최소 경계 + 제외 규칙 + `check` 배선 | 01a | S | kotlin-implementer | `./gradlew koverVerify` green (§3.2.1 임계표) |
| **MT1-01g** | **krxkt 통합(신설)**: §17 A-2 권고대로 `mobile/third_party/krxkt/` 벤더링(+`VENDOR.md` 상류 커밋 핀) 또는 A 관점이 채택한 대체 방식 | 01a | S | kotlin-implementer | **깨끗한 클론에서** `./gradlew :third_party:krxkt:compileKotlin` green + `VENDOR.md` 존재. MT1-04c의 선행 |

**설계 근거(§7 상세)**: K-16의 실제 위험은 "복사 누락"만이 아니라 **패키징 단계 변형**(압축·필터·인코딩)이므로 JVM(파일 대 파일)과 계측(APK 자산 대 기대값)을 분리한다. 기대 다이제스트는 **어디에도 리터럴로 적지 않는다**(빌드 시 SSOT에서 생성 — CLAUDE.md §1).

#### 3.2.1 커버리지 게이트 규격 (MT1-01f — AAA §2.3 하드 기준의 측정·강제)

도구는 **Kover**(`org.jetbrains.kotlinx.kover`)를 채택한다. 근거: Kotlin 공식 도구라 인라인 함수·기본 인자 같은 Kotlin 산출물을 JaCoCo보다 정확히 계상하고, **검증 규칙(`verify { rule { minBound(n) } }`)이 플러그인에 내장**돼 있어 별도 스크립트가 필요 없다(새 의존성 1개로 측정+강제 동시 해결).

| 모듈 | 최소 라인 커버리지 | 근거 |
|---|---|---|
| `:engine` (transforms·scoring·modifiers·statemachine·registry 파서) | **90%** | AAA §2.3 "코어 engine·statemachine" |
| `:contracts` | **90%** | AAA §2.3 "contracts" |
| `:lake` | **90%** | AAA §2.3 "lake" |
| `:collectors` | 70% | "나머지" |
| `:app` (틱·프리뷰·노티·화면) | 70% | "나머지" |

**제외 규칙**(생성 코드·서드파티만 — **자체 로직 제외 금지**):
`*_Impl`·`*Dao_Impl`(Room 생성), `*$$serializer`·`*$Companion`(kotlinx.serialization 생성), `BuildConfig`, `mobile/third_party/**`(벤더 코드 §17 A-2), `@Preview` 함수(`*ComposePreview*` — 개발 편의용 미실행 코드).

> **UI 패키지 제외 없음(라운드 2 정정).** 이전 판은 `:app`의 Compose 화면 패키지를 M1 한정 제외로 두었으나 **철회한다.** 근거: ① AAA §2.3에는 UI 예외 조항이 없다(코어 ≥90% / **나머지 ≥70%**가 전부다) ② AAA §2.2가 요구하는 실패 경로 UX(네트워크 단절·부분 결측·키 미설정·쿼터·중단 캐치업)가 **바로 그 패키지에 산다** — 제외하면 루브릭의 핵심 항목이 측정 밖으로 나간다 ③ 계획이 스스로 루브릭을 완화하는 것은 권한 밖이다. 유예가 필요하다는 판단이 서면 그것은 계획의 선언이 아니라 **사용자 결정 사항으로 상신**한다.
>
> 귀결(공짜가 아님을 명시): MT1-08b는 기능판 홈에 대해 **Robolectric 기반 화면 테스트**(JVM에서 실행 — 기기 불요)를 함께 낸다. 대상은 렌더링 미학이 아니라 **상태 매핑**이다: 국면·composite·coverage·스테일 배지·마지막 틱 시각·`PREVIEW` 배지·억제 상태(흐림 + "국면 판정 불가")·오류·빈 상태. 디자인 완성은 M2지만 **상태 분기 로직은 M1 산출물이므로 M1에서 측정 대상**이다.

**배선**: 각 모듈에서 `tasks.named("check") { dependsOn("koverVerify") }`. 즉 `./gradlew check`가 린트·테스트·**커버리지 임계**를 한 번에 판정한다(별도 기억 불필요).

**Python 측**(M1 신설 파일만 대상 — 기존 러너 커버리지 45% 이월분은 M1 범위 밖):
```bash
uv run pytest tests/test_contracts_snapshot.py backtest/test_export_parity.py \
  --cov=backtest.export_parity --cov=scripts.gen_contract_snapshots \
  --cov-report=term-missing --cov-fail-under=90
```
`pytest-cov>=7.1.0`은 이미 dev 의존성에 있다(신규 의존성 0).

### 3.3 MT1-02 계약 미러 + 스키마 스냅샷

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-02a | **Python 측 신규**: 스냅샷 생성기 `scripts/gen_contract_snapshots.py` + 정본 인스턴스 `contracts/snapshots/*.json` + `tests/test_contracts_snapshot.py`(왕복·형상 다이제스트·부정 케이스) | — | P | python-implementer | `uv run pytest tests/test_contracts_snapshot.py -q` green, `uv run python scripts/gen_contract_snapshots.py --check` exit 0 |
| MT1-02b | Kotlin 미러 데이터클래스(kotlinx.serialization) + 제약 검증(`init { require(...) }`) | 02a | S | kotlin-implementer | `./gradlew :contracts:test` green |
| MT1-02c | Kotlin 왕복·동결 테스트(같은 스냅샷 파일 참조, 정규화 규약 §6.3) | 02b | S | kotlin-implementer | `./gradlew :contracts:test --tests "*SnapshotRoundTrip*"` green |
| MT1-02d | 형상 다이제스트 교차 검증: 양측이 각자 모델 메타데이터에서 산출한 정규화 형상의 SHA-256 일치 | 02c | S | kotlin-implementer | 양측 테스트 green + 다이제스트 값이 두 로그에서 동일 |

### 3.4 MT1-03 Room append-only lake

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-03a | 스키마·엔티티(**`origin` 포함** §5.1)·DAO(**@Insert만**, @Update/@Delete 부재), 인덱스(`series_id, field, as_of, origin, revision DESC, observed_at DESC, id DESC` 포함) | 01a | S | kotlin-implementer | `./gradlew :lake:test` green |
| MT1-03b | **물리 강제**: `BEFORE UPDATE`/`BEFORE DELETE` 트리거 `RAISE(ABORT)` — RoomDatabase.Callback + 마이그레이션 | 03a | S | kotlin-implementer | 원시 SQL `execSQL("UPDATE observation …")`이 `SQLiteConstraintException`을 던지는 테스트 green |
| MT1-03c | as-of 조회: **`readSeriesForTick(seriesId, field, tickDay, includePreview)`(§5.4.1 (2) — 컷오프까지 N행 시계열, as_of당 최신 revision, 오름차순, 윈도 함수 비의존)** + `readAsAt(asOfCutoff, observedCutoff)`(감사·look-ahead 증명용) + 결정적 tie-break | 03a | S | kotlin-implementer | N행 반환·정렬·as_of당 1행·동률 tie-break·컷오프 경계(3 kind)·look-ahead 차단 + **프리뷰 배제 3케이스(§10.3-2)** 테스트 green |
| MT1-03d | 변경분만 append(동일값 미기록) + revision 증가 규칙 | 03a | S | kotlin-implementer | 재수집 멱등 테스트(동일 응답 2회 → 행 수 불변) green |
| MT1-03e | CSV 내보내기(일 1회) + Drive 백업 훅(인터페이스, 기본 off) + **키·비밀 제외 백업 규칙** | 03a,03c | S | kotlin-implementer | CSV 왕복 테스트 + `data_extraction_rules.xml`/`backup_rules.xml`에 암호화 prefs 제외가 있음을 파싱해 단언하는 테스트 green |
| MT1-03f | `tick_input`(틱 입력 동결)·`phase_commit`(tick_date UNIQUE)·`tick_run`(실행 이력) 테이블 | 03a | S | kotlin-implementer | 스키마 테스트 + 중복 삽입이 ABORT 되는 테스트 green |

### 3.5 MT1-04 collectors (a~f + 신설 g·h)

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-04a | 야후계 REST 6심볼 + **Stooq 폴백**, 지표별 결측 처리 | 00b, 03a | P | kotlin-implementer | 픽스처(저장된 JSON/CSV) 파싱·오류·폴백 경로 테스트 green |
| MT1-04b | FRED 2계열(`.` = 결측 매핑, T+1) | 03a | P | kotlin-implementer | 픽스처 테스트 green(`.` → missing 단언 포함) |
| MT1-04c | KRX via kotlin_krx(지수 2, 투자자 순매수, VKOSPI, 거래일) + rate limit 1req/s + 로그인 세션 관리 | 00a, 01g, 03a | P | kotlin-implementer | 픽스처 테스트 + 레이트리밋 테스트 green |
| MT1-04d | ECOS 2 item(실측 코드 사용) | 00c, 03a | P | kotlin-implementer | 픽스처 테스트 green |
| MT1-04e | KIS(옵션, 기본 비활성) 또는 이연 결정문 | 00e, 03a | P | kotlin-implementer | 플래그 off 시 경로 미진입 테스트 green (또는 §14 D-B5 결정문) |
| MT1-04f | **G-4 판정**: (a)수집 구현 / (b)미수집 확정 + UI "미수집" 배지 + GATE_GM1 기록 | 00d | S | Advisor 상신 → kotlin-implementer | 결정문 + 선택 경로의 테스트 green |
| **MT1-04g** | **초기 백필(신설)**: 워밍업 550 달력일 범위 조회 → lake 적재, 진행률·재개(중단 지점부터), 쿼터·레이트리밋 준수 | 04a~d | S | kotlin-implementer | 픽스처 기반 백필 테스트: **지표별 필요 행 수(§5.4.1 (1), 최대 272행)** 를 15지표 전부 충족, 부족 시 워밍업 상태 노출, 중단 후 재개 멱등 green |
| **MT1-04h** | **PIT 대조 하니스(신설)**: 각 수집기의 파싱 결과(as_of, field, value)를 `backtest/fixtures` 내보내기(§8.3의 `*.input.csv`)와 대조 | 04a~d, 05e | S | kotlin-implementer | `./gradlew :collectors:test --tests "*FixtureCrossCheck*"` green (중첩 구간 전 행 일치, 허용오차 1e-6 상대) |

> **MT1-04g 신설 근거**: `zscore(window=252)`·`rolling_mean_corr(window=120)`·`rolling_sum(5)+zscore(252)` 때문에 첫 실행 시 최소 252거래일(≈365일) + 룩백 여유가 필요하다. `backtest/windows.yaml`의 `padding_days: 550`이 같은 요구의 기존 정답이므로 그 값을 그대로 채택한다. 이 항목 없이는 MT1-05·06이 실기기에서 **전 지표 결측(D-25 §3 동결)** 으로만 동작한다 — 스모크가 성립하지 않는다.

### 3.6 MT1-05 Kotlin 엔진·상태기계 + BT-05

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-05a | **config 로더 + transform/modifier 문자열 파서**(snakeyaml) — `engine_ref/registry.py` 대응. 숫자 리터럴 0 | 01b | S | kotlin-implementer | 파서 테스트 green(§8.5 대응표의 registry 절 전건) + `grep`으로 엔진 소스 내 임계 리터럴 0 확인 |
| MT1-05b | transforms 12종 이식(float64=Double, ddof=1, min_periods) + **§5.4.1 (3) 계산 순서 파이프라인**(원계열 → 변환 → 가시성 색인 → lookup) | 05a, 03c | S | kotlin-implementer | 단위 테스트 green + §8.3 transform 골든 대조(L1) green + **순서 증인**(변환을 선택 뒤로 옮기면 실패) green |
| MT1-05c | scoring·modifiers 이식(경계 등호, 결측, abs, combine_max, is_extreme) | 05a | S | kotlin-implementer | 단위 테스트 green |
| MT1-05d | statemachine 이식(**D-25 §1~4 + D-26 레벨-로컬·reset·RED 예외**) | 05a | S | kotlin-implementer | 단위 테스트 + 증인 10종(F2 7 + D-26 3) green |
| MT1-05e | **패리티 산출물 내보내기(Python)** `backtest/export_parity.py` + 자체 검증 테스트 | — | P | python-implementer | `uv run python backtest/export_parity.py --check` exit 0, `uv run pytest backtest/test_export_parity.py -q` green |
| MT1-05f | **BT-05 패리티 러너·판정**(JVM 테스트) | 05b~e | S | kotlin-implementer → backtest-analyst 분석 | `./gradlew :engine:test --tests "*Bt05Parity*"` green + 패리티 리포트 산출 |
| MT1-05g | `golden_mobile.yaml` 직접 대조(동결 기대 타임라인) | 05f | S | kotlin-implementer | `./gradlew :engine:test --tests "*GoldenMobile*"` green |
| MT1-05h | 가시성 함수 패리티(`visible_at` 테이블 대조) — **단일 계열 + 2계열 결합(worst-of-inputs) 전건** | 05e | S | kotlin-implementer | `./gradlew :engine:test --tests "*VisibilityParity*"` green. **필수 포함**: `global_corr_break`(us_market+krx 혼합)·`vix_term_structure`(2계열)·`vkospi_z` 폴백 분기 |

### 3.7 MT1-06 일일 확정 틱 + 캐치업

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| **MT1-06a** | **확정 틱 시각 결정 메모(신설, AD-3b 이행)**: §9.2 사전등록 규칙 + MT1-00a 실측 → 시각 확정 + 스케줄 정합 논증 | 00a | S | **backtest-analyst**(작성) → qa-verifier → aaa-critic → 사용자 승인(채택) | `docs/journal/…_MT1-06a_confirm_time.md` + §9.2 규칙 적용표 + §9.5 스테일 영향 계산. 2단 판정 PASS 후 상신 |
| MT1-06b | 거래일 달력·틱 그리드(kotlin_krx business days + 캐시 + 관측 폴백). 휴장일은 **틱 미생성**(동결 틱 아님) | 04c, 06a | S | kotlin-implementer | 휴장일 무틱 테스트 + 캘린더 폴백 테스트 green |
| MT1-06c | 확정 틱 파이프라인: 수집 → append → **`readSeriesForTick` × 지표(§5.4.1)** → 변환·가시성·스테일 → 엔진 → `tick_input` 동결 → **전량 fold** → `phase_commit` | 03f, 04g, 05b, 05d, 06b | S | kotlin-implementer | Robolectric 정상 시나리오 green + **워밍업 미충족 시나리오**(동결·표기) green + 프리뷰 값 우선순위(§10.3-2) green |
| MT1-06d | 멱등·이중 실행 방지: `tick_date` UNIQUE + WorkManager 고유 작업 + 진행 중 표식 | 06c | S | kotlin-implementer | 동일 일자 2회 실행 → 상태·행수·노티 불변 테스트 green |
| MT1-06e | 캐치업: 누락 거래일 순차 커밋, `is_catchup` 표기, **소급 상한 20거래일 + 초과 시 gap 처리**(§5.6.1), 노티 정책(§5.6) | 06c, 06b | S | kotlin-implementer | 중단 후 N일 캐치업 시나리오 테스트 green(순서·1일1커밋·타임라인 일치) + **상한 초과 시나리오**(25거래일 공백 → 최근 20틱만 `tick_input` 생성·`kind='gap'` 1행·**절단 전 틱은 fold 입력에 그대로 남고 카운터 조작 없음**) green + 등가성 테스트(같은 틱 열을 "공백 없이" 준 경우와 타임라인 동일 — §5.6.2(3)) green |
| MT1-06f | 실행 이력(`tick_run`) + 누락 노출(K-15) | 06c | S | kotlin-implementer | 실패·부분 결측 이력 기록 테스트 green |
| MT1-06g | **확정 틱 결정론 테스트**: 같은 lake 상태 → 같은 산출(라이브 vs 캐치업 동일성) | 06c, 06e | S | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*ConfirmTickDeterminism*"` green |

### 3.8 MT1-07 프리뷰(D-17·D-23)

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-07a | 프리뷰 파이프라인(병렬 수집 + KIS 옵션 → 잠정 계산 → PREVIEW 배지·as_of). **시각 규약 §5.4.2**(tickDay=today / evaluatedAt=now / origin='preview' 포함 조회) | 04a~e, 05c | S | kotlin-implementer | 상태 불변 테스트(①) + **§5.4.2 완료 기준 3건**(오전 프리뷰 US·FRED 가시 / 밤 23시 전일 KR stale / 확정 산출 불변) green |
| MT1-07b | carry-forward **경로 격리**(모듈 경계 + 아키텍처 테스트) + **원천 = 직전 확정 `tick_input` 동결본**(§10.1.2, §5.4.3 ③) | 05c, 06c, 03f | S | kotlin-implementer | 확정 경로에서 호출 불가 테스트(②) + **§10.1.2 완료 기준 3건**(증인 `W-③a~c` / 이월 as_of 일치 / 프리뷰 2회 연속 자기참조 부재) green |
| MT1-07c | **raw coverage**(이월 전 유효가중/전체가중) 계산 + 억제(흐림·판정 불가·경보 억제). 임계는 **assets에서 로드**(§15 제안 6의 `engine.preview_coverage_min`) — 코드 리터럴 0.80 금지 | 07a, §15-6 | S | kotlin-implementer | KR 4지표 결측 시 raw coverage = 21.0/31.0 산출·억제 테스트(③) green + **이월 적용 후에도 억제가 유지되는** 회귀 테스트 green + 임계 하드코딩 부재 grep |
| MT1-07d | D-23 §23.2 수치 예 재현(66.7 vs 45.2) | 05c | S | kotlin-implementer | 재현 테스트(④) green |

### 3.9 MT1-08 노티·기능판 홈

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-08a | 노티 3채널(국면 전이·잠정 경보·틱 실패) + 억제 규칙 연결 | 06c, 07c | S | kotlin-implementer | 트리거 테스트 green(억제 상태에서 잠정 경보 미발신 포함) |
| MT1-08b | 기능판 홈(국면·composite·**raw coverage**·상위 지표·마지막 틱 시각·스테일 배지·PREVIEW 배지·억제/오류/빈 상태) | 06c, 07c | S | ui-craftsman | `./gradlew :app:testDebugUnitTest --tests "*HomeScreen*"` green — **Robolectric 상태 매핑 테스트 전 분기**(정상·부분 결측·억제·공백(gap)·오류·데이터 없음) + `:app` koverVerify 70% 충족(§3.2.1 — UI 제외 없음) |
| MT1-08c | 수동 E2E 체크리스트 문서 + 실기기 1일 스모크 절차 | 08a,08b | S | Advisor/kotlin-implementer | `docs/runbooks/M1_SMOKE.md` 존재 + 스모크 1회 수행 기록 |

---

## 4. 의존성 그래프와 병렬 레인

```
t0 ──┬─ MT1-00a KRX실측 ─┐
     ├─ MT1-00b 야후실측 ─┤
     ├─ MT1-00c ECOS실측 ─┤   (실측 4~5건 완전 병렬 — 첫 메시지 동시 위임)
     ├─ MT1-00d CDS실측  ─┤
     └─ MT1-00e KIS확인  ─┘
                          │
레인 A(빌드) MT1-01a ─┬─▶ 01b ─▶ 01c ─▶ 01d ─▶ 01e(계측)
                      └─▶ 01f(커버리지 게이트)
                          │
레인 B(계약) MT1-02a(py) ─▶ 02b ─▶ 02c ─▶ 02d          ‖ 레인 A와 병렬
                          │
레인 C(원장) MT1-03a ─▶ 03b/03c/03d/03f ─▶ 03e          ‖ 레인 A·B와 병렬(01a 이후)
                          │
레인 D(엔진) MT1-05a ─▶ 05b/05c/05d ──┐                  ‖ 레인 C와 병렬(01b 이후)
레인 E(하니스) MT1-05e(py) ────────────┼─▶ 05f ─▶ 05g    ‖ 레인 D와 병렬(t0부터 가능)
                                      └─▶ 05h
레인 F(수집) MT1-04a/b/c/d/e ─▶ 04g ─▶ 04h(05e 필요)     ‖ 레인 D와 병렬(03a 이후)
                          │
        MT1-06a(결정) ─▶ 06b ─▶ 06c ─▶ 06d/06e/06f ─▶ 06g
                                   └─▶ MT1-07a ─▶ 07b/07c   (07d는 05c 직후 병렬)
                                              └─▶ MT1-08a/08b ─▶ 08c ─▶ 실기기 스모크
```

**임계 경로**: `MT1-00a → 06a → 06b → 06c → 06e → 08c(스모크)`. 실측(00a)이 늦어지면 임계 경로 전체가 밀리므로 **t0에 최우선 위임**한다.
**두 번째 임계 경로**: `01b → 05a → 05d → 05f(BT-05)` — GM1의 하드 게이트라 레인 D·E를 t0 직후 동시에 연다(05e는 Python 단독이라 t0에 착수 가능).

**동시 위임 묶음 권고**
- 1차(t0): 00a, 00b, 00c, 00d, 00e, 01a, 02a, 05e → 8건 병렬(실측 5 + 빌드 1 + Python 2).
- 2차: 01b·01c·01d / 02b / 03a / 05a → 4건.
- 3차: 03b~03f / 04a~04e / 05b·05c·05d / 02c·02d → 최대 5~6건(동시성 상한은 §16.4).

---

## 5. 심화 A — PIT·as-of·가시성·스테일·멱등·캐치업 의미론

이 절이 관점 B의 본체다. **`engine_ref`/`run_replay`와 비트 단위로 같은가**를 판정 가능한 명제로 분해한다.

### 5.1 as_of·observed_at·revision의 정의(모바일)

| 필드 | 정의 | 저장 형식 | 비고 |
|---|---|---|---|
| `as_of` | 데이터의 기준 시점 = 해당 거래일의 **UTC 자정**(일봉) | epoch millis(UTC) | `backtest/fixture_schema.to_utc_midnight` 규약과 동일 — 픽스처 대조(MT1-04h)의 전제 |
| `observed_at` | 앱이 그 값을 실제로 받은 시각 | epoch millis(UTC) | 감사·revision 순서용. **스테일 판정에는 쓰지 않는다**(§5.3) |
| `revision` | 동일 `(series_id, field, as_of)`의 값 변경 횟수 | INTEGER | 값이 같으면 새 행을 만들지 않는다(MT1-03d, **동일 `origin` 내 판정** — §10.3-2) — 재수집 멱등의 물리적 근거 |
| `origin` | 이 행을 만든 경로: `confirm` \| `preview` \| `backfill` | TEXT NOT NULL | **확정 틱은 `preview` 행을 SQL에서 배제**(§10.3-2). `backfill`은 종가 소스라 확정 경로가 읽는다 |

거래일(as_of의 날짜)은 **계열의 거래소 시간대**로 정한다: US 지수 = America/New_York, KRX = Asia/Seoul, FRED = observation date 그대로, KRW=X = 야후가 부여한 일봉 날짜(§13 R-B3). naive datetime 금지(K-05) — 전 계층 UTC aware, 표시만 KST.

### 5.2 가시 시각(visible_at) — 단 하나의 결정적 함수

`run_replay.raw_visibility_grid_day`/`visibility_tick_utc`를 **의미 그대로** 모바일에 옮긴다. 단, 앱은 이를 "리플레이 규칙"으로 구현하는 것이 아니라 **as_of 컷오프와 스테일 기준 시각을 산출하는 파생 함수**로 구현한다.

```
visibleAt(seriesId, asOfDate, grid, confirmTime):        // 단일 계열
  kind = calendarKind(seriesId)                 // krx | fx | us_market | fred
  visDay = when(kind):
      us_market -> grid.firstAfter(asOfDate)                    // T 다음 거래일
      fred      -> grid.firstOnOrAfter(asOfDate + lagDays)      // indicators.yaml source.lag_days
      krx, fx   -> grid.firstOnOrAfter(asOfDate)                // T 당일
  return kstToUtc(visDay, confirmTime)                          // 없으면 null

// 2계열 이상을 입력으로 쓰는 지표 — worst-of-inputs (정본: run_replay.combined_visibility_utc)
combinedVisibleAt(inputSeriesIds, asOfDate, grid, confirmTime):
  ts = inputSeriesIds.map { visibleAt(it, asOfDate, grid, confirmTime) }
  if (ts.any { it == null }) return null        // 하나라도 못 보면 그 날짜 값을 모른다
  return ts.max()                               // 둘 다 알려져야 결합값을 안다

// 가시 판정은 "시각"이 아니라 "날짜"로 한다 (§5.4.2 — 프리뷰까지 한 규칙으로 덮기 위해)
isVisibleAt(inputSeriesIds, asOfDate, grid, tickDay):
  d = combinedVisibleAt(inputSeriesIds, asOfDate, grid, confirmTime).toKstDate()
  return d != null && d <= tickDay              // 확정: tickDay = D / 프리뷰: tickDay = today
```

**가시 판정(날짜) ↔ 스테일 판정(시각)의 분리.** 확정 틱에서는 두 표현이 **동치**다: `visibleAt ≤ D 17:00 ⟺ visDay ≤ D`(visibleAt이 곧 `visDay 17:00`이므로). 따라서 날짜 비교로 바꿔도 **확정 틱 산출과 BT-05 기대값은 비트 동일**하다. 이 분리는 오직 프리뷰(§5.4.2)를 같은 코드로 덮기 위해 필요하며, `run_replay`의 두 개념 구분("가시성 = 언제 처음 아는가라는 물리적 사실 / 스테일 = 여전히 믿을 만한가라는 정책 판단", 모듈 docstring §3)을 그대로 따른다.

**결합 규칙을 빠뜨리면 L0 "완전 일치" 게이트가 확정 실패한다**(§8.4). 정본은 `backtest/run_replay.py`의 `combined_visibility_utc`이며, `build_known_series`가 **모든 지표에 대해** 이 경로를 탄다(단일 계열 지표는 원소 1개인 특수 케이스일 뿐이다). 즉 Kotlin도 `combinedVisibleAt` **하나만** 구현하면 된다 — 분기 없음.

**지표 → 가시성 입력 계열 매핑**(정본: `run_replay._BUILDERS`의 `input_ids` 인자. 임의 판단 금지):

| 지표 | 가시성 입력 계열 | kind | 비고 |
|---|---|---|---|
| vix_level_z | `^VIX` | us_market | |
| **vix_term_structure** | **`^VIX`, `^VIX3M`** | us_market ×2 | 같은 kind라도 **한쪽 계열에 그 날짜 행이 없으면 결합 불가**(null) — max 규칙 필요 |
| move_index_z | `^MOVE` | us_market | |
| hy_oas_delta | `BAMLH0A0HYM2` | fred | `lag_days: 1` |
| ust_2s10s_move | `T10Y2Y` | fred | `lag_days: 1` |
| dxy_z | `DX-Y.NYB` | us_market | |
| spx_drawdown_momentum | `^GSPC` (두 성분 각각) | us_market | 결합 가시성 아님 — **성분별로 독립 조회·독립 스테일 판정** 후 `combine_max`(§8.5) |
| **global_corr_break** | **`^GSPC`, `KRX:1001`** | **us_market + krx (혼합)** | **최대 위험 지점**: T의 값은 `max(T 다음 거래일, T 당일)` = **T 다음 거래일** 틱에서 최초 가시. KR 계열만 보고 "당일 가시"로 구현하면 하루 앞서 보게 되어 look-ahead |
| vkospi_z | `KRX:VKOSPI` 존재 시 그것, 없으면 **`KRX:1001`**(K-02 폴백) | krx | 폴백 여부는 **데이터로 판정**(창의 VKOSPI 관측 0건이면 폴백) — 하드코딩 금지 |
| kospi_drawdown | `KRX:1001` | krx | |
| foreign_net_sell_kospi | `KRX:investor_foreign_kospi` | krx | |
| kospi_volume_distribution | `KRX:1001` | krx | close·trading_value 두 **필드**이나 계열은 하나 |
| usdkrw_z | `KRW=X` | fx | high/low/prev_close는 아래 보조 조회 규칙 |
| krx_credit_spread_delta, kr_cds_5y_delta | (M0 픽스처 미수집 → 상시 결측) | — | 프로덕션에서는 각각 ECOS·(G-4 결정) 계열 |

**보조 입력의 조회 키 규칙**(패리티 함정): modifier 입력(`hy_oas_delta`의 레벨 계열, `usdkrw_z`의 high/low/prev_close)은 `evaluated_at`이 아니라 **가시화된 주 계열 값의 `row_date`(= as_of)** 로 조회한다. `prev_close`는 달력 전일이 아니라 **직전 관측 행**(positional shift)이다. 결측이면 modifier를 적용하지 않고 기저 severity를 그대로 쓴다(§8.5의 modifier 행과 동일 규약).

**저장하지 않고 읽을 때 계산한다.** 이유: (a) 파생값을 저장하면 거래일 그리드가 나중에 갱신될 때 드리프트가 생긴다 (b) 틱당 지표 1행씩만 필요해 비용이 무의미하다 (c) 정의가 코드 한 곳에만 존재해 패리티 증명이 1함수로 끝난다.

**실운영 정합성 논증(중요)**: 이 함수는 인위적 규칙이 아니라 실제 물리를 재현한다.
- 월요일 17:00 KST 틱에서 가장 최신 미국 종가는 **금요일**이다(미 월요일 종가는 화요일 05:00 KST). → `us_market: firstAfter(T)`와 동일.
- FRED는 T+1이므로 D 틱에서 보이는 최신 관측은 as_of ≤ D−1. → `fred: firstOnOrAfter(T+lag)`와 동일.
- KRX·FX는 당일 17:00에 당일 값이 보인다. → `krx/fx: firstOnOrAfter(T)`와 동일.

즉 **하니스 규칙을 앱에 이식하는 것이 아니라, 앱의 물리적 사실이 하니스 규칙과 일치함을 이용**한다. 이 동치성은 MT1-05h가 실행 가능한 테이블 대조로 증명한다.

#### 5.2.1 실운영 경로에서의 visible_at (수집 시각 → 판정까지, 명시 재확인)

패리티 테스트 전용 규칙이 아니라 **앱이 매일 도는 경로 그 자체**다. 구현 위치와 책임을 못 박는다.

| 단계 | 무엇을 하나 | 담당 서브태스크 |
|---|---|---|
| ① 수집 | 응답의 거래일을 계열 거래소 tz로 해석해 `as_of`(UTC 자정) 산출, `observed_at` = 실제 수신 시각(UTC) 기록. **`visible_at`은 여기서 쓰지도 저장하지도 않는다** | MT1-04a~d |
| ② 저장 | `observation`에 append(값 변경 시에만, revision 증가). 스키마에 `visible_at` 컬럼 **없음** | MT1-03a·03d |
| ③ 읽기 | `readSeriesForTick(seriesId, field, tickDay, includePreview=false)`가 **as_of 컷오프까지의 시계열 N행**을 오름차순으로 반환한다(1행이 아니다 — §5.4.1). 확정 경로는 `origin='preview'` 행을 SQL에서 배제(§10.3-2). `grid`·`confirmTime`은 호출자가 주입 | MT1-03c(쿼리) + MT1-06b(주입) |
| ④ 계산 | 원계열 전체에 causal transform → 출력 행마다 `combinedVisibleAt(...)` 색인 → **가시일 ≤ tickDay**(§5.2 `isVisibleAt`) 중 as_of 최신 1건 선택 | MT1-05b(변환)·MT1-05a(색인) |
| ⑤ 판정 | `isStale(cadence, visibleAt, evaluatedAt, profile)` — §5.3 | MT1-05a·05c |
| ⑥ 동결 | 확정 시 `tick_input`에 계산에 쓴 `(as_of, visible_at, value, severity)`를 **함께 기록** — 사후 감사·재현의 근거 | MT1-03f·06c |

- **저장하지 않는 이유**: 거래일 그리드가 갱신(휴장일 정정)되면 저장된 파생값은 즉시 거짓이 된다. 함수는 항상 현재 그리드로 옳은 값을 낸다.
- **`observed_at`을 판정에 쓰지 않는 이유**: 같은 값을 재수집해도 나이가 리셋되지 않아야 하고(→ 영원히 fresh인 죽은 계열 방지), 캐치업·백필로 사후에 들어온 행이 "방금 알게 된 값"으로 둔갑하지 않아야 한다. `observed_at`은 감사·revision 순서 전용이다.
- **프리뷰 무영향**: 프리뷰가 append해도 `visible_at`은 as_of의 함수이므로 변하지 않는다(§10.3-1).
- **검증**: MT1-05h가 `visibility.json`(Python 산출) 전 행을 밀리초 단위로 대조하고, MT1-06g가 라이브·캐치업 두 경로에서 같은 `visible_at`·같은 타임라인이 나오는지 확인한다.

### 5.3 스테일 판정 — engine_ref 일치의 급소

```
isStale(cadence, visibleAt, evaluatedAt, profile) =
    (evaluatedAt - visibleAt) > staleWindow(profile, cadence)     // 초과만 stale, 등호는 fresh
staleWindow(profile, cadence) = engine.stale_profiles[profile][cadence]
                                 ?: engine.stale_profiles[profile]["daily_kr"]   // 키 부재 시 폴백
```

**두 가지 함정을 반드시 명시적으로 처리한다.**

1. **기준 시각은 `as_of`가 아니라 `visible_at`이다.** `run_replay.is_stale_check`의 확정 사항이다. 차이는 결측이 있을 때 즉시 드러난다 — 예: mobile `daily_kr` 30h, D+1 틱에서 당일 KR 데이터가 없어 전일(as_of=D) 값을 쓰는 경우, as_of 기준이면 32h로 **stale**, visible_at 기준이면 24h로 **fresh**다. 두 구현은 서로 다른 국면을 커밋한다. **정본은 visible_at**이며, 이 규칙을 어기면 결측이 낀 모든 창에서 BT-05가 깨진다.
2. **`mobile_daily`에는 `intraday_30m` 키가 없다** → `daily_kr`(30h)로 폴백한다. 이 폴백은 yaml 본문이 아니라 `registry.stale_window`의 "Advisor 지정 해석"에만 존재하는 규칙이며, 영향받는 지표는 **usdkrw_z·vkospi_z·kospi_drawdown 3종**(전체 가중 8.0/31.0)이다. Kotlin에서 이 규칙을 빠뜨리면 세 지표가 조용히 다른 창을 쓴다 — 전용 테스트로 고정한다(§8.5).

프로파일별 실효 창(mobile_daily):

| cadence | 창 | 해당 지표(가중 합) |
|---|---|---|
| daily_us | 48h | vix_level_z, vix_term_structure, move_index_z, dxy_z, spx_drawdown_momentum (10.5) |
| fred_daily | 96h | hy_oas_delta, ust_2s10s_move (4.0) |
| daily_kr | 30h | krx_credit_spread_delta, kr_cds_5y_delta, global_corr_break, foreign_net_sell_kospi, kospi_volume_distribution (8.5) |
| intraday_30m → daily_kr | 30h | usdkrw_z, vkospi_z, kospi_drawdown (8.0) |

### 5.4 확정 틱의 as_of 컷오프 — 라이브·캐치업 동일성의 근거

확정 틱 D가 **읽을 수 있는 원계열의 상한**을 계열 종류별로 정한다.

```
asOfCutoff(kind, D) = when(kind):
    krx, fx    -> D                    // 당일 종가 포함
    us_market  -> D - 1일               // D 당일 미국 종가는 아직 없다
    fred       -> D - lagDays(=1)일
```

**컷오프는 §5.2 가시성 규칙의 날짜 표현이며 둘은 동치다**(둘 중 하나만 구현하면 되는 것이 아니라, 컷오프가 범위를 자르고 가시성 lookup이 최종 선택을 한다 — §5.4.1):
- `us_market`: `firstAfter(T) ≤ D` ⟺ `T < D` ⟺ `T ≤ D−1` ✓
- `fred`: `firstOnOrAfter(T+lag) ≤ D` ⟺ `T+lag ≤ D` ⟺ `T ≤ D−lag` ✓
- `krx`·`fx`: `firstOnOrAfter(T) ≤ D` ⟺ `T ≤ D` ✓

따라서 컷오프로 자른 시계열의 **모든 행은 D 틱에서 가시**이며, 그 위에서 계산한 causal transform에는 look-ahead가 물리적으로 섞일 수 없다.

- **라이브에서는 이 필터가 무해**하다(존재하지 않는 미래 데이터를 거를 뿐).
- **캐치업에서는 이 필터가 look-ahead를 실제로 차단**한다. D를 D+2에 계산할 때 US as_of=D 종가(실제로는 D+1 05:00 KST 공개)를 쓰지 않게 만든다. 이 필터가 없으면 캐치업 틱은 라이브 틱과 다른 국면을 낸다 — K-11 위반이자 멱등성 파괴다.
- `observed_at` 필터는 확정 틱 경로에서 **쓰지 않는다**(§14 D-B2 결정 대상, 권고 근거는 §5.6). 대신 `readAsAt(asOfCutoff, observedCutoff)`를 감사·테스트 전용으로 둔다.

#### 5.4.1 원계열 조회 계약과 계산 순서 — **틱당 1행이 아니라 시계열을 읽는다**

> **정정 기록(라운드 3)**: 이전 판의 §2 다이어그램·§5.2.1 ③은 "계열별 최신 1행"을 엔진에 넘겼다. **활성 15지표의 대부분이 롤링 창을 요구하므로 1행으로는 계산 자체가 불가능하다.** `engine_ref/transforms.py`는 전부 `rolling(window, min_periods=window)`이라 행이 부족하면 결과가 `NaN`(= 결측)이 되어, 백필(MT1-04g)로 데이터가 있어도 **읽는 계약이 없으면 전 지표가 조용히 결측**이 된다. 아래가 그 계약이다.

**(1) 필요 행 수는 코드가 아니라 `indicators.yaml`의 transform 문자열에서 파생한다** (리터럴 금지 — MT1-05a의 파서가 이미 window/lookback을 파싱한다). 합성 체인의 필요 행 수 = `1 + Σ(각 단계 window − 1)`:

| 지표 | 합성 체인 | 필요 행 |
|---|---|---|
| **vkospi_z**(K-02 폴백 경로) | `pct_change_1d` → `realized_vol(20)` → `zscore(252)` | **272** ← 최대 |
| dxy_z / spx_drawdown_momentum(neg_z 성분) | `pct_change_5d` → `zscore(252)` | 257 |
| foreign_net_sell_kospi | `rolling_sum(5)` → `neg_zscore(252)` | 256 |
| usdkrw_z | `pct_change_1d` → `zscore(252)` | 253 |
| vix_level_z / move_index_z / vkospi_z(실 VKOSPI 경로) | `zscore(252)` | 252 |
| global_corr_break | `pct_change_1d` → `rolling_corr(20)` → `rolling_mean_corr(120)` | 140 |
| kospi_drawdown / spx(dd 성분) | `drawdown_from_high(60)` | 60 |
| kospi_volume_distribution | `zscore(60)` (+게이트용 `pct_change_1d`) | 61 |
| hy_oas_delta / ust_2s10s_move / krx_credit_spread_delta / kr_cds_5y_delta | `delta_bp(lookback=5)` | 6 |

**`readSeriesForTick`의 N = 위 파생값 + 여유 10행**. 창은 **달력일이 아니라 행 수**로 센다 — `rolling`이 행 단위이므로 휴장·결측이 있어도 자동으로 정합한다(달력일로 세면 휴장이 낀 구간에서 창이 모자라 조용히 NaN이 된다).

**(2) 쿼리** — as_of당 최신 revision 1행씩, 오름차순 N행. **SQLite 버전 무관 형태**(상관 서브쿼리):
```sql
-- 확정 틱 경로 (origin='preview' 행은 SQL에서 배제 — §10.3)
SELECT o.as_of, o.value
FROM observation o
WHERE o.series_id = :s AND o.field = :f
  AND o.as_of <= :asOfCutoff                       -- §5.4 계열종류별 컷오프
  AND o.origin <> 'preview'                        -- ★ 확정 경로 전용 필터
  AND o.id = (SELECT o2.id FROM observation o2
              WHERE o2.series_id = o.series_id AND o2.field = o.field
                AND o2.as_of = o.as_of AND o2.origin <> 'preview'
              ORDER BY o2.revision DESC, o2.observed_at DESC, o2.id DESC
              LIMIT 1)                             -- as_of당 결정적 1행(§5.4 tie-break)
ORDER BY o.as_of DESC LIMIT :n;                    -- 사용 시 오름차순으로 뒤집는다
```
- **프리뷰 경로**는 같은 쿼리에서 `origin <> 'preview'` 두 곳을 **빼고**, 상관 서브쿼리의 정렬에 **confirm 우선 tie-break를 앞에 붙여** 쓴다(프리뷰는 장중값을 봐야 하지만, 이미 확정된 as_of에서는 확정치가 이긴다):
  ```sql
  ORDER BY (o2.origin = 'preview') ASC,        -- 0=확정/백필 먼저, 1=프리뷰 나중 ★ 방향 명시
           o2.revision DESC, o2.observed_at DESC, o2.id DESC LIMIT 1
  ```
  이 한 줄이 없으면 **프리뷰가 이긴다**(같은 as_of에서 프리뷰 행의 `observed_at`이 대개 더 늦다) — 17:00 이후 프리뷰가 확정 종가 대신 자기 장중값을 다시 보게 된다. 즉 쿼리 1개 · 플래그 1개 · tie-break 1줄.
- **윈도 함수(`ROW_NUMBER() OVER`) 의존을 제거했다**(라운드 4 정정). 이전 판은 SQLite 3.25+를 전제했는데, minSdk 29 기기의 번들 SQLite 버전은 **검증되지 않은 가정**이었다. 상관 서브쿼리는 SQLite 전 버전에서 동작하므로 **실측 선행 과업 자체가 불필요**해진다(가정을 없애는 쪽이 가정을 측정하는 쪽보다 싸다).
- 비용: 인덱스 `(series_id, field, as_of, origin, revision DESC, observed_at DESC, id DESC)` 하나면 서브쿼리가 인덱스 선두 1행 조회로 끝난다. N ≈ 282행 × 15지표 = 틱당 수천 행 스캔 — 무시 가능.
- `ponytail`: 상관 서브쿼리가 실측에서 느리면 그때 윈도 함수로 올린다(그 시점엔 실기기 SQLite 버전이 측정된 사실이 된다). M1에서는 불필요.

**(3) 계산 순서 — 정본과 동일해야 한다** (`run_replay.py` `build_known_series`/`lookup_known`, L289-327):
```
① 원계열 구성:  readSeriesForTick(seriesId, field, D)  →  (as_of, value)[] 오름차순
② 변환:        transform(원계열 전체)                   →  (as_of, out)[]      ← causal, 한 번만
③ 가시성 색인:  out 행마다 combinedVisibleAt(inputSeriesIds, as_of, grid, confirmTime)
                NaN 값·가시성 null 행은 버린다                                  (= build_known_series)
④ 선택:        가시일 ≤ tickDay 인 것 중 as_of 최대 1건 (§5.2 isVisibleAt)      (= lookup_known)
               확정: tickDay = D / 프리뷰: tickDay = today (§5.4.2)
⑤ 스테일·severity: §5.3 → §5.7
```
**②를 ④ 뒤로 옮기면(=선택된 1행만 변환) 값이 달라진다 — BT-05 L1/L2 확정 실패다.** 반대로 ①의 컷오프 절단은 안전하다: causal transform은 위치 `i`에서 `≤ i`만 보므로 **꼬리 절단이 앞선 출력값을 바꾸지 않는다**(§5.4의 동치 증명으로 절단면이 곧 가시 경계와 일치). 이것이 "필터-후-변환"이 허용되는 유일한 형태다 — **중간 행을 빼는 필터는 금지**(단일 계열의 가시성은 as_of에 단조라 필터 결과가 항상 접두사이므로, 정상 구현에서는 애초에 발생하지 않는다).

**(4) 보조 계열·2계열 지표의 처리**
- 2계열 지표(`vix_term_structure`, `global_corr_break`)는 **각 계열을 자기 컷오프로 따로 읽어** 변환에 넣고, 출력 행의 가시 시각만 `combinedVisibleAt`으로 합산한다(§5.2). `global_corr_break`는 `^GSPC`(컷오프 D−1)와 `KRX:1001`(컷오프 D)의 관측일 집합이 달라 정렬이 필요하다 — 정본은 **KOSPI 거래일 인덱스에 SPX 수익률을 causal ffill**(`run_replay._align_to_ffill`)이며 이 규칙까지 이식 대상이다.
- modifier 보조 입력(`hy_oas_delta`의 레벨 계열, `usdkrw_z`의 high/low/prev_close)은 별도 시계열로 읽되, **선택된 주 계열 행의 `as_of`를 키로 조회**한다(§5.2 말미). `prev_close`는 그 시계열의 **직전 행**(positional shift)이다.
- `vkospi_z`의 폴백 분기는 **데이터로 판정**한다: `KRX:VKOSPI` 계열 조회 결과가 비면 `KRX:1001` 종가 기반 폴백 체인으로 간다(하드코딩 금지, §5.2 매핑표).

**(5) 워밍업 부족 구간의 정직한 거동**
설치 직후·백필 진행 중에는 N행을 못 채운다 → `min_periods` 미충족 → `NaN` → **결측**(D-02 분모 제외)이며, 전 지표가 결측이면 D-25 §3 "평가 불능"으로 그 틱이 동결된다. 이는 정상 동작이므로 **감추지 않는다**: `tick_run`에 워밍업 부족 지표 목록을 남기고, 홈에 "워밍업 n/272일" 진행 표기(§17 C-2·C-4). MT1-04g의 완료 기준(15지표 × ≥252거래일)은 여기서 나온 **272행**을 상한으로 재정의한다.

**(6) 이 계약 위에서 실행 가능해지는 완료 기준**

| 서브태스크 | 이 계약이 없으면 | 계약 적용 후 완료 기준 |
|---|---|---|
| MT1-03c | 롤링 창을 못 채워 전 지표 NaN | `readSeriesForTick`이 N행·오름차순·as_of당 최신 revision을 반환하는 테스트 + 컷오프 경계(3 kind × 경계일) 테스트 green |
| MT1-04g | "252거래일 확보"의 기준이 없음 | 백필 목표 = **272행**(파생값)·지표별 충족 여부 테스트 green |
| MT1-05b | transform 입력 자체가 없음 | 원계열 → transform → `transforms.json` 대조(L1) green |
| MT1-05f(BT-05) | L1/L2 확정 실패 | ①~⑤ 순서로 9창 재생 → L0~L5 green. **순서 증인**: ②를 ④ 뒤로 옮긴 변이가 반드시 실패함을 보이는 테스트 1건(신설 규율 ①) |
| MT1-06c | 확정 틱이 전 지표 결측으로 동결 | 워밍업 충족 픽스처에서 정상 국면 커밋 + 미충족 픽스처에서 동결·표기 green |

#### 5.4.2 프리뷰의 시각 규약 — `evaluated_at`·가시 판정·나이 (신설, B 관점 입장)

프리뷰는 임의 시각 `now`에 돈다. §5.2~§5.4의 모든 판정이 시각 인자를 받으므로 이를 못 박지 않으면 프리뷰는 정의되지 않은 계산이다.

**규정(3줄)**
```
tickDay        = today (KST 기준 날짜)          → 가시 판정에만 사용 (§5.2 isVisibleAt)
asOfCutoff     = asOfCutoff(kind, today)         → 확정 틱과 동일 함수 (§5.4)
evaluatedAt    = now (UTC, 실시간)               → 스테일 판정에만 사용 (§5.3)
```
즉 **프리뷰 = 확정 틱과 같은 파이프라인**이고 차이는 넷뿐이다: ① `D → today` ② `evaluatedAt = now` ③ `origin='preview'` 행도 읽는다(§5.4.1 (2)) ④ 커밋하지 않고 carry-forward·coverage 억제를 적용한다(§10). 새 규칙은 0개다.

**왜 "격자 시각을 그대로 쓰기"가 안 되는가(기각 논증).** `visible_at`은 `visDay 17:00`이다. 오늘 오전 10시 프리뷰에서 어제 미국 종가(as_of=D−1)의 `visible_at`은 **오늘 17:00 = 미래**다. `visible_at ≤ evaluated_at`을 시각으로 비교하면 이 값은 "아직 안 보이는 값"이 되어 **US·FRED 7지표(가중 14.5/31 = 46.8%)가 프리뷰에서 상시 결측**이 된다 → raw coverage ≈ 53% → §10.1의 80% 억제가 **항상** 걸려 프리뷰가 영구 무용지물이 된다. 실제로는 그 값을 오늘 새벽에 이미 알 수 있으므로 이 결측은 사실도 아니다. 그래서 가시 판정을 날짜로 올린다(§5.2 말미).

**나이(스테일)는 스냅이 아니라 실경과다 — 채택 근거.**

| 후보 | age 산식 | 밤 23:00 프리뷰에서 전일(D−1) KR 값(창 30h) |
|---|---|---|
| (A) **실경과 `now − visible_at`** ← **채택** | 실제 경과 시간 | D 23:00 → 30h 초과 → **stale(결측)** |
| (B) 오늘 확정 시각으로 스냅(`D 17:00 − visible_at`) | 항상 24h의 배수 | 24h → fresh |

(A)를 택하는 근거 3:
1. **스테일의 정의가 "지금 이 값을 믿을 만한가"** 이다. 밤 11시에 30시간 묵은 종가를 "24시간 됐다"고 계산하는 것은 정의를 어기는 것이다.
2. **D-17 §2가 프리뷰에 `as_of` 표기를 의무화**한 취지가 "지금 기준 얼마나 묵었는가"를 사용자에게 드러내라는 것이다. 표시값(실경과)과 판정값(스냅)이 어긋나면 화면이 자기모순이 된다.
3. **오류 방향이 안전한 쪽**이다. (A)는 결측을 더 자주 만들어 coverage를 낮추고 억제를 더 자주 건다. 프리뷰는 비확정이고 D-23의 목적이 **과대평가 차단**이므로, 보수적 오류(억제)가 낙관적 오류(잘못된 잠정 경보 발신)보다 낫다.

부수 성질: `visible_at`이 미래일 때(오전 프리뷰) age는 음수가 되지만 판정은 `> window`이므로 자동으로 fresh다 — 클램프 불필요(코드 0줄).

**확정 틱 산출 불변**: 위 규정 중 확정 경로에 영향을 주는 것은 "가시 판정을 날짜로" 하나뿐이며 §5.2 말미에서 동치임을 보였다. **BT-05 기대값 재생성 불필요.**

**완료 기준(MT1-07a)**: ① 오전 10시 프리뷰에서 US·FRED 7지표가 **가시**(결측 아님)임을 단언 ② 밤 23:00 프리뷰에서 전일 KR 값이 stale(30h 초과) 판정됨을 단언 ③ 같은 lake 상태·같은 today에 대해 프리뷰가 확정 틱의 국면·`tick_input`을 변경하지 않음을 단언.

#### 5.4.3 프리뷰 경로의 원장 접근 규율 — 읽기 지점 3개 (정본 표)

> 이 표가 프리뷰 관련 원장 접근의 **유일한 정본**이다. 읽기 지점은 셋뿐이며, 각 지점의 판별자 값과 증인 테스트를 여기서 못 박는다. 새 읽기 지점을 추가하려면 이 표를 먼저 고쳐야 한다.

| # | 읽기 지점 | 호출 위치 | 판별자(`origin`) | 동일 as_of tie-break | 증인 테스트(반드시 실패해야 하는 변이) |
|---|---|---|---|---|---|
| **①** | **확정 틱 조회** `readSeriesForTick(..., includePreview=false)` | MT1-06c 확정 파이프라인 · 캐치업 | **`origin <> 'preview'`** — **본 쿼리와 상관 서브쿼리 양쪽**(§5.4.1 (2)) | `revision DESC → observed_at DESC → id DESC` | `W-①a`: 어떤 as_of에 `preview` 행만 있으면 확정 조회 결과에 **그 as_of가 없다**. `W-①b`: `preview`·`confirm` 공존 시 **confirm 값**이 선택된다. `W-①c`: 필터를 한쪽(서브쿼리)만 지우면 두 테스트 중 하나가 **실패**한다 |
| **②** | **프리뷰 신선분 조회** `readSeriesForTick(..., includePreview=true)` | MT1-07a 프리뷰 파이프라인 | 필터 없음(**preview 포함**) | **`(origin='preview') ASC` 를 맨 앞에** → 0=confirm/backfill 우선, 1=preview 나중. 이후 `revision DESC → observed_at DESC → id DESC` | `W-②a`: 17:00 이후 시나리오에서 같은 as_of에 confirm(종가)·preview(장중) 공존 → **confirm 종가**가 선택된다. `W-②b`: tie-break 첫 줄을 지우면 `W-②a`가 **실패**한다(프리뷰의 늦은 `observed_at`이 이김). `W-②c`: confirm이 없는 장중 시각에는 preview 값이 선택된다 |
| **③** | **carry-forward 원천** `lastCommittedTickInput()` | MT1-07b carry-forward 해석기 | **원장을 읽지 않는다** — 마지막 `phase_commit`된 `tick_input` 1행의 `severities`/`as_of`를 쓴다(§10.1.2). `tick_input`은 ①로만 만들어지므로 **확정 전용이 구조적으로 보장** | 해당 없음(단일 행, `tick_date` 최대) | `W-③a`: 프리뷰만 실행한 상태에서는 `tick_input` 행이 **늘지 않는다**(프리뷰는 커밋하지 않는다). `W-③b`: carry 원천을 ②(프리뷰 포함 조회)로 바꾸면 자기참조가 생겨 §10.1.2의 이월 severity 단언이 **실패**한다. `W-③c`: `tick_input`이 0행(설치 직후)이면 이월 없이 결측 유지 |

- 세 지점의 **판별자는 서로 다르고 교차 사용은 금지**다. 코드상으로는 `includePreview` 플래그 1개와 별도 메서드 1개(`lastCommittedTickInput`)로 구분되며, 플래그의 기본값은 **`false`(확정)** 로 둔다 — 잊고 안 넘기면 안전한 쪽으로 떨어지게 하는 배치다.
- 증인 8건은 MT1-03c(①)·MT1-07a(②)·MT1-07b(③)의 완료 기준에 각각 편입된다(§3.4·§3.8).

### 5.5 틱 그리드 = KRX 거래일

- 확정 틱은 **KRX 거래일에만** 생성한다(휴장일 무틱). run_replay의 그리드가 "KRX 관측이 실제로 난 날의 합집합"이므로, 휴장일에 틱을 만들면 히스테리시스 카운트(promote 1 / demote 3 / dwell 5 / cooldown 2)가 하니스와 어긋난다.
- 거래일 출처: `kotlin_krx`의 business-day API(1차) → 로컬 캐시(2차) → **관측된 KRX as_of 날짜 집합**(3차, 하니스와 동일한 경험적 규칙). `exchange_calendars` 계열의 정적 달력은 쓰지 않는다(MT0-03 교훈: 2026 임시 휴장 드리프트 → 유령 결측).
- 주말·휴장일 판정 실패 시의 안전 기본값은 "**틱 생성 보류 + 이력 기록**"이다(잘못된 틱을 커밋하는 것보다 낫다 — append-only 원장에서 잘못된 커밋은 되돌릴 수 없다).

### 5.6 멱등·캐치업·전량 재계산

**커밋 절차(원자성)**
```
1) tick_run(started) 기록 — 진행 중 표식
2) 수집 → lake append(변경분만)
3) 지표별 readSeriesForTick(D) → 변환 → 가시성 색인 → lookup (§5.4.1 ①~④)
   → (value, visible_at, cadence) → 스테일·severity → 엔진 → Tick 레코드
4) tick_input INSERT (tick_date UNIQUE) — 실패 = 이미 처리됨 → 즉시 no-op 종료
5) tick_input 전량 SELECT(오름차순) → statemachine.fold → timeline
6) phase_commit UPSERT-금지·INSERT(tick_date UNIQUE) — 마지막 원소만 커밋
7) 노티 → tick_run(finished)
```
- **멱등의 물리적 근거는 UNIQUE 인덱스 2개**(`tick_input.tick_date`, `phase_commit.tick_date`)다. 애플리케이션 로직의 if-else가 아니다.
- **전량 fold를 쓰는 이유**: `engine_ref.statemachine.run`이 틱 리스트의 fold이므로, 증분 상태(스트릭·dwell·cooldown)를 앱에 저장하면 그 저장 형식이 곧 두 번째 진실이 되어 D-25 §1~4를 어길 여지가 생긴다. 전량 재계산은 (a) 상태 마이그레이션 불필요 (b) 중단 복구 자동 (c) BT-05가 검증한 코드 경로를 프로덕션이 그대로 사용, 세 이득을 동시에 준다. 비용은 연 ~250틱 fold — 무시 가능.
- **`tick_input`은 커밋 시점에 동결**된다. 이후 도착한 개정치(revision)는 과거 틱을 재작성하지 않는다(PIT). 재계산은 항상 같은 입력 위에서 돈다 → 결정론.

**캐치업**
- 누락 거래일을 **오름차순 1일 1커밋**으로 처리한다(건너뛰기·일괄 처리 금지 — 히스테리시스가 틱 단위 카운트이므로).
- 각 커밋은 §5.4의 as_of 컷오프를 그대로 적용한다 → **라이브와 동일 산출**(MT1-06g가 이를 실행 가능하게 증명: 같은 lake 상태에서 라이브 경로와 캐치업 경로의 timeline 완전 일치).
- 다만 값 자체는 사후 수집분이라 **개정치가 섞일 수 있다** — M0 픽스처와 정확히 같은 지위인 "**근사-PIT**"다. 이를 `phase_commit.is_catchup = true`로 표기하고 UI·이력에 노출한다(정직성 조항, BACKTEST_PLAN §5의 승계).
- 노티: 캐치업 커밋은 **가장 최근 1건만 사용자 노티**(과거 N일치 알림 폭탄 금지), 나머지는 이력에만. 잠정 경보는 발신하지 않는다.

#### 5.6.1 캐치업 소급 상한 (R-B9 = K-03과의 상충 해소)

**상한 = 최근 20 거래일**(위치: §15 제안 7 — `configs/statemachine.yaml` `profiles.mobile_daily.catchup_max_ticks: 20`. 코드 리터럴 금지).

근거 3:
1. **상태기계 관점**: mobile_daily의 히스테리시스 최장 상수 합은 `demote_below 3 + min_dwell 5 + cooldown 2 = 10틱`이다. 20틱이면 그 두 배 여유가 있어, 절단이 현재 국면 판정에 남기는 잔여 영향이 §5.6.2의 두 항목(≤2틱)으로 한정된다.
2. **K-03 예산**: 야후·FRED·ECOS는 범위 조회 1콜로 N일치를 받는다. 비용이 일수에 비례하는 것은 KRX 일자별 엔드포인트뿐이며, 20거래일 × 지표 ≈ 20~60콜 → 1req/s에서 1분 내. 상한이 없으면 6개월 미실행 후 복귀 시 수백~수천 콜이 한 번에 나가 차단(403)을 부른다.
3. **정직성**: 20거래일 넘게 미실행이면 데이터 신선도도 사용자 맥락도 이미 끊겼다. "조용히 한 달치를 소급 커밋"보다 "공백을 공백으로 표시"가 옳다.

초과 시 거동(테스트로 고정, MT1-06e):
- 상한 내 최근 20거래일만 틱 생성·커밋한다. 그 이전 공백 구간은 **틱을 만들지 않는다** — 그 날들은 `tick_input`에 행이 생기지 않는다.
- `tick_run`에 `kind='gap'` 레코드(구간 시작·끝·누락 거래일 수) 1건. 홈에 "N거래일 공백 — 국면 이력 불연속" 배지(§17 C-2).
- **카운터 리셋은 하지 않는다.** 상세와 근거는 §5.6.2.

#### 5.6.2 절단 구간의 카운터 처리 — 리셋을 하지 않는다 (설계 정정)

> **정정 기록(라운드 2)**: 본 계획의 이전 판은 "공백 직후 첫 틱에서 스트릭·cooldown 리셋"을 규정했다. **이는 `engine_ref.statemachine.run`의 API로 표현 불가능하며, 동시에 불필요하다.** 두 사실을 아래에 실측 근거로 기술한다.

**(1) 엔진 변경 없이 표현 가능한가 — 불가능하다 (반증 수용)**

`engine_ref/statemachine.py`를 직접 확인한 결과:
- `run()`은 `phase = config.initial_phase`(L114)에서 시작한다 → "국면은 유지하되 카운터만 리셋"이라는 상태를 외부에서 주입할 입구가 없다.
- `Tick`이 가진 필드는 `(composite, distinct_axes, any_crit, any_extreme)` 넷뿐이며, 어떤 조합으로도 세 카운터를 동시에 0으로 만들 수 없다: `composite=None`은 **완전 동결**(L124-126, 틱 미소비 — 리셋도 안 됨), 숫자 composite는 `ticks_in_phase += 1`을 강제(L131)하고 `promote_streaks`를 규칙대로 증감시키며(L138-145), `cooldown`은 틱당 1씩 감소만 한다(L189-190).
- 따라서 리셋은 **`run()` 시그니처·거동 변경**을 요구한다 = §14 D-B3에서 스스로 금지한 "엔진 거동 변경"에 해당하고, 이전 판의 MT1-06e 완료 기준("스트릭 리셋 green")은 실행 불가능한 기준이었다.

**(2) 그래서 필요한가 — 필요 없다 (정량 근거)**

mobile_daily 파라미터(`promote_sustain 1 / demote_below 3 / min_dwell 5 / cooldown 2`)로 "절단 이전 카운터가 살아남을 때"의 최대 영향을 계산하면:

| 카운터 | 절단 넘어 이월될 때의 영향 | 크기 |
|---|---|---|
| `promote_streaks` | **영향 0** — `promote_sustain_ticks = 1`이므로 승격은 `streak >= 1`, 즉 **그 틱 자신이 조건을 충족할 때만** 일어난다(미충족 틱은 즉시 0으로 리셋, L144-145). 과거 스트릭은 승격을 단 1틱도 앞당기지 못한다 | **0틱** |
| `demote_streak` | 절단 직전까지 최대 2가 쌓여 있을 수 있어, 복귀 후 이탈이 계속 충족되면 강등이 최대 2틱 빨라진다 | ≤2틱(보수 방향) |
| `cooldown` | 절단 직전 강등이 있었다면 최대 2틱 동안 승격이 막힌다 | ≤2틱 |
| `ticks_in_phase`(dwell) | 공백 동안 증가하지 않으므로 dwell 보호가 **약해지지 않는다**(오히려 달력상 더 길게 보호) | 0(보수 방향) |

즉 **내가 막으려 했던 "한 달 전 스트릭 부활에 의한 갑작스러운 승격"은 mobile_daily에서 원리적으로 발생하지 않는다.** 남는 것은 ≤2틱의 강등 조기화(보수 방향)와 ≤2틱의 승격 지연뿐이며, 둘 다 한 번의 캐치업에서 최대 2영업일 안에 소멸한다.

**(3) 채택 규정 — 공백은 "존재하지 않는 틱"이다**

`run()`은 틱 리스트의 fold이므로 **달력 거리를 모른다**. 이는 하니스에서도 이미 그렇다: 골든 창의 `2024-07-26(금) → 2024-07-29(월)`, 설·추석 연휴(4~5일)는 전부 "연속한 두 틱"으로 처리되고 그 위에서 골든이 동결됐다. **25거래일 공백은 긴 휴장과 엔진 관점에서 완전히 동형**이다. 따라서 절단 구간은 특별 처리 없이 "틱이 없는 날"로 두는 것이 정본 의미론과 일치하는 유일한 처리다.

**비평가 지정 명시 3항**

**(a) 프로덕션 국면 승계 방식**
`tick_input`의 **최초 행부터 현재까지 전량**을 `tick_date` 오름차순으로 fold한다. `config.initial_phase`(GREEN)는 **이력의 첫 틱에만** 적용되며, 그 이후 국면은 전부 fold 산출물이다 — 앱은 국면을 "승계"하지 않고 **매번 재도출**한다. `phase_commit`은 fold 결과의 마지막 원소를 기록하고, 동시에 **과거 `phase_commit` 행이 이번 fold의 대응 원소와 일치하는지 검증**한다(불일치 = `tick_input` 훼손 신호 → 틱 실패 처리 + `tick_run`에 기록. 조용히 덮어쓰지 않는다). 검증은 MT1-06g의 결정론 테스트가 고정한다.
`ponytail`: fold 길이는 연 ~250틱(10년 ≈ 2,500)이라 상한을 두지 않는다. 이것이 병목이 되면 그때 "N틱마다 상태 스냅샷"을 넣는다 — M1에서는 불필요하다.

**(b) fold의 근거 데이터 — 테이블·컬럼**

| 컬럼 | 타입 | fold 입력? | 용도 |
|---|---|---|---|
| `tick_date` | TEXT(ISO date) **UNIQUE** | 정렬키 | 멱등(§5.6) + fold 순서 |
| `composite` | REAL **NULL 허용** | **예** → `Tick.composite` | NULL = D-25 §3 평가 불능(동결) |
| `distinct_axes` | INTEGER | **예** → `Tick.distinct_axes` | ORANGE/RED 요건 |
| `any_crit` | INTEGER(0/1) | **예** → `Tick.any_crit` | AMBER 이스케이프 + D-26 짝지음 |
| `any_extreme` | INTEGER(0/1) | **예** → `Tick.any_extreme` | ORANGE 이스케이프 + D-26 짝지음 |
| `coverage` | REAL | 아니오 | 표시·§5.8 저커버리지 표기 |
| `severities` | TEXT(JSON) | 아니오 | 감사·패리티 계층 분해(§8.3) |
| `fired_axes` | TEXT(JSON) | 아니오 | 표시·골든 대조 |
| `evaluated_at_utc` | INTEGER | 아니오 | 감사(§5.2.1 ⑤) |
| `visible_at_by_indicator` | TEXT(JSON) | 아니오 | 감사·스테일 재현 |
| `is_catchup` | INTEGER(0/1) | 아니오 | 근사-PIT 표기(§5.6) |

즉 **fold가 실제로 읽는 것은 4개 컬럼**이며 이는 `statemachine.Tick`의 필드와 1:1이다. 나머지는 표시·감사 전용이라 fold 결과에 영향을 줄 수 없다(구조적 보장).

**(c) 절단 카운터 처리의 엔진 변경 없이 표현 가능 여부**
**리셋은 불가능**(위 (1)). 본 계획은 **리셋을 채택하지 않음**으로써 엔진 변경 없이 성립한다(위 (2)(3)). 리셋 정책이 필요하다고 판정되면 그것은 `engine_ref` 변경(예: `run(..., initial_state=...)` 주입)이므로 **정식 상신 사항**이며, 골든 2케이스×2프로파일 재확인 + BT-05 기대값 재생성 + D-25 §3 상호작용 분석이 동반돼야 한다. C안(P-12/U-6)이 이 경로를 택했으므로 **§14 D-B13에서 택일 대상으로 상신**한다 — B 관점의 권고는 "엔진 무변경(리셋 없음)"이다.

### 5.7 결측 의미론 (D-02/D-25 §3 일치 체크리스트)

1. 결측 = `severity == null` → **분자·분모 모두 제외**. `optional: true`도 동일 취급(kr_cds).
2. 분모(전체 가중)는 **enabled 지표만**의 가중 합 = **31.0**. `enabled:false`(krx_halt 3.0, margin 2.0, news 2.0×2)는 애초에 집합에 없다.
3. `coverage = 유효가중 / 전체가중`. 전 지표 결측(유효가중 0) → `score = null` → 상태기계는 그 틱을 **완전 동결**(국면·스트릭·dwell·cooldown 불변, 틱 미소비).
4. 확정 틱에는 carry-forward가 없다(D-23 §23.3-4). 스테일 초과 값은 **무효 = 결측**이다.
5. `any_crit = severity >= 3`(== 아님), `any_extreme = is_extreme(원값)`, `distinct_axes = severity >= 2인 축의 종류 수`.

### 5.8 확정 틱의 저커버리지 — 발견된 규율 공백

D-23의 커버리지 억제는 **프리뷰 전용**이다. 그런데 KRX 수집이 실패한 확정 틱은 유효가중 22.5/31.0(72.6%)로 떨어지면서 D-23 §23.2가 경고한 **바로 그 왜곡**(국내 침묵이 점수에서 사라짐)을 확정 국면으로 커밋할 수 있다. 확정 틱에는 억제 규칙이 없기 때문이다.

- 즉시 채택 가능한 대응(M1, 의미론 무변경): ① 확정 틱 전 **수집 재시도**(백오프 3회) ② `phase_commit`에 coverage 기록 ③ coverage < 80%면 노티 문구에 "부분 결측 확정" 표기 + 홈에 배지 ④ `tick_run`에 결측 지표 목록 기록.
- 의미론 변경(확정 틱 coverage 하한 → 동결)은 **엔진 거동 변경**이라 골든·BT-05·D-25 §3의 정의를 건드린다 → §14 **D-B3**으로 사용자 상신, M1에서 임의 구현 금지.

---

## 6. 심화 B — contracts 스냅샷 (Python 측 신규 작업 포함)

### 6.1 현황

`contracts/{snapshot,evidence}.py`는 pydantic 정본이지만 **스냅샷 파일도, 스냅샷 테스트도 양측 모두 없다**(`tests/test_configs_schema.py`는 import 가능 여부만 확인). MT1-02는 Kotlin 미러 이전에 **Python 측 정본화**부터 해야 한다.

### 6.2 형식 선택 — 인스턴스 골든 + 형상 다이제스트

| 후보 | 판정 |
|---|---|
| (A) JSON Schema 상호 비교 | **비채택**. pydantic의 `$defs`/`anyOf` 구조는 Python 방언이고, kotlinx.serialization은 JSON Schema를 생성하지 않는다. 양측에 스키마 생성기를 각각 만들면 "미러 검증용 코드"가 미러보다 커진다 |
| **(B) 정본 인스턴스 왕복 + 형상 다이제스트** | **채택**. 인스턴스는 실제 상호운용(필드명·날짜 포맷·튜플 인코딩·제약)을 검증하고, 형상 다이제스트는 인스턴스가 못 잡는 "필드 추가/삭제"를 잡는다. 기계장치 최소 |

산출물:
```
contracts/snapshots/
├── scenario_snapshot.min.json      # 필수 필드만
├── scenario_snapshot.full.json     # 전 필드 + 경계값(prob 0.0/1.0, horizon 1/120, scenarios 2·4개)
├── evidence_pack.min.json
├── evidence_pack.full.json
├── invalid/                        # 부정 케이스 — **현행 contracts에서 실제로 거부되는 것만**
│   ├── composite_out_of_range.json # confloat(ge=0,le=100) 위반
│   ├── subjective_prob_over_one.json # confloat(ge=0,le=1) 위반
│   ├── horizon_days_zero.json      # conint(ge=1,le=120) 위반
│   ├── phase_unknown.json          # Literal["GREEN","AMBER","ORANGE","RED"] 위반
│   ├── scenarios_too_few.json      # min_length=2 위반
│   └── leading_indicators_one.json # min_length=2 위반
├── asymmetric/
│   └── naive_datetime.json         # 알려진 비대칭(아래 §6.2.1) — 양측 "현행 거동"을 고정
└── shape.sha256                    # 형상 다이제스트(생성물, 양측 테스트가 재산출해 대조)
```

#### 6.2.1 naive datetime — "양측 거부"는 현행 스키마에서 성립하지 않는다 (수정)

`ScenarioSnapshot.generated_at`·`TriggerBlock.evaluated_at`·`EvidencePack.built_at`은 **평이한 `datetime`** 이고 `AwareDatetime`도 검증기도 0건이다. 즉 **pydantic은 naive를 수용한다.** 반면 Kotlin 측 `Instant.parse`는 오프셋 없는 문자열을 거부한다. 따라서 `naive_datetime`을 "양측 거부" 부정 케이스로 두면 Python 테스트가 통과할 수 없다 — 완료 명령이 실행 불가가 된다.

**채택: (a) — 부정 케이스 세트에서 제외하고, 비대칭 사례로 현행 거동을 고정한다.**
- Python 테스트: naive 입력이 **수용됨**을 단언(현행 거동 핀). 회귀가 아니라 "알려진 차이"의 문서화다.
- Kotlin 테스트: 같은 입력이 **거부됨**을 단언.
- 파일 1개·단언 2개. 이 차이를 방치하면 M2에서 LLM이 오프셋 없는 시각을 반환할 때 Python은 통과·Kotlin은 크래시하는 형태로 터진다 — 지금 눈에 보이게 두는 것이 목적이다.
- 정본 인스턴스(`*.min/full.json`)의 모든 시각은 **aware `Z`** 로만 쓴다(§6.3-2).

**(b)는 §15 제안 8로 정식 상신**(contracts에 `AwareDatetime` 적용 — `model_json_schema()` 출력은 `format: date-time`으로 불변이라 LLM 구조화 출력 영향 0). 채택되면 이 사례는 `asymmetric/` → `invalid/`로 승격한다. **M1 게이트는 (b)에 의존하지 않는다.**

### 6.3 정규화 규약 (양측 테스트가 강제할 것) — 발견된 모호성 포함

1. **`schema` vs `schema_id`**: `ScenarioSnapshot.schema_id`는 alias `schema`를 갖고 `populate_by_name=True`다. pydantic의 기본 `model_dump_json()`은 **필드명(`schema_id`)** 을, `model_json_schema()`는 기본적으로 **alias(`schema`)** 를 쓴다. 즉 LLM 구조화 출력은 `schema`를 내보내고 저장 직렬화는 `schema_id`를 쓸 수 있다 — **와이어 이름이 확정되지 않은 상태**다. 스냅샷은 이를 못 박아야 한다. 권고: **`by_alias=True`(= `"schema"`)를 정본 와이어 형식으로 고정**(구조화 출력과 일치, 문서 문언 `schema: scenario-snapshot/1`과도 일치). Kotlin은 `@SerialName("schema")`. → §14 **D-B6**.
2. **datetime**: 정본 = RFC-3339 UTC, 밀리초 없음, 접미사 `Z`(예: `2026-08-06T08:00:00Z`). Python 측은 직렬화 후 정규화(offset `+00:00` → `Z`), Kotlin은 `Instant` 커스텀 직렬화. naive 입력의 처리는 **현재 비대칭**이며 §6.2.1이 그 사실을 고정한다(양측 거부는 §15 제안 8 채택 후에야 성립).
3. **`tuple[float, float]`**(`kospi_range_pct`): JSON 2원소 배열. Kotlin은 `List<Double>` + `require(size == 2)`.
4. **제약**: `confloat/conint/min_length`는 kotlinx.serialization이 강제하지 않으므로 data class `init` 블록에서 `require`로 재현. 부정 케이스 4종이 이를 증명한다.
5. **키 순서·공백**: 비교는 **파싱 후 정규화 트리**로 한다(문자열 비교 금지 — 무의미한 실패 방지). 단 형상 다이제스트는 정렬된 정규 형태의 바이트로 산출.
6. **Enum/Literal**: `Phase`, `EventClassification.type`(8종), `severity/confidence`(3종), `usdkrw_bias`(3종)를 Kotlin `enum class` + `@SerialName`으로 1:1. 미지의 값은 **거부**(조용한 fallback 금지).

### 6.4 미러가 죽은 코드가 되지 않게 하는 장치

M1은 LLM을 호출하지 않으므로 계약 소비자가 없다. 그러나 **`TriggerBlock`은 M1이 실제로 생산할 수 있다**(phase, prev_phase, composite_score, distinct_axes, fired_indicators, evaluated_at은 전부 확정 틱 산출물). 따라서 MT1-05f/06c는 확정 틱 결과를 `TriggerBlock`으로 조립하고, MT1-02c 왕복 테스트가 **엔진이 실제로 만든 인스턴스**를 한 건 포함한다. 미러가 M1 안에서 한 번은 실행되게 하는 최소 장치다.

### 6.5 완료 명령

```bash
uv run python scripts/gen_contract_snapshots.py --check   # 정본과 재생성 결과 일치(=drift 0)
uv run pytest tests/test_contracts_snapshot.py -q
./gradlew :contracts:test
```

---

## 7. 심화 C — assets SHA-256·드리프트 차단 (K-16)

### 7.1 복사 대상 (7파일)

`configs/`: `analogue_seed.yaml`, `indicators.yaml`, `news_topics.yaml`, `sources.yaml`, `statemachine.yaml` (5)
`prompts/`: `daily_digest.md`, `scenario_report.md` (2)

M1이 실제로 파싱하는 것은 `indicators.yaml`·`statemachine.yaml`뿐이지만, **부분 복사는 드리프트의 온상**이므로 7개 전부 복사한다(M2에서 analogue_seed·prompts가 즉시 필요해진다). `news_topics.yaml`은 서버 전용이나 SSOT 동일성 증명 비용이 0이므로 포함한다.

### 7.2 3중 검증 구조

| 층 | 무엇을 증명하나 | 소스셋 | 게이트 |
|---|---|---|---|
| ① 빌드 배선(MT1-01c) | 수동 복사·구버전 패키징 **불가** | Gradle | `check` 이전에 항상 실행 |
| ② JVM 해시(MT1-01d) | 저장소 SSOT 바이트 == assets 사본 바이트 | `test` | **CI 게이트** |
| ③ 계측 해시(MT1-01e) | **APK에 실제로 들어간 자산** == 빌드시 SSOT 다이제스트 | `androidTest` | 실기기(GM1 증빙) |

- 다이제스트는 **바이트 기준 SHA-256**(텍스트 아님) — BOM·줄바꿈·cp949 오독 문제를 원천 차단(K-07의 정신, Windows 환경 필수).
- 기대값은 **코드·테스트에 리터럴로 넣지 않는다.** ②는 실행 시 양쪽을 읽어 비교, ③은 빌드 시 SSOT에서 생성해 `androidTest` 리소스로 주입.
- `MANIFEST.sha256`은 **빌드 산출물**이며 git에 커밋하지 않는다(커밋하면 그 자체가 드리프트원).

### 7.3 위험 1건 — 줄바꿈 정규화

git의 `core.autocrlf`가 체크아웃 시 YAML 줄바꿈을 바꾸면 저장소 파일 바이트가 머신마다 달라진다. 본 설계는 **양쪽 모두 같은 체크아웃에서 읽으므로 비교는 성립**하지만, `.gitattributes` 부재 시 "머신 간 동일 다이제스트" 주장은 성립하지 않는다. → 게이트 리포트에 다이제스트 값을 적을 때는 **머신·체크아웃 명시**, 또는 §15 제안 4(`.gitattributes`)를 채택한다.

---

## 8. 심화 D — BT-05 Kotlin 패리티 설계

### 8.1 실행 형태 — JVM 권장(계측 불가)

- 엔진 모듈은 **Android 프레임워크 비의존 순수 Kotlin/JVM**으로 만든다. 설정 로딩은 `InputStream` 공급자 한 개(`ConfigSource`)로 추상화하고 구현 2개(Android `AssetManager` / 파일·리소스)만 둔다 — 이것이 유일하게 정당화되는 인터페이스다.
- 결과: **BT-05는 `./gradlew :engine:test`로 기기 없이 CI에서 돈다.** BACKTEST_PLAN §BT-05의 "또는 로컬 JVM 테스트 타깃"을 채택하는 근거는 ① 픽스처가 수 MB라 계측 실행이 느리고 ② 실기기 의존 게이트는 회귀 주기를 망가뜨리며 ③ 엔진에 Android API가 없으므로 계측이 더 검증하는 것이 없기 때문이다.
- 계측이 반드시 필요한 것은 assets 패키징(MT1-01e)·Room·WorkManager뿐이다.

### 8.2 패리티 범위 — 골든 2창으로는 부족하다(핵심 논증)

| 대상 | 범위 | 판정 지위 |
|---|---|---|
| 골든 2창(`w2024_carry_unwind`, `w2024_05_calm`) × mobile_daily | `golden_mobile.yaml` 동결 타임라인 완전 일치 | **하드 게이트**(GM1) |
| 나머지 7창 × mobile_daily | `metrics.json` 대비 틱별 일치 | **하드 게이트** (아래 근거) |
| 9창 × server_intraday | 참고 대조 | 선택(리포트 전용) — 앱은 mobile_daily만 실행 |
| 합성 config 증인 10종 | 엔진 의미론 격리 | **하드 게이트** |

**7창을 하드 게이트로 올리는 근거**: MT0-08 실측에서 골든 창의 최대 낙폭은 **15.557% < extreme 20.0**이라 `or_any_extreme`이 **골든에서 0회 발화**한다. 또한 `w2024_05_calm`은 전 틱 GREEN·composite 0.0이라 상태기계 전이 경로를 거의 밟지 않는다. 즉 **이번 phase에서 새로 채택한 D-26 짝지음과 or_any_extreme은 골든 2창만으로는 단 한 줄도 검증되지 않는다**. `w2026_structural`이 유일하게 extreme 발화(07-08 첫 ORANGE)를 포함하므로 반드시 범위에 넣는다. 9창 전체 비용은 CSV 약 3MB·JVM 수 초로 무시 가능하다.

### 8.3 산출물 규격 (MT1-05e `backtest/export_parity.py`)

```
backtest/parity/
├── manifest.json                        # 각 파일 SHA-256, registry_version, 생성 시각,
│                                        #   source metrics.json 다이제스트, engine 의미론 마커(D-25§1~4/D-26)
├── <window>.input.csv                   # series_id,field,as_of(ISO-8601 UTC),value
├── <window>.<profile>.expected.json     # 틱별 기대값(아래)
├── <window>.<profile>.transforms.json   # 틱별·지표별 transform 출력 원값(계층 분해용)
├── visibility.json                      # 두 블록: ① (series_id, as_of) -> visible_at (단일 계열)
│                                        #          ② (indicator_id, input_series_ids[], as_of)
│                                        #             -> combined visible_at (worst-of-inputs, §5.2)  [MT1-05h]
└── grid.json                            # 창별 거래일 그리드(경험적 규칙 산출물)
```

`expected.json` 틱 레코드 = `run_replay.replay_window_profile`의 `tick_records` + `phase`:
`date, kst_time, evaluated_at_utc, composite, coverage, distinct_axes, any_crit, any_extreme, fired_axes[], phase` **+ 신설** `severities{indicator_id: int|null}`(계층 분해에 필수 — 실패 시 어느 지표에서 갈렸는지 즉시 특정).

**부동소수 인코딩 규약**: 모든 실수는 Python `repr()`(shortest round-trip)로 기록한다. 반올림·`%.6f` 금지. `export_parity.py --check`는 **내보낸 CSV를 다시 읽어 리플레이했을 때 `metrics.json`과 비트 동일**함을 자체 검증한다(내보내기 과정에서 정밀도가 새지 않았다는 증인).

### 8.4 판정 기준 (계층별 — 실패 지점을 좁히기 위해)

| 계층 | 비교 대상 | 하드 기준 | 관찰 기준(리포트 필수) |
|---|---|---|---|
| L0 가시성 | `visibility.json` | **완전 일치**(밀리초). **결합 가시성(§5.2 매핑표) 포함** — 지표별 `input_series_ids`와 그 max 결과를 함께 내보내 대조한다 | 결합이 단일과 갈리는 (지표, 날짜) 건수 |
| L1 transform | `transforms.json` | 상대 오차 ≤ 1e-9 (NaN↔NaN, ±Inf↔±Inf 동일) | 최대 상대 오차 |
| L2 severity | `expected.severities` | **정수 완전 일치** | 임계 근접 리포트(§8.6) |
| L3 composite/coverage | `expected` | **\|Δcomposite\| ≤ 0.05**(D-18 계약), \|Δcoverage\| ≤ 1e-9 | 실측 최대 \|Δ\| — **1e-6 초과 시 구조적 차이로 간주해 원인 규명 후 진행** |
| L4 타임라인 | `expected.phase` | **완전 일치** | 전이 횟수·최초 ORANGE 일자 |
| L5 골든 | `golden_mobile.yaml` | date·phase·composite(rel 1e-9)·coverage·fired_axes 완전 일치 | — |

- L3의 하드 기준은 계약대로 0.05로 두되, **1e-6 초과를 "통과했지만 조사 대상"으로 규정**한다. 두 구현이 같은 IEEE754 double로 같은 순서로 계산하면 실측 Δ는 0 또는 1e-12 수준이어야 하며, 1e-6이 나온다면 연산 순서·자료형이 다르다는 신호다(임계 근처에서 severity를 뒤집을 수 있는 크기).
- **합성 config 증인 10종**(MT1-05d): Python F2-1~F2-4·D-26 3종과 1:1 대응. REVIEW_M0 MT0-08 승계 ②대로 **F2-2류는 프로덕션 config로 구성 불가**하므로 Kotlin도 합성 `StatemachineConfig`(예: `phases=[GREEN,LOW]`, `upgrade.LOW={distinct_axes_gte:5}`)를 쓴다.

### 8.5 engine_ref → Kotlin 대응표 (포팅·테스트 체크리스트)

| engine_ref | Kotlin 모듈 | 반드시 이식할 의미론 | 대응 테스트(필수) |
|---|---|---|---|
| `registry.load_indicator_specs` | `IndicatorRegistry` | enabled-only 기본, `optional`, `max_severity` 기본 3 | enabled 토글, 미지 id 예외 |
| `registry.parse_call_kwargs` | `TransformSpecParser` | 단어 경계(`neg_zscore` ≠ `zscore`), 괄호 깊이, **중첩 kwargs 누출 금지**(`gated(zscore(...,window=60), gate=...)`) | 활성 15지표 전건 파싱, 누출 부정 케이스, 불균형 괄호 예외 |
| `registry.parse_gate` / `parse_fallback_window` / `parse_duration` | 동 | 연산자 5종, `_20d` 추출, `m/h/d` | 각 1건 + 오류 케이스 |
| `registry._parse_hy_level_boost` / `_parse_usdkrw_intraday_force` | `ModifierRules` | **4.5·+1·max 3·1.2%·2.0%를 문자열에서 파싱**(리터럴 금지) | 파싱 결과 단언 + malformed 예외 |
| `registry.stale_window` | `StalePolicy` | 프로파일별 창, **키 부재 → daily_kr 폴백** | mobile intraday_30m → 30h 단언 |
| `registry.is_stale` | 동 | 초과만 stale(등호 fresh), naive 거부 | 경계 3점(창−1s/창/창+1s) |
| `transforms.*` 12종 | `Transforms` | **표본표준편차(ddof=1)**, `min_periods=window`→NaN, pct_change ×100, drawdown = (고점−현재)/고점×100, `gated`는 0.0 대입(결측 아님) | 각 함수 + `transforms.json` 대조 |
| `build_known_series` / `lookup_known` (L289-327) | `SeriesPipeline` | **계산 순서**(원계열 전체 변환 → 출력 행 가시성 색인 → `visible_at ≤ evaluated_at` 최신 1건), NaN·가시성 null 행 제거, 행 수 기반 창(§5.4.1) | 순서 증인(변환을 뒤로 옮기면 실패) + N행 부족 시 NaN→결측 |
| `_align_to_ffill` (L501-509) | `SeriesPipeline` | `global_corr_break` 전용: SPX 수익률을 **KOSPI 거래일 인덱스에 causal ffill** 후 상관 계산 | 서로 다른 달력 2계열 정렬 테스트 |
| `scoring.classify_severity` | `Scoring` | `>=` 등호 포함, `direction=abs`→절대값, NaN→null, **±Inf는 결측 아님** | 경계·부호·NaN·Inf |
| `scoring.is_extreme` | 동 | extreme 키 부재 → 항상 false, `>=`, direction 적용 | 부재/경계/abs |
| `scoring.combine_max_severity` | 동 | 한쪽 결측 → 남은 쪽(0 대입 아님), 양쪽 결측 → null | 3분기 |
| `scoring.compute_composite` | 동 | 결측 분자·분모 동시 제외, coverage, **유효가중 0 → score null**, **가중 누적 순서 = YAML 선언 순서** | D-23 수치 재현 + 전멸 케이스 |
| `scoring.distinct_axes` | 동 | severity ≥ 2 축 종류 수 | 중복 축 |
| `modifiers.apply_hy_level_boost` | `Modifiers` | 초과(>)만, cap, severity null → null 유지 | 경계·cap·null |
| `modifiers.apply_usdkrw_intraday_force` | 동 | `>=`, **결측 기저도 강제 승급**(crit는 null→3, warn은 max(0,2)) | 4분기 + prev_close=0 예외 |
| `statemachine.Tick/run` | `StateMachine` | D-25 §1 레벨별 스트릭·skip_levels 최고 레벨 / §2 dwell 명목=실효 / §3 null 동결(틱 미소비) / **§4 D-26 레벨-로컬·reset·RED 예외** | 증인 10종 + 프로파일 왕복 |
| `_rule_satisfied` | 동 | 인식 키 0개 → **즉시 예외**(조용한 true 금지), `or_any_extreme`은 **composite_gte만 우회**(distinct_axes_gte 유지, AD-10) | 미지 규칙 예외 + AD-10 단언 |

**연산 순서 규약**: composite 누적은 `indicators.yaml` 선언 순서(= `LinkedHashMap`)로 돈다. 부동소수 덧셈은 결합법칙이 성립하지 않으므로 이 순서를 맞추는 것이 \|Δ\| = 0을 얻는 유일한 방법이다.

### 8.6 임계 근접 리포트 (severity 뒤집힘 조기 경보)

패리티 러너는 매 틱·지표에 대해 `min|value − threshold|`를 계산해 **1e-6 미만인 지점을 전부 리포트에 열거**한다. 이 지점들은 pandas rolling(온라인 알고리즘)과 Kotlin 2-pass 계산의 마지막 비트 차이만으로도 severity가 뒤집힐 수 있는 구간이다. 지금 0건이라도, C1 재보정으로 임계가 바뀌면 되살아나므로 **리포트 항목으로 상설화**한다(GATE_GM1 증빙).

### 8.7 회귀 재실행 절차

BT-05는 1회성 게이트가 아니라 **전 phase 공통 회귀**로 편입된다(MASTER_PLAN §3-2). 재실행 트리거: `configs/*` 변경 / `engine_ref/*` 변경 / 픽스처 재생성 / Kotlin 엔진 변경. `manifest.json`의 `registry_version`과 assets 다이제스트가 불일치하면 **패리티 테스트가 스스로 실패**하게 만든다(오래된 기대값으로 통과하는 사고 방지).

---

## 9. 심화 E — 확정 틱 시각 (16:20 vs 17:00) 논증과 결정 절차

### 9.1 무엇이 걸려 있나

| 문서 | 값 | 지위 |
|---|---|---|
| `TASK_mobile_m1.md` MT1-06 / `ARCHITECTURE_SPLIT.md` §1·D-15 | **16:20 KST** | 가설(문언에 "가설·BT-03 검증 대상" 명시) |
| `backtest/replay.yaml` `profiles.mobile_daily.confirm_time_kst` | **17:00 KST** | BT-03 선정(MT0-05④, AD-3) — **M1 재확인 조건부**(AD-3b) |
| `configs/statemachine.yaml` `schedules.collection.daily_kr` | **16:50 KST** | SSOT(서버 수집 크론) |
| `configs/statemachine.yaml` `schedules.evaluation.kr_close` | **17:00 KST** | SSOT(서버 평가 틱) |

`golden_mobile.yaml`의 동결 타임라인은 `confirm_time_kst = 17:00`으로 생성됐다. M1이 16:20을 채택해도 하니스는 무감(값 동일)이라 골든은 깨지지 않지만, **SSOT 문서군에 서로 다른 숫자가 두 개 남는다**.

### 9.2 사전등록 결정 규칙 (측정 전에 고정 — 사후 합리화 차단)

MT1-00a가 **연속 3거래일 이상**, 7개 시점(15:35/16:00/16:20/16:50/17:00/17:30/18:00)에서 다음을 기록한다.

- (i) 당일 KOSPI/KOSDAQ index OHLCV 종가 존재 여부 및 **최초 관측 후 값 변경 여부**
- (ii) 당일 투자자별 순매수(외국인, KOSPI) 존재 여부 및 값 변경 여부
- (iii) VKOSPI(MDCSTAT01201) 존재 여부 및 값 변경 여부
- (iv) 응답 지연·403·로그인 만료 발생 시점

**선정 규칙(고정)**: (i)~(iii)가 **전 측정일에서 확정**(최초값 == 최종값)인 가장 이른 시점 T\*를 구한 뒤,
`확정 틱 시각 = max(T* + 30분 여유, daily_kr 수집 시각 16:50)` 이상이면서 **SSOT에 이미 존재하는 가장 이른 시각**을 채택한다. 후보가 없으면 30분 단위로 올린다. 동률이면 **기존 숫자**(17:00 = kr_close)를 택한다.

### 9.3 측정 전 권고: **17:00 KST** (근거 5)

1. **물리 하한**: KRX 정규장 마감 15:30. 그 이전 시각은 애초에 불가.
2. **SSOT 정합**: 16:20은 `daily_kr` 수집(16:50)보다 **30분 이르다** — "그날의 확정치를 모으는 시각"보다 먼저 "그날을 확정"하는 모순. 17:00은 `kr_close` 평가 틱과 같은 숫자라 SSOT에 새 숫자를 만들지 않는다.
3. **하니스 무감**: BT-03 실측에서 09:00~23:50 전 값이 비트 동일 산출 → 백테스트 관점 비용 0. (선택 근거가 물리·스케줄이지 하니스가 아니라는 점은 `replay.yaml` 주석에 이미 정직하게 기록돼 있다.)
4. **K-14와의 상호작용**: WorkManager 일일 작업은 정시가 아니다. 명목 시각을 40분 앞당겨도 실제 실행 시각은 어차피 유동적이며, "데이터가 확정 전인 시각에 실행될 확률"만 올라간다. **append-only 확정 원장에 잘못된 국면을 커밋하는 비용은 되돌릴 수 없다** — 알림 40분 지연과 비대칭이다.
5. **미국·FRED 무영향**: 두 계열은 전일 as-of이므로 16:20/17:00 어느 쪽이든 산출이 같다. 이 결정은 **오직 KR 계열의 확정성 문제**다.

**반대 논거의 정직한 기록**: 16:20의 유일한 이점은 "장 마감 후 50분 내 알림"이다. 신속 통지를 우선한다면 16:20 + "부분 확정" 표기도 가능하나, (i)~(iii) 중 하나라도 미확정이면 그 지표가 결측 처리되어 coverage가 떨어지고 §5.8의 왜곡 위험이 상시화된다. 권고하지 않는다.

### 9.4 결정의 반영 지점 (3곳 + 1)

1. 앱: WorkManager 일일 작업의 목표 시각(+ flex 창 권고 2시간) — MT1-06b.
2. 기록: `docs/journal/…_MT1-06a_confirm_time.md`(측정표 + 선정 규칙 적용 + 결론) → GATE_GM1 인용.
3. 문서 정정 제안: `TASK_mobile_m1.md` MT1-06 및 `ARCHITECTURE_SPLIT.md` §1·D-15의 "16:20" → 확정값(§15 제안 2). **SSOT(configs) 변경은 없다** — `replay.yaml`은 하니스 파라미터이며 이미 17:00이다.
4. AD-3b 이행 선언: `replay.yaml` 주석의 "M1 실제 확정 틱 설계와 동시 재확인 조건" 충족을 저널·게이트에 명시(파일 수정 없이 기록으로 이행).

### 9.5 확정 시각과 스테일 창의 상호작용 (부수 검증)

| 상황 | visible_at | 다음 틱 기준 age | 창 | 판정 |
|---|---|---|---|---|
| 당일 KR 값 | D 17:00 | 0h | 30h | fresh |
| 전일 KR 값(당일 수집 실패) | D−1 17:00 | 24h | 30h | **fresh**(carry 아님 — 실제 관측값) |
| 2일 전 KR 값 | D−2 17:00 | 48h | 30h | stale → 결측 |
| 금요일 US 종가(월요일 틱) | Mon 17:00 | 0h | 48h | fresh |
| 목요일 US 종가(월 틱, 금 결측) | Fri 17:00 | 72h | 48h | stale → 결측 |

16:20으로 바꿔도 이 표의 판정은 바뀌지 않는다(모든 age가 40분씩 이동할 뿐 창 경계를 넘지 않는다). **즉 확정 시각 선택은 스테일 거동에 영향이 없다** — 이 계산 자체를 MT1-06a 저널에 남겨 결정의 영향 범위를 좁힌다.

---

## 10. 심화 F — 프리뷰 커버리지·carry-forward 격리 (D-17·D-23)

### 10.1 계산 규격 (완료 기준 ③④의 실행 가능한 정의)

- 전체 가중 = enabled 15지표 합 = **31.0**(vol 7.0 + credit 6.5 + rates_fx 5.5 + global_price 3.5 + kr_flow 8.5).
- D-23 §23.2 시나리오: KRX계 4지표(vkospi 2.5 + kospi_drawdown 2.5 + foreign 2.0 + volume 1.5 = **8.5**) + CDS(**1.5**) 결측 → 유효 가중 **21.0** → coverage = 21.0/31.0 = **0.677419…**(67.7%)
- 프리뷰: 100 × (21.0×2)/(21.0×3) = **66.666…**(66.7)
- 서버 동시각(국내 severity 0 관측): 100 × (21.0×2 + 10.0×0)/(31.0×3) = 42/93 = **45.161…**(45.2)

**테스트 작성 규율**: 66.7·45.2·67.7을 리터럴 기대값으로 쓰지 않는다. assets `indicators.yaml`에서 로드한 가중으로 계산해 대조하고, 문서 표기값과는 소수 1자리 반올림 일치만 부가 단언한다(CLAUDE.md §1). **억제 임계 0.80도 리터럴 금지** — §15 제안 6의 `engine.preview_coverage_min`에서 로드한다.

#### 10.1.1 coverage와 carry-forward의 관계 — **raw가 정본이다** (규정)

프리뷰는 두 값을 계산할 수 있다.

| 이름 | 정의 | 용도 |
|---|---|---|
| **raw coverage** | **이월 적용 전** 유효가중 / 전체가중 = "지금 이 순간 실제로 관측된 비율" | **정본**. 화면 표시(D-23 §23.3-2)와 **억제 판정**(§23.3-3)은 전적으로 이 값 기준 |
| effective coverage | 이월 후 유효가중 / 전체가중 (이월이 성공하면 사실상 1.0) | 산출은 가능하나 **v1에서는 표시하지 않는다** — 항상 100%에 수렴해 정보량이 0이다 |

**이것이 유일하게 성립하는 해석인 이유**: carry-forward는 D-23 §23.3-1에서 "분모를 유지"하기 위한 장치다. 이월값을 유효가중에 계상하면 coverage는 이월이 성공할 때마다 100%로 회복되고, **§23.3-3의 `coverage < 80%` 억제 규칙은 영원히 발동하지 않는 죽은 조문**이 된다. 동시에 TASK MT1-07 완료 기준 ③("KR 4지표 결측 시나리오에서 coverage 67.7% 산출·판정 억제")도 재현 불가가 된다 — 67.7%는 정확히 이월 전 21.0/31.0이다. 즉 **문서가 요구하는 숫자 자체가 raw임을 지시**한다.

구현·검증 요구:
- composite 계산에는 이월값을 넣고(분모 유지 = 왜곡 방지), coverage 계산에는 **넣지 않는다**. 두 경로가 같은 지표 맵을 공유하지 않도록 자료구조를 분리한다(`observed: Map<Id, Sev?>` / `carried: Map<Id, Sev>`).
- 회귀 테스트 2건: ① 이월이 **성공한** KR 4지표 결측 시나리오에서도 raw coverage = 21.0/31.0 이고 억제가 **유지**된다 ② 이월값을 coverage에 계상하도록 변이시키면 이 테스트가 **실패**한다(퇴화 증인, 신설 규율 ①).
- 표시 문구도 raw 기준으로 통일한다: "유효 21.0 / 전체 31.0 (67.7%) · 이월 4지표".

A안(U-2)의 raw/effective 이원 산출과 **충돌하지 않는다** — 이원 산출을 채택하더라도 **억제 판정은 raw로 키잉**한다는 것이 본 계획의 요구다. 병합 시 이 조건만 보존하면 된다.

#### 10.1.2 carry-forward의 원천 — **직전 확정 틱의 동결본**(원장을 다시 읽지 않는다)

D-23 §23.3-1은 "직전 **확정**값을 이월"을 요구한다. 원천을 지정하지 않으면 프리뷰가 **자기 이전 프리뷰 값(장중 부분봉)을 이월**해, "이월은 결측 왜곡을 보정하는 장치"라는 목적이 자기참조로 무너진다.

**규정**: 이월 원천 = **가장 최근 `phase_commit`된 `tick_input` 행**(= `max(tick_date)`)의 지표별 `(severity, as_of, visible_at)`. 원장(`observation`)을 다시 조회하지 않는다.

```
carryFor(indicatorId):
  row = lastCommittedTickInput()            // tick_date 최대 1행, 없으면 null
  return row?.severities[indicatorId]       // null이면 이월 없음 → 결측 유지
```

**이 원천을 택한 이유 3**
1. **확정 전용이 구조적으로 보장된다.** `tick_input`은 확정 틱 경로에서만 쓰이고(§5.6 커밋 절차 4단계), 그 입력은 §5.4.3 ①(`origin <> 'preview'`)로 만들어졌다. 즉 **프리뷰 값이 이월 원천에 들어올 경로가 존재하지 않는다** — 필터를 "잊어서" 깨지는 종류의 방어가 아니다.
2. **이미 있는 것을 쓴다.** `tick_input.severities`는 감사·패리티 계층 분해용으로 이미 기록된다(§5.6.2 (b) 표). 새 쿼리·새 컬럼·새 테이블이 0이며, 15계열 재조회 대신 **1행 읽기**로 끝난다.
3. **fold 설계와 정합한다.** 확정 국면은 `tick_input` 전량 fold로 도출되므로(§5.6), 이월도 같은 동결본을 원천으로 삼는 편이 "무엇이 확정 사실인가"의 정의를 하나로 유지한다.

**대안 검토(병합 대비)**: 계열 단위로 `observation`을 다시 읽는 방식(A안 lastConfirmed lane)도 성립하지만, 그 경우 **`origin <> 'preview'` 필터가 본 쿼리·상관 서브쿼리 양쪽에 필수**이며 그것이 유일한 방어선이 된다(누락 시 자기참조 재발). 병합에서 그 방식이 채택되면 §5.4.3 ③행의 판별자를 그렇게 갱신하고 증인 `W-③b`를 필터 제거 변이로 바꾼다.

**이월값의 성질(명시)**: 이월되는 것은 **severity**다(원값이 아니다). composite는 severity로부터 계산되므로 이월만으로 분모 유지가 완결되고, 변환 재계산이 필요 없다. 표시에는 이월 원천 행의 `as_of`를 그대로 써서 스테일 배지를 붙인다(D-23 §23.3-1). 이월값은 **raw coverage에 계상하지 않는다**(§10.1.1).

**완료 기준(MT1-07b)**: ① `W-③a`·`W-③b`·`W-③c`(§5.4.3) green ② 이월된 지표의 표시 `as_of`가 **직전 확정 틱의 as_of**와 일치 ③ 프리뷰 2회 연속 실행 시 두 번째 이월값이 첫 번째 프리뷰 값이 아니라 **여전히 직전 확정값**임을 단언(자기참조 부재의 직접 증인).

**정합성 주의(구현자 혼동 방지)**: ③과 ④는 모순이 아니다. ④는 "규율이 없을 때 발생하는 왜곡"을 재현하는 **반례 계산**이고, ③은 그 왜곡이 실제로는 coverage 67.7% < 80%에 걸려 **판정 억제**된다는 규율 검증이다. 같은 시나리오의 서로 다른 층이다.

### 10.2 carry-forward 격리 (완료 기준 ②)

- **1차(구조)**: carry-forward 해석기는 프리뷰 use-case 안에만 존재하고, 엔진·확정 틱 모듈은 의존성 그래프상 그것을 볼 수 없다(모듈 경계 또는 패키지 가시성). 컴파일 단계 차단이 테스트보다 강하다.
- **2차(회귀 그물)**: 아키텍처 테스트 1건 — 확정 틱 패키지가 carry-forward 심볼을 참조하지 않음을 정적으로 단언. 모듈 분리를 A 관점이 채택하면 중복이지만, 모듈 재편으로 방어가 사라지는 것을 막는 값싼 보험이다.
- **3차(행위 증인)**: 확정 틱 경로에 carry-forward를 강제 주입하면 **반드시 실패**하는 변이 테스트(신설 규율 ① "퇴화 입력 증인"의 정신).
- carry-forward 값은 **Room에 기록하지 않는다**(D-23 §23.3-1). 검증: 프리뷰 전후 `observation` 행 수가 carry 대상 지표에 대해 불변.

### 10.3 프리뷰의 lake 기록과 확정 틱의 상호작용 (주의 — 발견 사항)

D-17 §3에 따라 **프리뷰 수집치도 lake에 append된다**. 두 가지 귀결이 있다.

1. `visible_at`을 MIN(observed_at)이 아니라 §5.2의 **결정적 함수**로 정의한 본 설계에서는, 프리뷰를 자주 돌려도 스테일 판정이 흔들리지 않는다 → **확정 틱의 결정론이 사용자의 프리뷰 사용 패턴과 독립**이다(함수형 정의를 택한 두 번째 이유).
2. **확정 틱은 프리뷰 출처 행을 SQL 수준에서 전면 배제한다**(라운드 4 정정 — 이전 판의 "확정 행 우선, 없으면 프리뷰 행 + 경고"를 폐기).

   **폐기 근거(경고로는 못 막는다)**: 확정 수집이 실패한 날 장중 스냅샷이 그 날의 확정치로 굳으면 오염은 **불가역**이다 — ① `observation`은 append-only라 지울 수 없고 ② 그 값으로 만든 `tick_input`은 커밋 시점에 **동결**되어(§5.6) 사후 정정이 과거 국면을 되돌리지 않으며 ③ 그 as_of 행은 이후 **최대 272행 창(≈13개월)** 동안 모든 롤링 변환(zscore·drawdown·corr)의 입력으로 계속 참여한다. `tick_run` 경고는 사후 관측일 뿐 ①②③ 어느 것도 되돌리지 못한다.

   **구현**: `observation.origin TEXT NOT NULL ∈ {'confirm','preview','backfill'}`. 확정 경로 쿼리에 `origin <> 'preview'`를 **본 쿼리와 상관 서브쿼리 양쪽에** 넣는다(§5.4.1 (2)). `backfill`(캐치업·초기 백필)은 종가 소스이므로 확정 경로가 읽는다 — 배제는 `preview`만이다.

   **D-17 §3과의 정합**: "프리뷰 수집치도 Room lake에 append한다(observed_at=now). **일일 확정 틱은 마감 기준 as-of로 읽는다** — PIT 규율(D-06) 유지". 저장은 그대로 하고(관측된 것은 기록), 확정 틱이 마감 기준으로 읽는다는 후단이 곧 이 배제다. D-17을 어기는 것이 아니라 후단을 물리적으로 강제하는 것이다.

   **귀결(정직하게)**: 확정 수집이 실패한 날 KR 지표는 **결측**이 된다(장중값으로 메우지 않는다) → coverage 하락 → §5.8 경로(재시도·표기·저커버리지 노티). 결측은 D-02의 분모 제외로 **무편향**이지만 장중값 오염은 **편향**(하루치 방향을 왜곡)이므로, 결측이 엄격히 낫다.

   **dedupe와의 상호작용**: "값이 같으면 새 행을 만들지 않는다"(§5.1)는 **같은 `origin` 안에서만** 판정한다. 그래야 프리뷰가 먼저 같은 값을 적재해도 확정 수집이 `origin='confirm'` 행을 반드시 남긴다.

   **테스트(MT1-06c·MT1-03c)**: ① 어떤 as_of에 `preview` 행만 존재하면 확정 조회 결과에 그 as_of가 **나타나지 않는다** ② `preview`와 `confirm`이 같은 as_of에 공존하면 `confirm`이 선택된다 ③ 같은 픽스처로 프리뷰 조회(필터 없음)는 그 as_of를 **본다**.

---

## 11. 골든 무회귀 연결과 회귀 게이트 정의

### 11.1 M1이 상속하는 게이트

```bash
uv run ruff check . && uv run pytest -q                 # ① 전부 green (현행 177 + M1 신설분)
uv run pytest backtest/test_golden.py -q                 # ② D-08 2케이스 × 2프로파일 (6건)
```
M1에서 Python 측에 추가되는 것은 MT1-02a(계약 스냅샷)·MT1-05e(패리티 내보내기) 테스트뿐이다. **M1은 `configs/`·`engine_ref/`를 건드리지 않으므로 ②는 무변경 통과가 기본값**이며, ②가 흔들리면 그 자체가 SSOT 무단 변경의 신호다(qa-verifier 선행 확인 항목).

### 11.2 M1이 신설하는 게이트

```bash
# JVM (CI 기본 — 기기 불요)
./gradlew check                                          # ktlint + detekt + JVM 단위테스트 + koverVerify
./gradlew koverVerify                                    # 커버리지 임계 단독 판정 (§3.2.1)
./gradlew koverHtmlReport                                # 미달 시 원인 확인용 리포트
./gradlew :engine:test --tests "*Bt05Parity*"            # BT-05 패리티 (9창 × mobile_daily)
./gradlew :engine:test --tests "*GoldenMobile*"          # golden_mobile.yaml 동결 대조
./gradlew :engine:test --tests "*VisibilityParity*"      # 가시성 함수 대조
./gradlew :app:testDebugUnitTest --tests "*SsotHash*"    # assets 해시 (파일 대 파일)
./gradlew :contracts:test                                # 계약 스냅샷 왕복 + 형상 다이제스트
uv run python backtest/export_parity.py --check          # 패리티 산출물 자기검증
uv run pytest tests/test_contracts_snapshot.py backtest/test_export_parity.py \
  --cov=backtest.export_parity --cov=scripts.gen_contract_snapshots --cov-fail-under=90
# 계측 (실기기 — GM1 증빙)
./gradlew :app:connectedDebugAndroidTest                 # assets 패키징 해시 + Room + WorkManager
```

### 11.3 실행 환경 주의(Windows)

- PowerShell은 `.\gradlew.bat …`, git-bash는 `./gradlew …`. 본 문서는 git-bash 표기다.
- 콘솔 cp949 — 출력 한글이 깨질 수 있다. **판정은 exit code로** 하고, 로그가 필요하면 파일로 리다이렉트해 UTF-8로 읽는다.
- Python 명령은 전부 `uv run` 접두사(프로젝트 규약).

### 11.4 골든과 패리티의 관계 (혼동 방지)

| 게이트 | 무엇을 지키나 | 깨졌을 때 의미 |
|---|---|---|
| ② 골든(Python) | engine_ref + 하니스가 D-08 기대 타임라인 재현 | SSOT 또는 engine_ref 회귀 |
| L5 골든(Kotlin) | Kotlin 엔진이 같은 동결 타임라인 재현 | Kotlin 포팅 결함 |
| L0~L4 패리티 | Kotlin이 9창 전 틱에서 Python과 동일 산출 | 골든이 못 잡는 경로(extreme·D-26·결측)의 결함 |

세 게이트는 서로를 대체하지 않는다. **L5만 통과하고 L0~L4가 깨지면 "골든 창에서만 우연히 같다"** 는 뜻이므로 GM1 반려 사유다.

---

## 12. 실측 선행 과업 (블로킹 관계)

| 실측 | 확정해야 할 사실 | 블록 대상 | 실패 시 대체 경로 |
|---|---|---|---|
| MT1-00a KRX | 로그인 필수 여부·성공률, 4데이터셋 가용성, **KR 확정 시각** | MT1-04c, **MT1-06a**(임계 경로), MT1-04g | 로그인 불가 시 KR 축 4지표 전면 결측 → **M1 재설계 상신**(§14 D-B1) |
| MT1-00b 야후 | 엔드포인트 유효성·as_of 규약·Stooq 폴백 | MT1-04a, MT1-04h | 차단 시 Stooq 단독 → 심볼 매핑·필드 차이 재검증 |
| MT1-00c ECOS | **item_code 2종(K-04, 현재 `VERIFY`)** | MT1-04d | 미확정 시 `krx_credit_spread_delta`(가중 2.0) 상시 결측 — coverage 29.0/31.0 |
| MT1-00d CDS | 모바일 접근 가능성 | MT1-04f | (b) 미수집 확정(권고) |
| MT1-00e KIS | 앱키 보유·토큰 발급 | MT1-04e | M2 이연 |

**중요**: MT1-00a의 확정 시각 프로파일링은 **거래일에만** 가능하다. 착수일이 금요일 이후면 임계 경로가 최대 3일 밀리므로 실측을 최우선 배치한다.

---

## 13. 리스크 × K-xx 매핑과 완화

| ID | 리스크 | K-xx | 발현 신호 | 완화(서브태스크) |
|---|---|---|---|---|
| **R-B1** | 스테일 기준 시각을 `as_of`로 구현 → 결측 낀 창 전부에서 국면 불일치 | K-05·K-07 | BT-05 L2/L4 실패가 특정 결측일에 몰림 | §5.3 규약 + `VisibilityParity`(MT1-05h) + 전용 테스트(§8.5) |
| **R-B2** | `mobile_daily`에 `intraday_30m` 키 부재 → `daily_kr` 폴백 규칙 누락(yaml에 안 보임) | — | usdkrw/vkospi/kospi_drawdown만 다르게 결측 | §5.3-2 전용 단언(MT1-05a) |
| **R-B3** | 야후 KRW=X 일봉이 KST 17:00에 미마감(BACKTEST_PLAN §5.4의 ~16h 근사) | K-01·K-18 | 다음날 같은 as_of 값 변경(revision 1) | revision 규칙(MT1-03d) 정직 기록 + `tick_input` 동결 + 한계 고지(GM1) |
| **R-B4** | Kotlin rolling이 pandas와 마지막 비트에서 갈려 임계 경계에서 severity 뒤집힘 | K-07 | L1 통과·L2만 실패 | ddof=1·min_periods 명시(§8.5) + **임계 근접 리포트**(§8.6) + L3 1e-6 관찰 기준 |
| **R-B5** | composite 누적 순서 불일치로 \|Δ\| ≈ 1e-13(통과하나 원인 불명) | K-07 | L3 Δ ≠ 0 | YAML 선언 순서 = `LinkedHashMap` 규약(§8.5) |
| **R-B6** | 캐치업이 미래 데이터를 써서 라이브와 다른 국면 커밋 | K-11 | MT1-06g 결정론 테스트 실패 | 계열종류별 as_of 컷오프(§5.4) + `is_catchup` 표기 |
| **R-B7** | M1에서 §6 잔여 FAIL 3건을 "고치려" 임계·프로파일을 만짐 | K-11 | `configs/` diff 발생 | **M1 재보정 금지**(M0 승계 ④). qa-verifier가 매 서브태스크 `git diff --stat configs/` 확인 |
| **R-B8** | KRX 자격증명이 로그·백업·CSV로 유출 | K-17 | 내보내기·로그에 ID/PW | EncryptedSharedPreferences + 백업 제외 파싱 테스트(MT1-03e) + CSV 화이트리스트 |
| **R-B9** | KRX 과호출·장중 반복 조회로 차단 | K-03 | 403/빈 응답 급증 | 1req/s 게이트 + 확정 틱 1회/일 + 프리뷰 쿨다운(§17 C-3) |
| **R-B10** | assets 구버전 패키징(빌드 배선 누락) | K-16 | 계측 해시만 실패 | 3중 검증(§7.2), 특히 ① 빌드 배선 |
| **R-B11** | WorkManager 미실행·OEM 킬 → 누락 누적 | K-14·K-15 | `tick_run` 공백 | 캐치업(MT1-06e) + 누락 이력 노출(MT1-06f) + 온보딩 안내(§17 C-1) |
| **R-B12** | 초기 백필 없이 스모크 → 전 지표 결측 동결이 "정상"으로 보임 | K-11 | 첫 틱 composite = null | MT1-04g + 스모크 절차의 "백필 완료 확인" 선행 항목(§17 C-4) |
| **R-B13** | `golden_mobile.yaml`의 `registry_version: 0.1.0` 표기로 기대값 노후 오판 | — | 패리티 리포트 버전 서술 혼선 | §15 제안 3(스탬프 정정) + 패리티 manifest에 실제 registry_version 기록 |
| **R-B14** | VKOSPI를 모바일만 실수집 → 서버·픽스처(실현변동성 폴백)와 지표 정의가 갈림 | K-02 | 동일 일자 vkospi_z가 서버와 크게 다름 | §14 **D-B7** 결정 필요. 패리티(픽스처 주입)는 무영향이나 **실운영 동등성**에 영향 |
| **R-B15** | 커버리지 임계를 통과시키려 자체 로직을 제외 목록에 넣음(게이트 무력화) | — | `kover` 제외 목록 증가 | 제외는 **생성 코드·벤더 코드만**(§3.2.1, UI 제외 없음). 제외 목록 변경은 커밋 리뷰 대상이며, 신규 제외는 aaa-critic이 아니라 **사용자 결정 사항**으로 상신한다 |
| **R-B16** | 벤더링한 krxkt가 상류와 조용히 갈라짐(K-16과 동형 위험) | K-16 | 상류 수정이 반영되지 않음 | `VENDOR.md`에 상류 커밋 해시 기록 + 로컬 패치 목록 유지. 상류가 완료·동결 상태라 위험은 낮으나 **기록 없는 수정은 금지** |
| **R-B23** | carry-forward가 직전 **프리뷰** 값을 이월 → 이월 장치가 자기참조로 무너짐(D-23 §23.3-1 위반) | K-11 | 프리뷰 반복 실행 시 이월값이 계속 갱신됨 | 원천 = 직전 확정 `tick_input` 동결본(§10.1.2) — 구조적 면역 + 증인 `W-③a~c`(§5.4.3) |
| **R-B24** | 프리뷰 조회의 tie-break가 `observed_at` 우선이라 **확정 종가 대신 자기 장중값**을 봄(17:00 이후) | — | 저녁 프리뷰가 종가와 다른 값 표시 | `(origin='preview') ASC` 선두 정렬(§5.4.1 (2)) + 증인 `W-②a·W-②b` |
| **R-B20** | 확정 수집 실패일에 프리뷰 장중값이 종가로 굳음 → **불가역 오염**(append-only·`tick_input` 동결·272행 창 잔류) | K-11 | 그 날 확정 국면이 장중 스냅샷 기반 | `origin <> 'preview'`를 **SQL 양쪽에** 적용(§5.4.1 (2)·§10.3-2) + 배제 3케이스 테스트. 경고 로그로 대체하지 않는다 |
| **R-B21** | 조회가 SQLite 윈도 함수(3.25+)에 의존 → 일부 기기에서 런타임 실패 | — | 특정 기기에서만 조회 예외 | **가정 제거**: 상관 서브쿼리로 전환(§5.4.1 (2)) — 실측 선행 과업 불요 |
| **R-B22** | 프리뷰가 격자 시각으로 가시 판정 → US·FRED 7지표 상시 결측(coverage ≈53%)으로 억제 영구 발동 | — | 프리뷰가 언제나 "국면 판정 불가" | 가시 판정을 날짜로 분리(§5.2 말미) + 프리뷰 시각 규약(§5.4.2) + 완료 기준 ①로 고정 |
| **R-B18** | 확정 틱이 "계열별 최신 1행"만 읽어 롤링 창을 못 채움 → 백필했는데도 **전 지표 결측·동결**(조용한 실패) | K-11·K-07 | 첫 틱 composite = null인데 lake에는 데이터가 있음 | §5.4.1 조회 계약(행 수 파생·N행 쿼리) + MT1-03c 완료 기준 + MT1-06c 워밍업 미충족 시나리오 테스트 |
| **R-B19** | 변환을 "선택된 1행"에만 적용(필터-후-변환 오구현) → 값이 정본과 다름 | K-07 | BT-05 L1/L2 실패, L0는 통과 | §5.4.1 (3) 순서 규정 + **순서 증인 테스트**(MT1-05b) + 꼬리 절단만 허용된다는 근거(§5.4 동치 증명) |
| **R-B17** | 2계열 지표를 단일 계열 규칙으로 구현 → `global_corr_break`가 하루 앞서 보임(look-ahead) | K-11 | L0 실패, 또는 L0를 안 짰다면 L2/L4에서 특정 지표만 어긋남 | `combinedVisibleAt` **단일 구현**(분기 없음, §5.2) + §5.2 매핑표를 코드가 아니라 표에서 가져오기 + MT1-05h 필수 케이스 3종 |

---

## 14. 미해결 결정 목록 (Advisor·사용자 상신용 — 권고안 포함)

| ID | 결정 사항 | 선택지 | **권고** | 필요 시점 |
|---|---|---|---|---|
| **D-B1** | **확정 틱 시각** — 16:20 vs 17:00 (AD-3b 이행) | (a) 17:00 (b) 16:20 (c) 실측이 지시하는 더 늦은 시각 | **(a) 17:00** — §9.3 근거 5. 단 MT1-00a 실측이 §9.2 규칙으로 (c)를 지시하면 (c) | MT1-06 착수 전 |
| **D-B2** | **캐치업의 PIT 엄밀도** — 놓친 날 D를 D+2에 계산할 때 `observed_at ≤ D` 필터를 걸 것인가 | (a) as_of 컷오프만(값은 사후 수집분 = 근사-PIT, `is_catchup` 표기) (b) observed_at까지 엄격 필터 | **(a)** — (b)는 그 날 아무것도 관측되지 않았으므로 전 지표 결측 → D-25 §3 동결이 되어 **캐치업이 무의미**해진다. (a)는 M0 픽스처와 동일한 "근사-PIT" 지위이며 표기로 정직성을 확보한다 | MT1-06e 착수 전 |
| **D-B3** | **확정 틱 저커버리지 처리** — KRX 수집 실패로 coverage 72.6%인 확정 틱이 국면을 커밋해도 되는가(§5.8) | (a) 현행 유지(D-02대로 분모 제외) + 표기·재시도 강화 (b) 확정 틱 coverage 하한(<80% 등) 도입 → 동결 | **(a) — M1에서는 의미론 무변경**. (b)는 엔진 거동 변경이라 골든·BT-05·D-25 §3 정의에 파급된다. 필요 시 C1에서 실측 기반 판정 | MT1-06c 착수 전 |
| **D-B4** | **G-4 CDS** — 수집 구현 vs 미수집 확정 | (a) 모바일 스크래핑 구현 (b) 미수집 확정 + UI "미수집" 배지 | **(b)** — `optional:true`라 composite 무왜곡, HTML 스크래핑은 상시 파손 경로(K-18류)를 하나 더 만든다. 단 MT1-00d 실측에서 **안정적 JSON 엔드포인트가 확인되면 (a) 재고** | MT1-04f |
| **D-B5** | **KIS(e)** — M1 구현 vs M2 이연 | (a) M1 구현(플래그 off 기본) (b) M2 이연 | 사용자가 앱키를 이미 보유하면 **(a) 최소 구현**(토큰+지수·환율 1엔드포인트), 미보유면 **(b)**. 프리뷰의 KR 실시간은 KIS 없이도 KRX 장중 조회로 부분 대체 가능하나 K-03 상한을 소모한다 | MT1-04e |
| **D-B6** | **계약 와이어 필드명** — `schema` vs `schema_id` | (a) `by_alias=True` → `"schema"` (b) 필드명 그대로 `"schema_id"` | **(a)** — LLM 구조화 출력(`model_json_schema()`)이 alias를 쓰고 문서 문언도 `schema: scenario-snapshot/1`이다. 저장 직렬화도 (a)로 통일해 두 표현을 없앤다. **contracts 코드 변경 없이 스냅샷 규약으로 고정 가능** | MT1-02a |
| **D-B7** | **모바일 VKOSPI 실수집 여부** — `kotlin_krx.getVkospi()`(MDCSTAT01201)로 **실제 VKOSPI 취득이 가능해 보인다**. 반면 서버(pykrx 현물 인덱스 경로)는 조회 불가로 `realized_vol_kospi_20d` 폴백이 확정(K-02)이고, M0 픽스처·골든도 폴백 기반이다 | (a) 모바일도 폴백 통일(서버·픽스처와 동일 정의) (b) 모바일은 실 VKOSPI 사용(정의가 갈림) (c) 실 VKOSPI 수집·저장은 하되 v1 지표 계산은 폴백 사용, C1에서 일괄 전환 | **(c)** — 데이터는 지금부터 쌓되(나중에 소급 불가한 자산) 지표 정의는 서버·골든과 통일 유지. (b)는 D-23 §23.5 "동일 규칙" 보장을 깨고 INT 게이트(일치율 ≥90%)를 선제적으로 위협한다 | MT1-04c 착수 전 |
| **D-B8** | **패리티 범위** — 골든 2창만 vs 9창 전체 | (a) 골든 2창(계약 문언 최소) (b) 9창 전체 + 합성 증인 | **(b)** — §8.2. 골든에서 `or_any_extreme` 발화 0회이므로 (a)는 이번 phase의 채택 기능을 미검증으로 남긴다 | MT1-05f 착수 전 |
| **D-B10** | **프리뷰 coverage의 이월 계상 방식** — 억제 판정을 무엇으로 키잉하는가 | (a) **raw(이월 전)** 단일 값 (b) raw/effective 이원 산출, 억제는 raw로 키잉(A안 U-2) (c) effective로 키잉 | **(a), (b)도 수용** — 두 안은 억제 조건이 동일하다. **(c)는 배제**: 이월 성공 시 coverage가 100%로 회복돼 D-23 §23.3-3이 죽은 조문이 되고 TASK 완료 기준 ③(67.7%)이 재현 불가다(§10.1.1). 병합 시 보존해야 할 불변식 = "**억제는 raw로 키잉**" | MT1-07c 착수 전 |
| **D-B11** | **억제 임계 0.80의 SSOT 위치** | (a) `configs/indicators.yaml` `engine.preview_coverage_min` (b) `configs/statemachine.yaml` (c) 앱 로컬 상수 | **(a)** — §15 제안 6. `missing_data_policy`·`stale_profiles`와 같은 "엔진 공통 규칙" 계열이고 D-23이 composite 산출과 직결된다. (b)는 전이 조건 파일인데 프리뷰는 전이를 만들지 않아 부적합, (c)는 CLAUDE.md §1 위반 | **MT1-07 착수 전(필수)** |
| **D-B12** | **캐치업 소급 상한 20거래일의 SSOT 위치** | (a) `statemachine.yaml` `profiles.mobile_daily.catchup_max_ticks` (b) 앱 로컬 상수 | **(a)** — §15 제안 7. 틱 단위 카운트이고 프로파일별로 달라질 값이라 프로파일 파라미터와 동거하는 것이 맞다. 스키마 테스트가 `issubset` 방식이라 키 추가는 무해(실측 확인) | MT1-06e 착수 전 |
| **D-B13** | **절단 구간의 카운터 처리** — 공백 후 스트릭·cooldown을 리셋할 것인가 | (a) **리셋 없음**(엔진 무변경, 공백 = 존재하지 않는 틱) (b) 리셋 도입(`engine_ref.statemachine.run`에 시작 상태 주입 — C안 P-12/U-6 경로) | **(a)** — §5.6.2. 근거: ① `run()` API로 (b)는 표현 불가라 반드시 엔진 변경이 따르고 ② mobile_daily `promote_sustain_ticks = 1` 때문에 리셋이 막으려던 "과거 스트릭에 의한 조기 승격"이 **원리적으로 발생하지 않으며** ③ 잔여 영향은 ≤2틱(강등 조기화·승격 지연)으로 2영업일 내 소멸하고 ④ 25거래일 공백은 엔진 관점에서 긴 휴장과 동형이라 골든이 이미 그 의미론 위에서 동결돼 있다. **(b) 채택 시 필수 동반**: 골든 2×2 재확인 + BT-05 기대값 재생성 + D-25 §3 상호작용 분석 | MT1-06e 착수 전 |
| **D-B9** | **KRX 자격증명 보관** — 앱이 KRX ID/PW를 보관해야 한다(kotlin_krx는 전 호출에 로그인 요구) | (a) 온보딩 입력 + EncryptedSharedPreferences (b) 매 실행 사용자 입력(자동 틱 불가) | **(a)** — (b)는 일일 자동 틱이라는 시스템 목적과 충돌. 단 K-17 규율(로그·백업·CSV 제외) 전건 테스트 필수 + 동시 로그인(CD011)로 PC 세션이 끊길 수 있음을 온보딩에 고지 | MT1-04c 착수 전 |

---

## 15. SSOT·문서 변경 제안 (직접 수정 금지 — 제안만)

| # | 대상 | 제안 내용 | 근거·조건 |
|---|---|---|---|
| **1** | `configs/indicators.yaml` `krx_credit_spread_delta.source.item_codes` | `corp_aa3y: VERIFY`, `ktb_3y: VERIFY` → **실측 코드값**으로 교체 | K-04 미해소 상태. MT1-00c 실측 완료 후 Advisor가 허가 범위를 브리프에 명시해 반영. **본 계획은 수정하지 않는다** |
| **2** | `TASK_mobile_m1.md` MT1-06 / `docs/ARCHITECTURE_SPLIT.md` §1·D-15 | "16:20 KST" → D-B1 확정값 + "(AD-3b 재확인 완료, 근거: MT1-06a 저널)" 부기 | 문서-문서 불일치 해소. SSOT(configs) 무변경 |
| **3** | `backtest/golden_mobile.yaml` 머리 주석 | `registry_version: 0.1.0` 스탬프에 "0.1.0 생성 이후 0.3.0-rc·0.3.1-rc에서 타임라인 **불변 확인**(MT0-05·MT0-08)" 부기 또는 스탬프 갱신 | 현행 스탬프는 3세대 이전 값이라 패리티 리포트 독자를 오도한다(R-B13). 값 변경이 아니라 출처 표기 정정 |
| **4** | 저장소 루트 `.gitattributes`(신규) | `*.yaml -text`, `*.md -text`(또는 `* text=auto eol=lf`) | 다이제스트의 머신 간 재현성(§7.3). SSOT 파일 내용 무변경 |
| **5** | `configs/sources.yaml` `pykrx.notes` / `stooq`·`kis` 항목 | MT1-00a·00b·00d 실측 결과 부기(모바일 kotlin_krx 경로의 로그인 필수 사실, Stooq 심볼 매핑, CDS 판정) | K-02·K-04와 동일한 "실측 결과를 sources.yaml에 기록" 규율의 연장. **MT1-04f·D-B7 결정 확정 후** |
| **6** ★ | `configs/indicators.yaml` `engine:` 블록 — **키 신설** | 아래 패치안. **MT1-07 착수 전 필수**(없으면 착수 즉시 CLAUDE.md §1 위반) | D-23 §23.3-3의 임계 0.80이 현재 SSOT 어디에도 없다. D-B11 |
| **7** ★ | `configs/statemachine.yaml` `profiles.mobile_daily` — **키 신설** | `catchup_max_ticks: 20` + 근거 주석(§5.6.1) | 캐치업 상한이 스트릭 리셋 거동을 좌우한다(D-B12). `load_statemachine`은 명시 키만 읽으므로 추가 키는 무시 — Python 거동 변화 0 |
| **8** | `contracts/snapshot.py`·`contracts/evidence.py` | `datetime` → `AwareDatetime`(pydantic) 3필드 | K-05 규율의 계약 계층 적용. `model_json_schema()`는 `format: date-time`으로 **불변**이라 LLM 구조화 출력 영향 0. **M1 게이트 비의존**(채택 시 §6.2.1의 asymmetric → invalid 승격) |

**제안 6 패치안** (그대로 적용 가능한 형태 — 본 계획은 적용하지 않는다):
```yaml
# configs/indicators.yaml — engine: 블록 말미
engine:
  missing_data_policy: exclude_from_denominator
  preview_coverage_min: 0.80    # D-23 §23.3-3 — 프리뷰 전용. raw coverage(이월 전
                                # 유효가중/전체가중)가 이 값 미만이면 composite 흐림 처리 +
                                # "국면 판정 불가" + 잠정 경보 억제. 확정 틱에는 적용하지 않는다
                                # (D-23 §23.3-4). 서버는 프리뷰가 없어 소비자 없음.
```
- **영향 분석**: `engine_ref`는 이 키를 읽지 않는다(서버에 프리뷰 없음) → Python 거동 변화 0, 골든 무회귀 0 영향. `tests/test_configs_schema.py`에 존재·범위(`0 < x <= 1`) 가드 1건 **추가 제안**(테스트 파일 변경도 제안 대상으로 함께 상신). 모바일은 assets 사본에서 로드(§7.1 복사 대상에 이미 포함).
- **대안 검토 기록**: `statemachine.yaml`에 두면 "전이 조건" 파일에 전이를 만들지 않는 규칙이 섞이고, `profiles.*` 하위에 두면 프로파일 무관 규율을 프로파일에 종속시킨다 — 둘 다 부적합.

> ★ 표시 2건(6·7)은 **해당 서브태스크 착수의 선행 조건**이다. 나머지는 기록만 한다. 실제 편집은 해당 서브태스크에서 Advisor가 TASK 허가 범위를 브리프에 명시한 뒤 수행하며, 편집 후 `uv run pytest -q` + `backtest/test_golden.py` 재실행이 의무다(제안 1은 골든에 영향 없음 — 해당 지표는 픽스처 미수집이라 상시 결측).

---

## 16. 커밋 단위·운영 규약

### 16.1 커밋 단위 (`m1-xx:` 프리픽스, 서브태스크 1:1)

```
m1-00: krx/yahoo/ecos/cds probe results (measurement journals)
m1-01: android scaffold + gradle check baseline
m1-01b: syncConfigs task + assets manifest
m1-01c: wire syncConfigs into build (drift block)
m1-01d: ssot hash jvm test
m1-01e: ssot hash instrumented test (packaging)
m1-01f: kover coverage gates wired into check
m1-01g: vendor krxkt sources (upstream commit pinned in VENDOR.md)
m1-02a: contract snapshot generator + python round-trip tests
m1-02b: kotlin contract mirror (kotlinx.serialization)
m1-02c: kotlin snapshot round-trip + shape digest
m1-03a: room append-only schema + insert-only dao
m1-03b: physical append-only enforcement (update/delete triggers)
m1-03c: as-of queries (per-kind cutoff, deterministic tie-break)
m1-03d: revision-on-change append (idempotent re-collection)
m1-03e: csv export + backup exclusion rules
m1-03f: tick_input / phase_commit / tick_run tables
m1-04a: yahoo rest collector + stooq fallback
m1-04b: fred collector (missing '.' mapping)
m1-04c: krx collector via kotlin_krx (login, rate limit)
m1-04d: ecos collector (verified item codes)
m1-04e: kis collector (optional, disabled by default)
m1-04f: kr_cds decision (G-4)
m1-04g: initial backfill (550d warm-up, resumable)
m1-04h: collector-fixture cross-check harness
m1-05a: kotlin config loader + transform/modifier string parser
m1-05b: kotlin transforms (ddof=1, min_periods)
m1-05c: kotlin scoring + modifiers
m1-05d: kotlin statemachine (D-25 s1-4, D-26 pairing)
m1-05e: python parity export (backtest/export_parity.py)
m1-05f: BT-05 parity runner (9 windows x mobile_daily)
m1-05g: golden_mobile frozen timeline check
m1-05h: visibility function parity
m1-06a: confirm-time decision memo (AD-3b closure)
m1-06b: trading-day grid + workmanager daily job
m1-06c: confirm tick pipeline (freeze inputs, full fold commit)
m1-06d: idempotency + duplicate-run guard
m1-06e: catch-up (ordered per-trading-day commits)
m1-06f: tick_run history + missed-tick surfacing
m1-06g: confirm tick determinism (live == catch-up)
m1-07a: preview pipeline (no commit, PREVIEW badge)
m1-07b: carry-forward isolation (structure + arch test)
m1-07c: coverage computation + suppression under 80%
m1-07d: D-23 numeric example reproduction
m1-08a: notification channels (3)
m1-08b: functional home screen
m1-08c: manual e2e checklist + smoke runbook
```

### 16.2 서브태스크 완료 정의 (전건 공통)

`qa-verifier`(기계 검증) → `aaa-critic`(루브릭 §2) **2단 PASS**. 그 전에 PROGRESS 체크 금지(CLAUDE.md §1).

### 16.3 Worker 브리프 공통 규약 (REVIEW_M0 신설 규율 4건의 편입)

모든 M1 브리프에 다음을 **복사해 넣는다**.
1. 완료 보고에 **`git status --porcelain` 원문**과 게이트 명령 실행 출력(마지막 줄)을 그대로 포함할 것.
2. 파생 수치를 리포트에 쓰면 **퇴화 입력 증인 테스트**(빈 분모·전멸·단일 계열)를 함께 낼 것.
3. 결측 원인 귀속을 서술하면 **같은 kind 형제 계열의 당일 관측 증거**를 첨부할 것.
4. qa-verifier는 판정 전에 **보고-저장소 일치**를 먼저 확인할 것.
추가(M1 고유): 5. `git diff --stat configs/ contracts/ prompts/` 가 **비어 있음**을 보고에 포함할 것(허가된 서브태스크 제외).

### 16.4 병렬 위임 상한

동시 위임 6건 이하를 권고한다(§4의 3차 묶음). 근거: 서브태스크 간 공유 파일이 `settings.gradle.kts`·버전 카탈로그·`libs.versions.toml`에 집중돼 있어 그 이상은 병합 충돌 처리 비용이 병렬 이득을 잠식한다. 레인 D(엔진)·E(하니스)·F(수집)는 파일 충돌면이 거의 없어 우선 병렬화 대상이다.

---

## 17. 타 관점 최소 요구사항 (비워두지 않기)

### A — 아키텍처·의존성 (B가 의존하는 최소선)

- **A-1 모듈 경계**: `:engine`(순수 JVM, Android 비의존 — §8.1의 전제), `:contracts`(순수 JVM), `:lake`(Room), `:collectors`, `:app`. `:engine`이 Android에 의존하면 BT-05가 계측 테스트로 밀려 회귀 주기가 무너진다 — **B 관점의 하드 요구**.
- **A-2 kotlin_krx 통합 → 벤더링(소스 임포트) 권고, 컴포지트 빌드는 조건부**
  - 현황(실측): `D:\android_2025\kotlin_krx`는 순수 JVM 라이브러리(`kotlin("jvm") 2.1.0`, Java 17, `api` OkHttp 4.12·coroutines / `implementation` Gson·kotlinx-datetime), 자체 git 저장소, `version = 1.0.0-SNAPSHOT`, `PROGRESS.md` = **ALL PHASES COMPLETE**(유지보수 중단 상태), `settings.gradle.kts`에 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, `local.properties`에 KRX 자격증명(통합테스트 전용).
  - **권고: `mobile/third_party/krxkt/`로 소스 벤더링**(`src/main`만) + `VENDOR.md`에 상류 커밋 해시·임포트 일시·미포함 항목 기록. 근거 4: ① 저장소 밖 절대경로 의존이 사라져 **깨끗한 클론에서 그대로 빌드**된다(재현성의 근본 해법) ② 상류가 완료·동결 상태라 벤더링의 통상 비용(업스트림 추종)이 거의 0이다 ③ 이 저장소는 이미 "단일 저장소 SSOT + 해시로 드리프트 차단"이 지배 규율이라(K-16) 외부 경로 의존이 그 규율과 어긋난다 ④ `1.0.0-SNAPSHOT`은 애초에 핀할 수 있는 좌표가 아니다.
  - 벤더링 시 필수 조치: (i) 모듈은 `kotlin("jvm")` + `jvmTarget 17` 유지, `:collectors`만 의존 (ii) **버전 카탈로그로 OkHttp·coroutines 버전을 앱과 정렬**(`api` 노출이라 충돌 시 앱이 이긴다) (iii) 커버리지 제외 대상(§3.2.1) (iv) ktlint/detekt 제외(상류 코드 스타일 강제 금지) (v) `local.properties`·통합테스트·자격증명 파일은 **임포트하지 않는다**(K-17) (vi) 상류 대비 로컬 수정이 생기면 `VENDOR.md`에 패치 목록으로 남긴다.
  - **컴포지트 빌드(`includeBuild`)를 택할 경우 반드시 갖춰야 할 재현성 조건 4**(하나라도 빠지면 채택 불가): ① 경로는 절대경로가 아니라 **`mobile/third_party/kotlin_krx` 서브모듈**(또는 subtree)로 저장소 안에 두어 커밋으로 핀할 것 ② 앱 Kotlin 버전 ≥ 2.1.0(소비자 메타데이터 하위호환 방향) 및 Java 17 정렬 ③ 포함 빌드는 **자체 `settings.gradle.kts`의 저장소 설정을 그대로 유지**하므로 루트의 `dependencyResolutionManagement`가 적용되지 않는다는 점을 문서화(포함 빌드의 `FAIL_ON_PROJECT_REPOS`는 루트와 충돌하지 않지만, 루트에서 저장소를 중앙집중 관리한다는 가정이 깨진다) ④ CI/신규 클론에서 `git submodule update --init` 없이 빌드가 실패함을 README·게이트 절차에 명시.
  - 공통: Android(minSdk 29)는 Java 17 바이트코드·`java.time`(API 26+)을 그대로 쓰므로 desugaring 불요. Gson과 kotlinx.serialization 공존은 계층 분리로 무해(계약 직렬화는 kotlinx 전용).
  - **판정 주체**: 최종 선택은 A 관점·Advisor 소관이다. B 관점의 요구는 하나 — **깨끗한 체크아웃에서 `./gradlew check`가 성공할 것**(재현성). 벤더링은 이를 무조건 만족하고, 컴포지트는 위 4조건을 모두 갖출 때만 만족한다.
- **A-3 버전 카탈로그·핀**: `libs.versions.toml` 단일 소스. AAA §2.3 "의존성 버전 핀" 대상.
- **A-4 아키텍처 테스트**: carry-forward 격리(§10.2), `:engine`의 Android import 0건, `:lake` 외부에서 DAO 직접 접근 0건.
- **A-5 CI**: `check`는 JVM만. `connectedDebugAndroidTest`는 별도 타깃(GM1 증빙 시 수동 실행).

### C — UX·운영·실패경로 (B의 데이터 규율이 UI에 요구하는 것)

- **C-1 온보딩**: API 키 4종(FRED/ECOS/(KIS)/KRX ID·PW) 입력 + **OEM 절전 예외 등록 안내**(K-15) + v1 한계 고지(G-1~G-5, D-22 §22.3).
- **C-2 상시 표기**: `as_of`·`coverage`·스테일 배지·`PREVIEW` 배지·마지막 틱 시각. coverage < 80% 프리뷰는 흐림 + "국면 판정 불가"(D-23 §23.3-3).
- **C-3 실패 경로 전수**(AAA §2.2): 네트워크 단절 / 부분 결측 / 키 미설정·오류 / KRX 로그인 실패·세션 만료 / 쿼터·레이트리밋 / 중단 후 캐치업 — "빈 화면·무한 스피너·조용한 실패" 금지. 프리뷰는 쿨다운(K-03 예산 보호).
- **C-4 실기기 스모크(GM1 증빙)**: ① 초기 백필 완료 확인(15지표 × ≥252거래일) ② 확정 틱 1회(노티·`phase_commit` 1행·`tick_run` 성공) ③ 프리뷰 3회(국면 불변·PREVIEW 배지·coverage 표기) ④ 이중 실행 시도 → 무해 ⑤ 강제 종료 후 캐치업 1일 ⑥ 계측 테스트 green. 각 단계 스크린샷·DB 덤프를 증빙으로 수집.
- **C-5 노티 정책**: 캐치업은 최근 1건만 사용자 노티, 잠정 경보는 억제 상태에서 미발신(§5.6·§10.1).

---

## 18. 한 줄 요약

M1의 데이터 정합성은 **"원계열 조회 계약 1개(§5.4.1) + 가시 시각 함수 1개 + as_of 컷오프 1개 + 전량 fold 1개"** 로 수렴한다. 이 셋을 명시적 규약으로 못 박으면 PIT·멱등·캐치업·패리티·골든 무회귀가 각각의 장치 없이 동시에 성립하고, 못 박지 않으면 결측이 낀 창마다 서로 다른 방식으로 어긋난다. BT-05는 그 셋이 실제로 같은지를 9창 × 6계층으로 증명하는 장치다.

