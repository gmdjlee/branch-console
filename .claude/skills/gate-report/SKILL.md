---
name: gate-report
description: phase 게이트 리포트(docs/gates/GATE_*.md)를 작성할 때 사용하는 양식과 절차. 게이트 통과 선언 전 필수.
---
# 게이트 리포트 양식

파일: docs/gates/GATE_<ID>.md. 순서 고정:
1. 요약 판정 (PASS 조건 충족 여부 한 문단)
2. 완료 기준 대조표 (TASK 항목 × 증빙: 테스트 명령·결과 요지·커밋)
3. 공통 회귀: ruff/pytest·골든×2프로파일·(M1+) 패리티·계측 결과 원문 요지
4. aaa-critic 판정 이력 요약 (docs/reviews/ 로그 인용, FAIL→해소 횟수 포함)
5. 수치 증빙 (백테스트 표·성능 계측·소크 로그 등 해당분)
6. 미결·리스크와 다음 phase 착수 조건
7. 사용자 결정 안건 (승인/선택 필요 항목을 질문 형태로)
작성 후 Advisor가 검토·서명(날짜)하고, 사용자 승인을 받은 뒤에만 PROGRESS의 게이트 항목을 체크한다.
