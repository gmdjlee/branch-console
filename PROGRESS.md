# PROGRESS — branch-console (v3 이원 트랙)

세션 시작 시 읽는 순서: 본 파일 → docs/MASTER_PLAN.md → 현재 phase TASK → CLAUDE.md.
규칙: aaa-critic PASS 없는 항목 체크 금지. 게이트는 리포트 + 사용자 승인 후에만 통과 표기.

## 완료 이력
- [x] P0 설계 (2026-07-19): 레지스트리 15+2 / 상태기계 / 소스 / 계약 2종 / D-01~D-10 / 골든 / HW
- [x] 전 phase 문서화 v2: MASTER_PLAN, TASK P1·C1·P2·AL·P3·P4, 프롬프트·토픽·시드, 런북, 에이전트 3종
- [x] 정합성 점검 2026-08-01 (CONSISTENCY_AUDIT_2026-08.md, D-11~D-14) + 검증 백테스트(A/B/C)
- [x] v3 계획 수립 2026-08-02: 이원 시스템(D-15~D-21), 모바일 우선, BT 하니스, AAA 체계 (본 번들)

## Track M — 모바일 (우선)

### M0 기반·보정 (착수 대기) — TASK_mobile_m0.md
- [x] MT0-01 SSOT v3 패치(CHANGES_V3) + 모델 ID 적용·스모크 검증(D-20)
  - 2026-08-02: §0~§5+§3.5 적용분 2단 판정 PASS(qa-verifier→aaa-critic, docs/reviews/REVIEW_M0.md). 커밋 348f1b9·5e190a9. 참고: registry_version 0.1.0 vs 문서 "0.2.0" 불일치는 MT0-05 착수 전 해소(O-3)
  - 2026-08-02 스모크(부분): 모델 목록 실조회로 3개 ID 완전일치 확인(D-20 유효성 실측 완료). 실호출 3건은 400 "credit balance too low"로 차단 — 크레딧 충전 후 (b)호출·(c)구조화출력만 재검증(증빙: docs/journal/2026-08-02_MT0-01_model_smoke.md) → 2026-08-02 충전 후 재검증 **3/3 PASS**(저널 §6), qa→aaa 2단 PASS(REVIEW_M0 MT0-01 라운드 3) — **MT0-01 완료**
  - 2026-08-02 (사용자 지시) D-24 LLM 공급자 옵션화: llm_tiering provider 스위치 + Gemini GA 후보 2종 병기(기본 비활성, 동작 변화 0), 검토 결론 "부분 대체 가능·전면 대체 비권고"(docs/journal/2026-08-02_gemini_option_review.md). qa PASS → aaa CONDITIONAL(경미 2) → 해소(REVIEW_M0.md) → **같은 날 사용자 지시로 D-24 철회**: SSOT gemini 병기·스키마 테스트 가드 제거, 검토서는 기록 존치(P0_DESIGN_DECISIONS D-24 철회 기록)
- [x] MT0-02 engine_ref (BT-01 전반)
  - 2026-08-02: 순수 함수 6모듈(transforms·scoring·modifiers·registry·statemachine) + 테스트 67건, 커버리지 100%. 라운드 1 FAIL(의미론 미확정 3건 외 9결함) → D-25 물질화 → 라운드 2 FAIL(증인 테스트 부재, 변이 생존 5종) → 라운드 3 **PASS**(qa→aaa, 변이 7/7 kill 특이도 1:1). REVIEW_M0 참조. 인계: O3-1(min_dwell 무효 — D-25 §2 부기, BT-03 규율), O2-1·O2-2·O3-2 잠재 관찰
- [x] MT0-03 픽스처 9창 (BT-01 후반)
  - 2026-08-02: 빌더(경험적 달력×비례 롤업)+windows.yaml+37테스트, 9창 실수집 완료(근사-PIT). aaa 4라운드 수렴(DEF-1~6 → NEW-1~3 → F3-1 → F4-1, 3연속 FAIL 시 사용자 승인으로 표적 수정) → **PASS**. 실측 확정: KRX 2026 로그인 정책(pykrx 1.2.8+계정), XKRX 달력 2026 휴장 미반영, FRED HY OAS 2023-08 개시, VKOSPI 폴백 확정(K-02 종결), ^MOVE·^VIX3M 야후 절단. 신설 규율 2건(퇴화 입력 증인·귀속 서술 증거) — REVIEW_M0 참조
- [x] MT0-04 골든 2×2프로파일 + golden_mobile.yaml + D-14 상신 자료 (BT-02)
  - 2026-08-02: run_replay(근사-PIT 가시성·스테일·경험적 달력)+replay.yaml+골든 yaml 2종+test_golden 6건, 전체 145 green. 라운드 1 FAIL(F-1~F-6: --config 스테일 split-brain·경로 캐시 고착·D-14 출처/인과 결함·증인 부재) → 전면 배선+무캐시 해소 → 라운드 2 **PASS**(qa→aaa, 골든 18행 비트 동일, REVIEW_M0 참조). D-08 재확인(server 08-05 kr_close ORANGE·mobile RED·음성 GREEN). **D-14 상신: 승격 보류 권고** — w2026 두 프로파일 ORANGE 미도달(35.15/40), vol_global 무발화(데이터 결손 vs 실거동 분리 불가, C1 재상신). 이월: O-3·O-7·O-4·90분 스테일↔sustain 격리(BT-03), O-5(P1), KRW=X 가시성·30분 해상도(C1)
- [x] MT0-05 보정 스윕 → configs 0.3.0-rc (BT-03)
  - 2026-08-03: O-3 해소(0b6d9ba). 설계(sweep.yaml+저널)는 aaa 4라운드 수렴(R1 결함 13 → R2 3: server 플래핑 "일봉 아티팩트" 귀속이 **statemachine 설정 모순**(`or_any_crit` 진입 ⊕ `exit_AMBER` 동시 영구 충족 → 입력 불변 영구 진동)으로 반증·재귀속 → R3 CONDITIONAL → R4 **PASS**, b84d3f7). AD-1~6 물질화(저널 §0). 핵심 실측: w2026 ORANGE는 스윕 ①~④로 구조적 불가(전 조합 35.15 불변 — 해상도 F-06 소관), F-04 비활성 유지(픽스처 9/9 미수집+수집 경로 부재+켜도 리드 0), server 스테일·플래핑은 스윕 무감.
  - Stage B: run_sweep(리터럴 0)+하니스 path 오버라이드+증인 8테스트, S1~S5=154평가. 선정: ①④·promote·demote 무변경, **min_dwell 2→5(O3-1 비무효 영역), reentry_cooldown →2 확정, confirm_time →17:00**(하니스 무감·물리 논증, M1 재확인). qa FAIL(동어반복 단언 등 5건)→수정→PASS, aaa CONDITIONAL(SB-1·SB-2 1줄씩)→커밋 동반 해소로 **PASS**. 153 green+골든 6 green(0.3.0-rc 반영 후). §6 판정표: w2026 탐지·리드 FAIL(F-06)·server 플래핑 FAIL(설정 모순) verbatim 유지 — "0.3.0-rc (조건부)". **GM0 안건 2건 인계**: ① w2026 해상도 갭(BT-04 대응안 비교) ② `or_any_crit ⊕ exit_AMBER` 충돌(프로덕션 재현성, mobile 주기 2영업일 — 우선 안건). BT-04 승계: metrics.json 재생성 절차 명시, S1~S3 완화 게이트 무영향은 이번 실행 한정.
- [x] MT0-06 성능·해상도 리포트 + F-06 대응 제안 (BT-04)
  - 2026-08-03: Stage A 설계(저널+f06_variants.yaml) aaa 5라운드 PASS(ce09062, AD-7~11 물질화)
    → Stage B 실행 aaa 4라운드 PASS(0c93647, 총 9라운드). 173 green·골든 6·engine_ref 커버리지
    99%. **홀드아웃 포함 §6 판정: 신규 FAIL 1**(w2023_11 mobile AMBER 18틱 오탐 — R-1 예측
    실현, AD-8로 GM0 상신) + 기존 FAIL 2(w2026 탐지·리드, server 플래핑 20→25 악화) verbatim.
    **F-06 대응안 3종 실측: 채택 가능 후보 0** — ①or_any_extreme는 w2026 탐지 성공(리드
    17/13)하나 mobile 플래핑 6→9로 하드 게이트 탈락(AD-11 여유 0 발동+안건 2 구조 동형성
    실증), ②4단계는 골든 비호환+w2026 악화(35.15→34.12)로 이중 실격, ③RED 서브레벨은 다른
    증상 대상. **유일 실효 경로 = ① 변형(이스케이프-이탈 짝지음, 안건 2 수정 방향 A와 동일
    기전) — GM0 상신.** 절차 사건 1(허위 완료 보고 → qa 적발·독립 재산출 검증 종결, 재발
    방지 규율 2건 신설). BT_REPORT §BT4 게이트 인용 적격(aaa 확정).
- [x] 게이트 GM0: GATE_GM0.md(aaa 게이트 검토 2라운드 PASS) + **사용자 승인 2026-08-03**
  - 결정: D-14 보류(C1) / F-04 비활성 유지(C1) / F-06 ① 변형 재설계(안건 5 연동, MT0-07) /
    데모 픽스처 유지 / statemachine 충돌 방향 A 채택(**D-26** 신설) / 신규 오탐 (b)+(c) 병행
    (C1 이관 부기) / M0 종결·M1 착수 승인. §6 FAIL 3건은 조건부 수용(0.3.0-rc 라벨 유지,
    안건 3·5·6 해소 시 0.3.0 승격). 커밋 86571cd + 결정 물질화 커밋.
- [x] MT0-07 이스케이프-이탈 짝지음(D-26) + ① 변형 재시뮬 (GM0 승인 후속, TASK_mobile_m0 §MT0-07)
  - 2026-08-04: 설계 aaa 2라운드 + 실행 4라운드 PASS(총 6). D-26 프로덕션 반영(레벨-로컬·
    reset·configs 키 0 — 엔진 의미론, D-25 부기 물질화). 실측: 골든 100% 유지, 한계진동 소멸
    (상수 입력 server 15→1·mobile 24→0), server 플래핑 25→13(§6 FAIL 잔존·C1), mobile 6→5
    (여유 회복), w2023_11 오탐 18 불변(C1 이관 유지), 탐지·리드 불변. ① 변형 재시뮬: 하드
    게이트 생존 0/3→3/3, mobile w2026 탐지(리드 17/13), server는 distinct_axes(AD-10)로
    미탐지 — 목표 미달성 verbatim. F2 뮤테이션 7/7 복원(중대 반려 1회 경유), 176 green.
    절차: Stage B에서 비평가 처방 오류 2건을 Worker 실증 반박으로 정정(REVIEW_M0 기록).
    **① 채택 상신 → 사용자 결정(2026-08-04): 채택 — 20.0% + C1 재확정 조건부**(GATE_GM0
    후속 결정 기록).
- [x] MT0-08 ① 변형 프로덕션 채택 반영 (20.0% + or_any_extreme, registry 0.3.1-rc — TASK_mobile_m0 §MT0-08)
  - 2026-08-04: qa PASS(발견 0) → aaa PASS(결함 0 — M0 유일 무반려 통과). **mobile 탐지
    6/7→7/7 PASS**(w2026 첫 ORANGE 07-08, 리드 15거래일), server 전 항목 불변, 골든 발화 0회
    구조 확인, F2 7/7 유지, 177 green. 잔여 §6 FAIL 3건 C1 이관 verbatim(C1 TASK ⑦ 승계
    부기). **M0 트랙 전체 마감 — M1 plan council 착수 가능**(GATE_GM0 안건 7 기승인).

### M1 모바일 코어 (진행 중) — TASK_mobile_m1.md
- [x] plan council 통과 → TASK 확정 → **사용자 승인 2026-08-07**
  - 2026-08-06~07: 4관점(A 아키텍처/B 데이터·정합성/C UX·운영/D 런타임 경로 — D는 3연속 FAIL
    구조 재분류에 따른 사용자 결정으로 신설) × 7라운드, **전원 PASS**. 결함 누계 40건 전량
    해소, 구조 재분류 2회 모두 처방 작동(REVIEW_M1.md). 병합 확정본 docs/plans/M1_PLAN_FINAL.md
    (필수 결정 11+3건, 정규 매핑, 서브태스크 합집합 W0~W5, SSOT 변경 제안·착수 선행 2건).
    승인 시 확정: KIS 미보유 → 04e M2 이연 / 스모크 필수만+S9. 커밋 m1-00.
- W0 실측 선행 (판정: qa R1 PASS→aaa R1 FAIL(D-1~5·A-1~6)→수정→qa R2 PASS→**aaa R2 PASS** — REVIEW_M1)
  - [x] SSOT 선행 2키(preview_coverage_min 0.80·warmup_padding_days 550) — ea14a87, 가드 테스트 포함
  - [x] MT1-00a 야후·Stooq·FRED — 완료. 야후 chart v8 UA만으로 OK / **Stooq 전면 PoW 차단(K-18
    폴백 무효, A-2 미결)** / FRED 폴백: VIX·SPX 가능, KRW·DXY 불가 / HY OAS **3년 롤링 정책**·
    공휴일 결측 표기 이원성 / MT1-04a·04b 계약 인도
  - [~] MT1-00b ECOS item_code — **차단(BLOCKED, 워커 귀책 아님)**: ECOS_API_KEY 미발급, VERIFY
    2건 유지. 재개 조건 저널 §4. MT1-04d 블록 유지. FRED 절반은 00a §12로 이관·완료
  - [x] MT1-00c kotlin_krx — 완료. login·VKOSPI 모바일 조회 성공(M-19(c) 물질화) / **D-1 투자자
    필드 오정렬 적발·정본 확정(외국인=TRDVAL10+11)** — 벤더링 승계 의무 3건 → MT1-01g PROVENANCE
  - [x] MT1-00d CDS(G-4) — 완료. **(b) 미수집 확정** → GM1 기록 항목
  - [ ] MT1-00e 툴체인 호환 매트릭스 — 미착수(MT1-01a 블로커)
  - [ ] MT1-00f SQLite 인덱스 플랜 — 미착수(성능 확인용, M-44 미사용은 기결정)
  - [~] MT1-00g 확정 시각 — **부분 완료**: 계기+1차 표본+range 상한(캐치업 20 병목 아님) 인도.
    **3거래일×6시점 폴링 미완** → confirm_time_kst SSOT 미기입, MT1-06 블록 유지.
    잔여: 08-07 16:00~19:00(모니터 자동) / 08-10 / 08-11
  - GM1 기록 누적: 무폴백 4계열(KRW=X·DXY·MOVE·VIX3M) / credit 축 이중 차단(ECOS+CDS) /
    A-1 프리뷰 커버리지 상한(ECOS 차단 시 0.792<0.80, 키 발급 시 0.847 — **MT1-07 착수 게이트**)
- [ ] MT1-01 스캐폴드+syncConfigs / [ ] MT1-02 계약 미러+스냅샷 / [ ] MT1-03 Room append-only
- [ ] MT1-04 collectors a야후 b FRED c KRX d ECOS e KIS(옵션) f CDS 판정(G-4)
- [ ] MT1-05 엔진·상태기계 + 패리티(BT-05) / [ ] MT1-06 일일 확정 틱+캐치업
- [ ] MT1-07 프리뷰(D-17) / [ ] MT1-08 노티+기능판 홈
- [ ] 게이트 GM1: 전 테스트+패리티 green, 실기기 1일 스모크, 사용자 승인

### M2 판단·UI (대기) — TASK_mobile_m2.md
- [ ] plan council → [ ] MT2-01 evidence / [ ] MT2-02 LLM 계층 / [ ] MT2-03 디자인 시스템
- [ ] MT2-04 홈 대시보드 / [ ] MT2-05 리포트·다이제스트 / [ ] MT2-06 위젯·설정·온보딩 / [ ] MT2-07 접근성·완성 요소
- [ ] 게이트 GM2: §2.4·§2.5 PASS, 비용표, 사용자 승인

### M3 검증·릴리스 (대기) — TASK_mobile_m3.md
- [ ] MT3-01 회귀 전량 + registry 0.3.0 확정 / [ ] MT3-02 실기기 7일 소크 / [ ] MT3-03 성능·안정성 확정 / [ ] MT3-04 릴리스+런북
- [ ] 게이트 GM3 + 사용자 승인 → **모바일 v1 운영 개시**

## Track S — 서버 (M3 이후, 기존 TASK 문서 + v3 델타)
- [ ] S1(=P1) ST-01~10, engine_ref 재사용 → GS1
- [ ] C1 실측 PIT 보정(0.3.0 상속 → 0.4.0), D-14·F-04·F-06 확정 → GS2
- [ ] S2(=P2) ‖ AL → GS3
- [ ] S3(=P3) + 스냅샷 발행 → GS4 → **서버 상시 운영 개시**

## INT 연동 (GS4·GM3 이후)
- [ ] plan council로 TASK_integration.md 작성(범위: D-21) → 구현 → 게이트 GI

## P4 선택 확장 — 모듈 A(SNS)·B(공식텍스트)만 존치, 사용자 지시 시 착수 (모듈 C는 INT로 흡수)

## 운영 주기 (GM3/GS4 이후) — 분기 C-주기(모델 목록 실조회·폐기 일정 확인, D-20 §20.3) / 판정 저널 리뷰
