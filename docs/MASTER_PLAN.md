# MASTER_PLAN v3 — branch-console 이원 시스템

- 개정일: 2026-08-02 · 이 문서가 **시퀀스·게이트의 SSOT**다. v2(단일 트랙 P1→P4)를 대체한다.
- 아키텍처 근거: `docs/ARCHITECTURE_SPLIT.md`(D-15~D-21) · 품질 규율: `docs/AAA_QUALITY_STANDARD.md` · 백테스트: `docs/BACKTEST_PLAN.md`
- 절대 규칙: **현재 phase의 TASK 범위 밖 작업 금지. 다음 phase는 게이트 리포트(docs/gates/) + 사용자 승인 후에만 착수.**

---

## 0. 2026-08 검토 결과 반영 (본 개정의 입력)

정합성 점검(`CONSISTENCY_AUDIT_2026-08.md`, F-01~F-08 / D-11~D-14)과 검증 백테스트(설정 A/B/C 비교)의 결론을 계획에 다음과 같이 편입한다:

| 검토 결론 | 계획 반영 |
|---|---|
| F-03 (USD/KRW 양방향) — 검증됨: 2026-07 창 rates_fx 발화 0일→회복, 최고점수 75.1→81.9, 골든·음성 무회귀 | **즉시 채택 확정**(레지스트리 0.2.0 유지). BT-03에서 양방향 임계 1차 보정, C1에서 실측 확정(D-12) |
| F-04 (구조지표 2종) — 2026-07 리드타임 개선하나 **골든 회귀 유발**(미발화 지표의 분모 희석, D-02) | `enabled:false` 유지. **BT-03의 최우선 재보정 대상**(가중치·임계 동시 스윕 후 활성 판정) |
| F-06 — composite 포화보다 **지표 해상도 손실**이 실제 문제(다수 지표 severity 3 고정) | BT-04에서 대응안 비교(임계 사다리 확장 / CRIT+ 4단계 / RED 서브레벨) → 제안서 상신, 임의 구현 금지 |
| D-14 — 2026-07-28 3번째 골든 승격 제안 | BT-02에서 두 프로파일 기대값 산출 후 **GM0 게이트에서 사용자 승인 상신** |
| 음성 대조 — 세 설정 모두 오탐 0 | 무회귀 기준선으로 BT 수용 기준에 고정 |
| D-11 모델 ID(opus-5/sonnet-5) | 공식 문서 재확인 결과 **유효한 현행 ID** — D-20에서 확정 채택. 런타임 티어링과 개발 시 Advisor/Worker 배정을 함께 고정 |

미결(사용자 결정 대기): ① D-14 승격 ② 데모 픽스처 2026-07 교체(F-08) — 둘 다 GM0 게이트 안건.

---

## 1. 트랙과 시퀀스

```
Track M (모바일, 우선)   M0 ──▶ M1 ──▶ M2 ──▶ M3 ═══ 모바일 v1 운영
                                              │ (S 착수 조건 = GM3 승인)
Track S (서버, 후속)                           └▶ S1 ──▶ C1 ──▶ [S2 ‖ AL] ──▶ S3 ═══ 서버 상시 운영
                                                                              │
INT (연동)                                                                     └▶ INT ═══ 이원 체제 완성
P4 (선택)                모듈 A(SNS)·B(공식텍스트)만 존치 — 사용자 지시 시 개별 착수 (모듈 C는 INT로 흡수)
```

의존성: M1은 M0의 레지스트리 0.3.0에 의존. S1은 M0의 engine_ref를 재사용하나 착수 자체는 GM3 이후다. C1은 서버 lake 백필에 의존하며 BT 산출을 초기값으로 상속. INT는 S3와 M3 완료에 의존. **M 트랙 완료 전 S 트랙 착수 금지**(사용자 지시: 모바일 우선).

## 2. Phase 정의

### Track M — 모바일

| Phase | 내용 | TASK 문서 | 게이트 |
|---|---|---|---|
| **M0 기반·보정** | SSOT v3 패치 적용(CHANGES_V3), 모델 ID 고정·스모크 검증(D-20), engine_ref 구축, 백테스트 하니스 BT-01~04, 레지스트리 0.3.0, D-14 상신 | `TASK_mobile_m0.md` | **GM0** |
| **M1 모바일 코어** | Android 스캐폴드, syncConfigs, 계약 미러+스냅샷, Room append-only lake, collectors 5종, Kotlin 엔진·상태기계(mobile_daily), 일일 확정 틱+캐치업, 프리뷰(D-17), 로컬 노티, 패리티(BT-05) | `TASK_mobile_m1.md` | **GM1** |
| **M2 판단·UI** | evidence 조립, LLM 티어링 호출(구조화 출력), 일일 다이제스트, 시나리오 리포트 화면, Compose 디자인 시스템(AAA), 홈 대시보드·위젯·온보딩·설정 | `TASK_mobile_m2.md` | **GM2** |
| **M3 검증·릴리스** | 백테스트 전량 재실행(회귀), 실기기 7일 소크(일일 틱 무결), 성능·크래시 계측, 릴리스 빌드, 모바일 런북 | `TASK_mobile_m3.md` | **GM3** → v1 운영 |

### Track S — 서버 (기존 TASK 문서 유지, CHANGES_V3 §3의 델타만 반영)

| Phase | 내용 | TASK 문서 | 게이트 |
|---|---|---|---|
| **S1 구현** (구 P1) | ST-01~10. detection 코어는 engine_ref import, 프로파일 server_intraday 명시 | `TASK_branch_console_p1.md` | **GS1** (구 G1) |
| **C1 보정** | CT-01~03 + CT-02b. BT 산출 상속 → 실측 PIT 확정, D-12·F-04·D-14 최종 판정 | `TASK_calibration_c1.md` | **GS2** (구 G2) |
| **S2 뉴스·HMM·관측 ‖ AL** (구 P2‖AL) | 기존 정의 그대로 | `TASK_branch_console_p2.md` / `TASK_analogue_library.md` | **GS3** (구 G3) |
| **S3 시나리오 엔진** (구 P3) | 기존 정의 + 스냅샷 발행 모듈(INT 대비) | `TASK_branch_console_p3.md` | **GS4** (구 G4) → 상시 운영 |

### INT — 연동 (범위: D-21. TASK는 GS4 통과 후 plan council로 작성)

게이트 GI 신규 조건(D-23 §23.5): 병행 운영 30일 동안 **동일 일자 확정 국면 일치율 ≥ 90%**,
불일치 전건이 프로파일·틱 주기 차이로 설명 가능할 것. 설명 불가 불일치가 1건이라도 있으면 엔진 패리티 회귀로 간주해 GI 반려.

서버 스냅샷 발행 → 모바일 서버-provider 소비(우선 표시 + 자체 폴백). 게이트 **GI**.

## 3. 게이트 절차 (전 게이트 공통)

1. TASK의 완료 기준 전 항목 green + **AAA 판정 전 항목 PASS**(aaa-critic, `docs/AAA_QUALITY_STANDARD.md` 루브릭).
2. 공통 회귀: `ruff`/`pytest` 전부 green + **골든 무회귀 × 2프로파일**(M0 이후) + (M1 이후) Kotlin 패리티·계측 테스트 green.
3. `docs/gates/GATE_<ID>.md` 작성(양식: `.claude/skills/gate-report`): 결과 요약, 수치 증빙, 미결·리스크, 다음 phase 착수 조건, 사용자 결정 안건.
4. **사용자 승인 후에만** 다음 phase 착수. PROGRESS.md 갱신·커밋.

## 4. 개발 프로세스 (Claude Code, 전 phase 공통)

- **세션 시작**: PROGRESS.md → 본 문서 → 현재 TASK → CLAUDE.md 순으로 읽는다. 세션 프롬프트에 **ultracode 활성 지시 포함**(세션 한정 설정이므로 매번), 설계·비평 턴에는 ultrathink.
- **계획**: phase 착수 전 plan council(M0는 본 v3 수립 자체가 council 산출이라 면제 — TASK_mobile_m0 머리 참조) — `plan-architect` 2~3 인스턴스 병렬(관점: 아키텍처 / 데이터·정합성 / UX·운영) → 각자 완전한 계획안 → `aaa-critic` 라운드 판정(FAIL 시 반려·재작성 반복) → Advisor 병합 → 사용자 승인. 절차 상세: AAA_QUALITY_STANDARD §3.
- **구현**: Advisor는 위임만. 독립 서브태스크는 한 메시지 다중 위임(병렬). 브리프에 대상 경로·D-xx 근거·K-xx 함정·완료 테스트 명령 필수.
- **검증**: 서브태스크마다 `qa-verifier`(기계 검증) → `aaa-critic`(품질 판정) 2단. PASS 전 PROGRESS 체크 금지.
- **도구**: MCP는 `docs/MCP_SETUP.md`, 에이전트·스킬 목록은 CLAUDE.md §6.

## 5. 산출물 버전 규약

- 레지스트리(configs): 0.2.0(현행) → **0.3.0**(GM0, BT 보정) → 0.4.0(GS2, C1 실측 확정). 골든 기대값 파일과 항상 짝으로 갱신.
- 계약: 기존 규칙 유지(/1 하위호환, 파괴 시 /2).
- 문서: 본 계획 개정은 반드시 개정일·사유를 머리에 남긴다.
