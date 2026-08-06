# TASK_mobile_m1 — 모바일 코어 (M1)

- 선행: GM0 승인(레지스트리 0.3.0 확정) · 게이트: **GM1** · 위치: `mobile/` (모노레포 내 Android 프로젝트)
- 스택: Kotlin · Compose · Room · WorkManager · Retrofit/OkHttp · kotlinx.serialization · snakeyaml · minSdk 29
- 착수 전 plan council 필수(AAA_QUALITY_STANDARD §3). 아래 서브태스크는 council이 세분화·보강할 수 있으나 축소는 불가.
- **plan council 확정(2026-08-07)**: 7라운드 4안(A~D) 전원 PASS(docs/reviews/REVIEW_M1.md).
  실행 상세의 정본은 **`docs/plans/M1_PLAN_FINAL.md`**(병합 결정·정규 참조 매핑·서브태스크
  합집합 — 본 문서를 축소 없이 세분화·보강). 실측 선행 묶음 MT1-00, 신설 서브항목
  (01f·01g·02d·03c·04g·04h·05b2·05k·06h·07e·08c·08d)이 추가됐다. 확정 틱 16:20 표기는
  가설이며 17:00 채택 + MT1-00g 실측 확정 조건부(AD-3b). 참고: registry 현행은 0.3.1-rc
  (GM0 후속 결정 — 0.3.0 표기는 그 이전 기준).

## 서브태스크

### MT1-01 스캐폴드 + SSOT 동기화
Gradle(버전 카탈로그, ktlint+detekt, JVM 단위테스트/계측테스트 소스셋), CI 스크립트(`./gradlew check`).
`syncConfigs` task: 루트 configs/·prompts/ → assets 복사, 계측 테스트가 SHA-256 일치 검증(K-16).
완료: `./gradlew check` green, 해시 테스트 green.

### MT1-02 계약 미러 + 스키마 스냅샷
contracts(pydantic) → kotlinx.serialization 데이터클래스. 공유 스냅샷 파일 기준 직렬화 왕복 테스트(Python 테스트와 동일 스냅샷 참조 — 동결 일치).
완료: 스냅샷 테스트 양측 green.

### MT1-03 Room append-only lake
observed_at/as_of/revision 스키마, **update/delete DAO 미구현으로 물리 강제**, as-of 조회 쿼리, 일 1회 CSV 내보내기 + Drive 백업 훅(설정 화면에서 on/off).
완료: append-only 강제 테스트(갱신 시도 컴파일 불가 or 예외), as-of 정렬 테스트 green.

### MT1-04 collectors 5종 (병렬 위임 가능: a~e)
a) 야후계 REST(VIX/VIX3M/MOVE/SPX/DXY/UST/KRW=X) — 엔드포인트 실측·폴백(Stooq) 확정은 `data-verifier` 선행(K-01)
b) FRED(HY OAS, T10Y2Y) c) KRX via kotlin_krx(지수·수급·VKOSPI, K-03) d) ECOS(item_code 실측 K-04) e) KIS(옵션, 프리뷰 실시간 — TinyOscillator 검증 자산 재사용).
f) **kr_cds_5y_delta 판정(G-4)**: 서버는 `scrape_wgb`로 수집하나 모바일 경로가 없다. `data-verifier`가 모바일에서의 접근 가능성을 실측한 뒤 (a) 수집 구현 또는 (b) 미수집 확정 중 하나를 Advisor에 상신한다. (b)인 경우 UI에 "미수집" 배지를 노출하고 credit 축 발화 표면 축소를 GATE_GM1에 기록한다.
공통: Retrofit 어댑터 플러그인 구조, 실패는 지표별 결측 처리(전체 틱 실패 금지), 테스트는 픽스처 기반 네트워크 금지.
완료: 어댑터별 파싱·오류 경로 테스트 green.

### MT1-05 Kotlin 엔진·상태기계
engine_ref와 동일 명세(assets configs 로드, mobile_daily 프로파일). Double 고정, KST는 `java.time` zone 명시(K-05).
완료: 단위 테스트 + **BT-05 패리티**(픽스처 주입: |Δcomposite| ≤ 0.05, 국면 타임라인 일치, golden_mobile 일치) green.

### MT1-06 일일 확정 틱 + 캐치업
WorkManager 일일 작업(16:20 KST, 프로파일 설정값) → 수집→append→엔진→국면 커밋→노티. 멱등(동일 일자 재실행 무해), 놓친 날은 앱 실행 시 캐치업(순서대로 커밋). 실행 이력 화면용 로그 테이블.
완료: Robolectric/계측 테스트 — 정상·중단 후 캐치업·이중 실행 시나리오 green.

### MT1-07 프리뷰 (D-17)
수동 갱신 파이프라인: 병렬 수집(+KIS) → 잠정 계산 → PREVIEW 배지·as_of 표기. 국면 비커밋, LLM 호출 없음.
**D-23 커버리지 규율 구현**: 결측 지표는 직전 확정값 carry-forward(분모 유지, Room 미기록, 스테일 배지) → coverage=유효가중/전체가중 계산 → coverage<80%면 composite 흐림 처리·"국면 판정 불가"·잠정 경보 억제. carry-forward는 프리뷰 전용 코드 경로로 분리해 확정 틱이 절대 호출할 수 없게 한다.
완료: ① 프리뷰가 상태기계 상태를 변경하지 않음 ② 확정 틱 경로에서 carry-forward 호출 불가(아키텍처 테스트) ③ KR 4지표 결측 시나리오에서 coverage 67.7% 산출·판정 억제 ④ D-23 §23.2 수치 예(66.7 vs 45.2) 재현 테스트 — 전부 green.

### MT1-08 로컬 노티·기본 화면(기능판)
국면 전이·잠정 경보·틱 실패 노티 채널 3종. 임시 홈 화면(국면·composite·상위 지표·마지막 틱 시각) — 디자인 완성은 M2, 여기서는 기능 검증용.
완료: 노티 트리거 테스트, 수동 E2E 체크리스트(문서화) 통과.

## 완료 기준 (GM1)
`./gradlew check` + JVM/계측 테스트 전부 green / BT-05 패리티 green / 해시·스냅샷 테스트 green /
실기기 1일 스모크(확정 틱 1회 + 프리뷰 3회 정상) / aaa-critic 전 항목 PASS / GATE_GM1.md + 사용자 승인.

## 함정 (이 phase 신규: K-14~K-18 — CLAUDE.md §3에 편입)
K-14 WorkManager 일일 작업은 정시가 아니다(지연 허용·캐치업 설계가 정답). K-15 OEM 절전 예외 안내를 온보딩에.
K-16 assets 드리프트(syncConfigs+해시). K-17 API 키는 Keystore, 로그·백업 유출 금지. K-18 Yahoo 비공식(폴백 필수).
