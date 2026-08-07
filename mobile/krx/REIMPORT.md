# REIMPORT — kotlin_krx 재이식 절차

`mobile/krx/`는 `D:\android_2025\kotlin_krx`의 소스 벤더링이다(PROVENANCE.md 참고). 업스트림이
갱신되면 아래 절차로 재이식한다. 원본 저장소는 항상 읽기 전용으로 다룬다.

## 절차

1. **업스트림 확인**: `git -C D:/android_2025/kotlin_krx log --oneline -20`으로 새 커밋
   확인. `git status --porcelain`으로 작업 트리가 깨끗한지(또는 알려진 미추적 파일만
   있는지) 확인 — 벤더링 대상은 커밋된 내용만이다.
2. **diff 검토**: `git -C D:/android_2025/kotlin_krx diff <이전 벤더링 SHA> <새 SHA> --
   src/main/kotlin/ src/test/kotlin/`로 변경 파일을 파악한다.
3. **우리 변경 재적용 여부 판단**: `PROVENANCE.md` §3의 4건(InvestorTrading.kt D-1 수정,
   IndexOhlcv.kt KDoc, KrxClient.kt 재시도 파라미터화, 자격증명 — 변경 없음)이 업스트림에서
   이미 해결됐는지 확인한다. 해결됐다면 우리 변경을 제거하고 PROVENANCE.md에서 해당 항목을
   "업스트림 반영으로 제거"로 갱신한다. 미해결이면 새 버전에 다시 적용한다.
4. **복사**: `src/main/kotlin/com/krxkt/**` 전체를 덮어쓴다. `src/test/kotlin/com/krxkt/**`는
   `integration/`과 실네트워크 의존 파일(현재: `ManualMarketCapTest.kt`,
   `MarketCapComparisonTest.kt` — 이름이 다른 새 탐색 스크립트가 추가됐다면 §2 배제 기준
   (네트워크 호출·미추적·login() 미선행)으로 재판단)을 제외하고 복사한다.
5. **우리 변경 재적용**: §3 목록의 미해결 항목을 다시 적용한다.
6. **매니페스트 갱신**: `krx-manifest.sha256`을 재생성한다(PowerShell/bash 어느 쪽이든,
   `Get-FileHash -Algorithm SHA256` 또는 `sha256sum`으로 벤더링된 모든 파일을 경로
   오름차순으로 나열). `PROVENANCE.md`의 복사 시점 SHA·일시를 갱신한다.
7. **검증**: `cd mobile && ./gradlew :krx:test :krx:verifyKrxProvenance` green 확인.
8. **원본 무변경 확인**: `git -C D:/android_2025/kotlin_krx status --porcelain`을 재이식
   전후로 비교해 원본 저장소에 변경이 없음을 확인하고 커밋 메시지/보고에 첨부한다.

## 알려진 함정

- 업스트림이 `InvestorTrading.fromJson()`을 자체적으로 고치면(TRDVAL8~11 정정), 우리
  위임 코드(`fromJson() = fromTickerJson()`)와 충돌하지 않는다 — 안전하게 제거 가능.
- `KrxClient` 생성자 시그니처가 업스트림에서 바뀌면 `maxRetries`/`retryDelaysMs` 파라미터
  위치·기본값을 다시 확인한다. `:app` 어댑터(MT1-04c 이후)가 이 생성자를 호출하므로
  시그니처 변경은 해당 호출부도 함께 갱신해야 한다.
