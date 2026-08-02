# OPERATIONS_RUNBOOK — 상시 운영 기준 문서

적용 시점: G4 통과 이후. 대상 장비: Ryzen 7 8745HS / 32GB / NVMe 1TB + 백업 SSD (D-09).

## 1. 최초 배포 (1회)

1. Ubuntu Server 24.04 LTS 설치, 유선 고정 IP, BIOS: AC 전원 복구 시 자동 부팅 ON.
2. 기본 세팅: `ufw`(SSH+Tailscale만 허용), unattended-upgrades(보안 패치 자동), Docker + Compose, Tailscale 로그인.
3. 백업 SSD 마운트: `/backup` (fstab 등록), 스모크: 쓰기 테스트.
4. 저장소 clone → `.env` 작성(.env.example 기준, 백업 SSD가 아닌 로컬에만 보관) → `docker compose up -d`.
5. 검증: `make once` 1틱 성공, Grafana 접속(Tailscale 경유), 텔레그램 수신, healthchecks.io 핑 등록 확인.
6. systemd: compose는 `restart: unless-stopped` 정책 사용. 호스트 재부팅 리허설 1회로 자동 복구 확인.

## 2. 백업 (자동)

- 매일 02:30 `pg_dump` → `/backup/pg/<date>.dump` (14일 보관 로테이션).
- 매일 03:00 lake rsync → `/backup/lake/` (append-only라 증분 자연 성립).
- 매주 일요일 rclone으로 `/backup` → Cloudflare R2 (오프사이트). 월 1회 복원 리허설: 임시 컨테이너에 restore 후 `make replay` 골든 통과 확인 — **복원 안 되는 백업은 백업이 아니다.**

## 3. 배포·변경 절차

`git pull` → `make lint && make test`(전부 green일 때만) → `docker compose build && docker compose up -d`
→ `make once` 스모크. config 변경은 반드시: Advisor 승인 근거(D-xx 또는 TASK 허가) 커밋 메시지 명기
+ registry/schema version 상승 + 골든 리플레이 재실행.

## 4. 관제와 알림 대응

- healthchecks.io 미핑 경보 = 서비스 사망: SSH → `docker compose ps`, `docker compose logs --since 1h app`.
- Grafana 일일 확인 항목(1분): composite 타임라인 연속성, 지표 stale 여부, 뉴스 유입량.
- 텔레그램 국면 전이 수신 시: 리포트 HTML 확인 → 무효화 조건을 개인 워치리스트에 등록(판정 저널).

## 5. 장애 플레이북

| 증상 | 조치 |
|---|---|
| 특정 소스 지속 결측 | stale 정책이 흡수 중인지 Grafana 확인 → 어댑터 로그 → 제공자 장애면 대기(코드 수정 금지), 스키마 변경이면 이슈로 기록 후 Advisor 세션에서 수정 TASK화 |
| yfinance/pykrx 차단 의심 | 호출 간격 로그 확인(K-01/K-03), 24시간 백오프, 재발 시 대체 소스 검토를 결정 안건으로 상신 |
| DB 기동 실패 | 볼륨 점검 → 최신 dump 복원(2장) → 유실 구간은 lake에서 재적재(운영 DB는 재구성 가능, lake가 원장) |
| 디스크 80% 초과 | Docker 이미지 prune → lake 압축 확인 → 백업 로테이션 점검. lake 삭제는 어떤 경우에도 금지 |
| LLM 호출 실패/비용 급증 | draft 저장 경로 확인(ST-P3-03) → Anthropic 상태 페이지 → 월 비용이 예산 2배면 티어링 하향을 결정 안건으로 상신 |
| 오탐 체감 증가 | 즉흥 임계 조정 금지. 사례를 `docs/journal/`에 기록 누적 → 분기 C-주기에서 일괄 반영 |

## 6. 정기 주기

- **일**: 관제 1분 점검(4장). **주**: 오프사이트 백업 성공 확인, 디스크·메모리 추이.
- **분기(C-주기)**: TASK_calibration_c1.md 절차를 최신 데이터로 재실행(신규 사건 창 추가), analogue 시드 확장 검토(AL-05), 판정 저널 리뷰 — 무효화 조건 적중률·선행지표 변별력 사후 평가(타당성 검토 7장).
- **반기**: 의존성 업그레이드(별도 브랜치에서 전체 테스트 후), Ubuntu 배포판 패치 상태 점검.

## 7. 판정 저널 (시스템 신뢰의 원천)

모든 ORANGE/RED 사건 종료 시 `docs/journal/<event>.md`: 스냅샷 링크, 무효화 조건별 적중 여부,
실제 경로 vs 시나리오 범위, 개선 후보(임계·프롬프트·analogue). C-주기의 유일한 입력이다.
