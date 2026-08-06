# MT1-00e — Android 툴체인 호환 매트릭스 실측 (M1 W0, MT1-01a 블로커)

- 작성일: 2026-08-07 · 소속: M1 W0 / MT1-00e · 역할: data-verifier Worker
- 근거: `docs/plans/M1_PLAN_A.md` 표 §14(MT1-00 목록) 00e 행 · AD-A9(§1, "의존성 버전 숫자를 박지
  않는다 — 제약만 고정하고 실제 값은 MT1-00e 실측 후 카탈로그에 기록") · §16 자기한계 #1
- **범위 고지(중요)**: 본 세션 브리프는 00e 행 전체(스냅요약: AGP↔Kotlin↔Gradle 매트릭스 +
  snakeyaml-engine Android 호환 + Konsist·Robolectric·work-testing·room-testing 최신 안정 +
  kotlin_krx origin push 상태) 중 **툴체인 매트릭스 부분만** 지정했다. 처리 범위와 미처리
  잔여 항목은 §8에 명시한다 — 추측으로 채우지 않고 사실 그대로 이관한다.
- **대상 저장소 변경 범위: 0.** `D:\android_2025\kotlin_krx`는 읽기·`git log`만 수행했다(수정
  없음). `mobile/`은 생성하지 않았다(MT1-01a 소관). 실측용 스모크 프로젝트는 저장소 밖
  scratchpad에 만들었다(§5).

## 0. 결론 요약 (TL;DR)

| # | 질문 | 답 |
|---|---|---|
| 1 | 이 머신에 AGP×Gradle×Kotlin×JDK×compileSdk 조합이 실제로 configure되는가 | **예.** AGP 8.13.2 + Gradle 8.13 + Kotlin 2.1.0(+ `kotlin.plugin.compose` 2.1.0) + JDK 21(Android Studio 내장 JBR) + compileSdk 36 조합으로 `gradle tasks` **BUILD SUCCESSFUL**(§5) — 추가 다운로드 0건(Gradle 8.13·SDK 컴포넌트 전부 이미 로컬 존재) |
| 2 | kotlin_krx(Kotlin 2.1.0, JVM 17 target)와 버전 충돌이 있는가 | **없음.** mobile/ 카탈로그를 Kotlin **2.1.0로 고정**하면 정렬이 자동 성립(§2) |
| 3 | ktlint/detekt/Kover는 Kotlin 2.1.0과 바로 맞는가 | ktlint-gradle·Kover는 문제 없음. **detekt는 공식 호환표에 2.1.0 행이 없다(1.23.8=2.0.21, 다음은 2.0.0-alpha=2.2.20+) — 실제 GitHub 이슈로 비호환 보고 사례 있음(§4, 리스크로 이관)** |
| 4 | 로컬 JDK/SDK 구성에 결측이 있는가 | JAVA_HOME 미설정, PATH에 `java`/`gradle` 없음(§1). Android SDK cmdline-tools 미설치(§1). 둘 다 **차단은 아님** — Android Studio 내장 JBR과 이미 설치된 SDK 컴포넌트로 스모크가 통과했다 |
| 5 | 스모크에서 새로 발견한 함정이 있는가 | **있음.** `kotlin { jvmToolchain(17) }` DSL은 Gradle의 엄격한 툴체인 자동탐색을 트리거해 "JDK 17을 못 찾음"으로 **실패**한다(다운로드 리포지토리 미설정). `compileOptions`/`kotlinOptions`로 target=17만 지정하면(툴체인 강제 없이) JDK 21이 그대로 크로스컴파일해 성공한다(§5) — MT1-01a 카탈로그에 반영 필요 |

## 1. 로컬 환경 실측

```
JAVA_HOME            = (미설정)
java on PATH         = 없음
gradle on PATH       = 없음
ANDROID_HOME / ANDROID_SDK_ROOT = (미설정, 단 local.properties류로 sdk.dir 지정 시 문제 없음)

Android Studio        AI-253.29346.138.2531.14876573 (product-info.json)
  내장 JBR            JAVA_VERSION=21.0.9 (JetBrains s.r.o., JAVA_RUNTIME_VERSION=21.0.9+-14649483-b1163.86)
  경로                C:\Program Files\Android\Android Studio\jbr

Android SDK 위치       C:\Users\gmdjl\AppData\Local\Android\Sdk
  build-tools         34.0.0, 35.0.0, 36.1.0
  platforms           android-35, android-36, android-36.1
  cmdline-tools       (미설치 — sdkmanager CLI 단독 사용 불가, GUI/Studio 경유만 가능)
  licenses            android-sdk-license (수락됨)

Gradle wrapper 배포 캐시 (~/.gradle/wrapper/dists) 8.5-bin, 8.9-bin, 8.13-bin 3종 기추출 상태
  (8.5는 kotlin_krx의 wrapper 버전과 동일 — 재사용 중인 캐시로 추정)
```

standalone JDK(Temurin/Corretto/Zulu 등)는 `Program Files`·`Program Files (x86)` 어디에도 없다.
이 머신에서 Gradle/AGP를 CLI로 돌리려면 JAVA_HOME을 Android Studio 내장 JBR로 **명시 지정**해야
한다 — MT1-01a의 빌드 스크립트·CI 문서에 이 경로를 그대로 기록할 것을 권고한다(§6).

## 2. kotlin_krx 정렬 대상 (읽기 전용 확인)

`D:\android_2025\kotlin_krx\build.gradle.kts` / `gradle/wrapper/gradle-wrapper.properties`:

```
kotlin("jvm") version "2.1.0"
distributionUrl = gradle-8.5-bin.zip
java { sourceCompatibility = VERSION_17; targetCompatibility = VERSION_17 }
kotlinOptions.jvmTarget = "17"
```

순수 JVM 라이브러리(`kotlin("jvm")` + `java-library`)이며 **AGP·Android 플러그인을 전혀 쓰지
않는다** — 따라서 kotlin_krx 자체는 AGP 버전과 무관하다. mobile/에서 이를 벤더링(source
copy, MT1-01g)할 때 유일하게 맞춰야 하는 것은 **Kotlin 컴파일러 버전(2.1.0)과 JVM target(17)**
뿐이다. AD-A9의 "Kotlin ≥ 2.1.0" 제약과 정확히 일치하며, 위로 올릴 필요가 생기면(예: Kotlin
2.2/2.3) kotlin_krx 소스 자체에는 영향이 없다(순수 JVM 코드, 하위 호환 범위).

부기(git log, 읽기 전용): `origin/main..HEAD` / `HEAD..origin/main` 둘 다 빈 결과 — 로컬
HEAD(`6cc8180`)가 origin과 완전히 동기화됨. 미추적 파일 5건(MT1-00c 문서와 동일 목록)만
남아있고 push 대기 커밋은 없다. 이 항목은 §14(916행)의 "kotlin_krx origin push 상태" 요구를
읽기 전용 `git log`로 만족한다(변경 없음이므로 별도 실호출 예산 소비 없음).

## 3. AGP×Gradle×Kotlin×JDK×compileSdk×Compose BOM 매트릭스

공식 문서(2026-08-07 조회, 출처는 각 항목에 병기):

| 구성요소 | 확정값 | 근거 |
|---|---|---|
| AGP | **8.13.2** | [developer.android.com/build/releases/agp-8-13-0-release-notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes) — 최소 Gradle 8.13, 최소 JDK 17, **compileSdk 최대 36.1 지원**(정확히 로컬 SDK의 최신 platform과 일치) |
| Gradle | **8.13** | 위 AGP 요구사항과 정확히 일치 + 로컬에 **이미 추출되어 있음**(§1) → 신규 다운로드 0 |
| Kotlin | **2.1.0** | kotlin_krx 고정값과 정렬(§2), AD-A9 제약("≥2.1.0")의 최소값을 그대로 채택 — 상향은 미검증 리스크로 남김(§7) |
| JDK(빌드 실행) | **21**(Android Studio 내장 JBR) | AGP 8.13 최소요건(17)을 상회. standalone JDK 17이 로컬에 없어 JBR 21로 대체 실측(§1, §5) |
| compileSdk / targetSdk | **36**(minSdk 29는 계획 고정값 그대로) | AGP 8.13이 지원하는 최대치(36.1)이자 로컬 SDK platform 최신판과 일치 |
| Compose BOM | **2025.01.01** | Kotlin 2.1.0(2024-11 릴리스)과 동시대 안정판. Compose Compiler는 Kotlin 2.0+부터 `org.jetbrains.kotlin.plugin.compose` 플러그인이 **Kotlin 버전과 1:1**로 맞춰지므로 별도 버전 불필요(kotlin=2.1.0 → compose-compiler 플러그인도 2.1.0) — [developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) |

AGP 9.x 계열(최신 9.3.0, 2026-07)은 **의도적으로 채택하지 않았다** — 최소 Gradle 9.5.0을
요구하는데 로컬에 캐시된 Gradle 최고 버전은 8.13이라 신규 대형 다운로드가 필요하고, M1은
"기능판" 단계라 최신 메이저보다 로컬에서 즉시 재현 가능한 조합을 우선했다(AD-A9 정신).

## 4. ktlint · detekt · Kover

| 플러그인 | 버전 | Kotlin 2.1.0 호환 근거 | 리스크 |
|---|---|---|---|
| ktlint-gradle | **12.3.0** | 2025-05-22 릴리스가 내부 Kotlin 빌드를 2.1.20으로 갱신, 번들 ktlint 1.5대 — 2.1.x 계열 테스트 확인 | 낮음 |
| detekt | **1.23.8** | [detekt.dev/docs/introduction/compatibility](https://detekt.dev/docs/introduction/compatibility/) 공식 표에는 1.23.8→Kotlin 2.0.21 행이 마지막이고, 다음 행은 2.0.0-alpha→Kotlin 2.2.20+로 **건너뛴다 — 2.1.0 전용 행이 없다** | **중간.** GitHub `detekt/detekt#7883`이 "Kotlin 2.1.0에서 `kotlin-compiler-embeddable`이 KGP와 함께 classpath에 존재해 예측 불가 동작" 이슈를 실제로 보고함. MT1-01a에서 detekt를 실제로 배선할 때 **직접 실행 확인** 필요(본 세션 스모크에는 detekt 미포함 — §5 이유) |
| Kover | **0.9.8** | 공식 페이지에 Kotlin 버전별 호환표가 없음(문서 미기재) — 최신 stable 채택, K2/Kotlin 2.x 일반 지원은 업계 통념이나 문서 인용 불가 | 낮음(문서 근거 약함 — MT1-01a에서 `koverHtmlReport` 1회 실행으로 자체 확인 권장) |

## 5. 스모크 실측 (scratchpad, mobile/ 미생성)

위치: `scratchpad/smoke/`(session-scoped temp, 저장소 밖). 최소 `:app` 단일 모듈(AGP
application + Kotlin android + Kotlin compose 플러그인, compileSdk 36 / minSdk 29 /
targetSdk 36, `buildFeatures.compose = true`, Compose BOM 2025.01.01 + `activity-compose`
1.9.3).

**1차 시도 — 실패 (실제 발견):**
```
kotlin { jvmToolchain(17) }  선언 상태
> Cannot find a Java installation on your machine (Windows 11 ... amd64) matching:
  {languageVersion=17, vendor=any vendor, implementation=vendor-specific}.
  Toolchain download repositories have not been configured.
```
JBR 21을 `JAVA_HOME`으로 지정해도 `jvmToolchain(17)`은 "정확히 17을 제공하는 설치"를
찾으려 하며 21을 후보로 인정하지 않는다(툴체인 자동 다운로드 리포지토리 미설정이므로
provisioning도 불가).

**2차 시도 — 성공:** `jvmToolchain(17)` 제거, `compileOptions{ source/targetCompatibility =
VERSION_17 }` + `kotlinOptions{ jvmTarget = "17" }`만 유지(툴체인 강제 없이 javac/kotlinc가
직접 target=17로 컴파일 지시만 받음, 실행 JDK는 그대로 21).

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
<gradle-8.13 배포 캐시 경로>\bin\gradle.bat tasks --console=plain
...
BUILD SUCCESSFUL in 5s
1 actionable task: 1 executed
```

AGP/Kotlin/Compose 플러그인 해석, Google/MavenCentral 의존성 다운로드, 프로젝트 configure,
`:app` 태스크 그래프 생성(installDebug/lint/test 등 표준 태스크 전부 노출) — 전부 정상. 신규
Gradle 다운로드 0건(캐시된 8.13 재사용), SDK 컴포넌트 신규 다운로드 0건.

detekt/ktlint/Kover는 이번 스모크에 포함하지 않았다 — §4에서 이미 문서 근거로 detekt 리스크를
확인했고, 3개 정적분석/커버리지 플러그인을 최소 스모크에 추가하는 것은 "AGP+Kotlin+Compose
코어 툴체인이 이 머신에서 configure되는가"라는 본 항목의 판정 기준을 벗어난다(범위 유지) —
MT1-01a가 실제 배선 시 1회 실행으로 직접 확인해야 한다(특히 detekt, §4).

## 6. 추천 버전 카탈로그 초안 (MT1-01a 입력)

```toml
[versions]
agp = "8.13.2"
kotlin = "2.1.0"
composeBom = "2025.01.01"
activityCompose = "1.9.3"
ktlintGradle = "12.3.0"
detekt = "1.23.8"       # 리스크 §4 — 실제 실행으로 재확인
kover = "0.9.8"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library      = { id = "com.android.library", version.ref = "agp" }
kotlin-android       = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm           = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose       = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ktlint               = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlintGradle" }
detekt               = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
kover                = { id = "org.jetbrains.kotlinx.kover", version.ref = "kover" }

[libraries]
compose-bom      = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
```

빌드 설정 권고(§5의 실측 함정 반영): `kotlin { jvmToolchain(N) }` DSL을 쓰지 말 것. 대신
`android { compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility
= JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" } }` 형태로 target만 지정한다.
CLI/CI에서는 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` 명시(로컬에 standalone
JDK 17/21이 없으므로).

## 7. 리스크·미설치 컴포넌트 (설치는 하지 않음, 상신)

- **detekt 1.23.8 × Kotlin 2.1.0**: 공식 호환표 공백 구간 + 실제 비호환 이슈 보고(§4). MT1-01a
  배선 시 `detekt` 태스크 1회 실행으로 직접 확인 필수. 실패 시 대안: detekt를 2.0.0-alpha 계열로
  올리려면 Kotlin도 2.2.20+로 동반 상향해야 하므로(§4 표) kotlin_krx 정렬(§2)이 깨질 위험 —
  이 경우 detekt 도입을 M1에서는 보류하고 ktlint만으로 정적 검사를 운용하는 것도 옵션으로 열어둔다.
- **Android SDK cmdline-tools 미설치**: `sdkmanager`/`avdmanager` CLI 단독 실행 불가(§1). 현재
  필요한 컴포넌트(build-tools 34/35/36.1, platform 35/36/36.1)는 이미 설치돼 있어 **차단 아님**.
  향후 새 SDK 컴포넌트(예: 에뮬레이터 시스템 이미지)가 필요해지면 Android Studio SDK Manager
  GUI 경유로 설치 필요 — 이번 세션에서는 설치하지 않았다(브리프 제약).
- **standalone JDK 부재**: JAVA_HOME 미설정 상태가 기본값이라, CI/신규 개발자 환경에서는 Android
  Studio 설치 여부에 build가 암묵적으로 의존한다. MT1-01a 문서(README/CONTRIBUTING류)에 JBR
  경로를 명시하거나, Gradle Foojay toolchain resolver 플러그인 도입으로 `jvmToolchain()`을 다시
  안전하게 쓸 수 있게 하는 것을 후속 검토 항목으로 남긴다(이번 스모크는 그 플러그인 없이도
  target 지정만으로 통과했으므로 필수는 아님, §5).

## 8. MT1-00e 전체 범위 대비 처리 범위 (정직성 고지)

`M1_PLAN_A.md` 00e 행(§14, 915행)은 아래 4가지를 요구한다. 본 세션 브리프는 (1)만 지정했다:

| # | 요구 항목 | 처리 여부 |
|---|---|---|
| 1 | AGP↔Kotlin↔Gradle 호환 매트릭스 | **완료**(§3, §5) |
| 2 | `snakeyaml-engine` 최신 안정 버전·Android 호환 근거 | **미처리** — 본 브리프 범위 밖. §16(316~320행)이 이미 "Android 호환성은 MT1-00e에서 계측 스모크로 실증"을 요구하므로 후속 Worker 디스패치 필요 |
| 3 | Konsist·Robolectric·work-testing·room-testing 최신 안정 버전 | **미처리** — 동일 사유 |
| 4 | kotlin_krx origin push 상태(`git log origin/main..HEAD`) | **완료**(§2 부기, 읽기 전용 확인 — 동기화됨, push 불필요) |

2·3번은 추측으로 카탈로그에 채우지 않았다(브리프 원칙 준수) — Advisor가 별도 디스패치로
후속 확정할 것을 권고한다.

## 9. 재현 절차

1. `D:\wp_2026\branch-console\scratchpad`(또는 임의 위치)에 최소 Android 프로젝트 생성:
   `settings.gradle.kts`(google/mavenCentral repos, `include(":app")`) + root
   `build.gradle.kts`(플러그인 버전 선언 `apply false`) + `app/build.gradle.kts`(§6 카탈로그
   그대로 적용, `jvmToolchain()` 사용 금지) + `app/src/main/AndroidManifest.xml`(최소) +
   `local.properties`(`sdk.dir=<로컬 SDK 경로>`).
2. `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.
3. 캐시된 Gradle 8.13 바이너리 직접 실행(래퍼 생성 없이):
   `~\.gradle\wrapper\dists\gradle-8.13-bin\<hash>\gradle-8.13\bin\gradle.bat tasks --console=plain`.
4. `BUILD SUCCESSFUL`이면 통과.

## 10. 검증

- 스모크: `gradle tasks` → **BUILD SUCCESSFUL in 5s**, 신규 다운로드 0건(§5).
- `mobile/` 미생성 확인: `git status --short`(branch-console) — 본 문서 외 무변경.
- kotlin_krx 무변경 확인: `git status --short`(kotlin_krx) — 기존 미추적 파일 5건 외 무변경,
  `origin/main..HEAD` 및 역방향 모두 빈 결과(§2).
- 인용 URL(§3, §4)은 2026-08-07 실시간 조회 결과이며 추측이 아니다 — 매직넘버가 아닌 카탈로그
  값이므로 SSOT 규칙(CLAUDE.md §1)과 무관하다(코드 하드코딩이 아니라 `gradle/libs.versions.toml`
  후보안).

## 11. 생성/변경 파일 목록

- `docs/journal/2026-08-07_MT1-00e_toolchain_matrix.md`(본 문서)
- 그 외 branch-console 저장소 변경 없음.
- `D:\android_2025\kotlin_krx`: 변경 없음(읽기·`git log`만 수행).
- 스모크 프로젝트(`scratchpad/smoke/`)는 session-scoped 임시 디렉터리로 저장소 밖에 위치, 이
  커밋에 포함되지 않는다.
