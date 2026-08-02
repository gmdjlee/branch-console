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
- [ ] MT0-01 SSOT v3 패치(CHANGES_V3) + 모델 ID 적용·스모크 검증(D-20)
- [ ] MT0-02 engine_ref / [ ] MT0-03 픽스처 9창 (BT-01)
- [ ] MT0-04 골든 2×2프로파일 + golden_mobile.yaml + D-14 상신 자료 (BT-02)
- [ ] MT0-05 보정 스윕 → configs 0.3.0-rc (BT-03)
- [ ] MT0-06 성능·해상도 리포트 + F-06 대응 제안 (BT-04)
- [ ] 게이트 GM0: 수용 기준표 충족 + GATE_GM0.md + 사용자 승인(안건: D-14 / F-04 / F-06 / 픽스처 교체)

### M1 모바일 코어 (대기) — TASK_mobile_m1.md
- [ ] plan council(3관점) 통과 → TASK 확정
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
