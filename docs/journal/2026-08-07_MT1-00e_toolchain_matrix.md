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
| 6 | (2차 디스패치) snakeyaml·테스트 라이브러리 4종은 이 조합과 맞는가 | **예, 전부 호환.** snakeyaml-engine 3.1(JavaBeans 미사용, Android 안전), Konsist 0.17.3·Robolectric 4.16.1은 KGP에 훅을 걸지 않아 Kotlin 2.1.0과 컴파일 경로 충돌 없음, work-testing 2.11.2(compileSdk≥33 요구, 36으로 충족)·room-testing 2.8.4(Kotlin≥2.0 요구, 2.1.0으로 충족) 전부 공식 릴리스 노트로 확정(§9). Robolectric SDK36 테스트는 "JDK 21 필요"를 명시하는데 이 머신의 유일한 JDK가 이미 JBR 21이라 그대로 맞아떨어짐 |

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
| compileSdk / targetSdk | **36**(minSdk 29는 계획 고정값 그대로) | AGP 8.13 지원 범위(최대 36.1) 안의 값 — 로컬 SDK 최신판은 36.1이지만 **36으로 채택**(§5 스모크도 36으로 수행). 36.1(QPR)로 올릴지는 MT1-01a 재량으로 남긴다 |
| Compose BOM | **2025.01.01** | Kotlin 2.1.0(2024-11 릴리스)과 동시대 안정판. Compose Compiler는 Kotlin 2.0+부터 `org.jetbrains.kotlin.plugin.compose` 플러그인이 **Kotlin 버전과 1:1**로 맞춰지므로 별도 버전 불필요(kotlin=2.1.0 → compose-compiler 플러그인도 2.1.0) — [developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) |

AGP 9.x 계열(최신 9.3.0, 2026-07)은 **의도적으로 채택하지 않았다** — 최소 Gradle 9.5.0을
요구하는데 로컬에 캐시된 Gradle 최고 버전은 8.13이라 신규 대형 다운로드가 필요하고, M1은
"기능판" 단계라 최신 메이저보다 로컬에서 즉시 재현 가능한 조합을 우선했다(AD-A9 정신).

## 4. ktlint · detekt · Kover

| 플러그인 | 버전 | Kotlin 2.1.0 호환 근거 | 리스크 |
|---|---|---|---|
| ktlint-gradle | **12.3.0** | 2025-05-22 릴리스가 내부 Kotlin 빌드를 2.1.20으로 갱신, 번들 ktlint 1.5대 — 2.1.x 계열 테스트 확인 | 낮음 |
| detekt | **1.23.8** | [detekt.dev/docs/introduction/compatibility](https://detekt.dev/docs/introduction/compatibility/) 공식 표에는 1.23.8→Kotlin 2.0.21 행이 마지막이고, 다음 행은 2.0.0-alpha→Kotlin 2.2.20+로 **건너뛴다 — 2.1.0 전용 행이 없다** | **중간.** GitHub `detekt/detekt#7883`이 "Kotlin 2.1.0에서 `kotlin-compiler-embeddable`이 KGP와 함께 classpath에 존재해 예측 불가 동작" 이슈를 실제로 보고함. MT1-01a에서 detekt를 실제로 배선할 때 **직접 실행 확인** 필요(본 세션 스모크에는 detekt 미포함 — §5 이유) |
| Kover | **0.9.9** | Maven Central `org.jetbrains.kotlinx:kover-gradle-plugin` 실조회(2026-08-07) — **0.9.9이 실제 최신 stable**(2026-07-17 발행). 최초본의 0.9.8은 아티팩트 저장소를 조회하지 않은 누락이었다(aaa CONDITIONAL 반영, 정정). Kotlin 버전별 호환표는 공식 페이지에 여전히 미기재 — K2/Kotlin 2.x 일반 지원은 업계 통념이나 문서 인용 불가 | 낮음(문서 근거 약함 — MT1-01a에서 `koverHtmlReport` 1회 실행으로 자체 확인 권장) |

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
kover = "0.9.9"             # §4 — Maven Central 실조회로 정정(aaa CONDITIONAL, 최초본 0.9.8 오기)
snakeyamlEngine = "3.1"     # §9.1
konsist = "0.17.3"          # §9.2 — 리스크: ~1년 무갱신, 내부 파서 Kotlin 2.0.20 고정
robolectric = "4.16.1"      # §9.3 — SDK36 테스트 시 JDK21 필요(이 머신은 이미 충족)
androidxWork = "2.11.2"     # §9.4 (work-testing 포함, compileSdk>=33 요구)
androidxRoom = "2.8.4"      # §9.5 (room-testing 포함, Kotlin>=2.0 요구) — Room 3.0 여부는 미확정(§9.5)

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
snakeyaml-engine = { module = "org.snakeyaml:snakeyaml-engine", version.ref = "snakeyamlEngine" }
konsist          = { module = "com.lemonappdev:konsist", version.ref = "konsist" }
robolectric      = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
work-testing     = { module = "androidx.work:work-testing", version.ref = "androidxWork" }
room-testing     = { module = "androidx.room:room-testing", version.ref = "androidxRoom" }
```

`activityCompose = "1.9.3"` 근거(aaa 관찰 반영): Compose BOM 자체는 `androidx.compose.*` 모듈만
정렬하고 `androidx.activity:activity-compose`는 BOM 밖의 별도 좌표라 버전을 직접 못 박아야
한다 — 1.9.3은 §3에서 채택한 Compose BOM 2025.01.01(2024-11/12 Kotlin 2.1.0 동시대 안정판)과
같은 시기에 안정화된 릴리스로, "동시대 핀" 전략을 그대로 따른 것이다.

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
| 2 | `snakeyaml-engine` 최신 안정 버전·Android 호환 근거 | **완료**(§9.1, 2026-08-07 2차 디스패치) |
| 3 | Konsist·Robolectric·work-testing·room-testing 최신 안정 버전 | **완료**(§9.2~9.5, 2026-08-07 2차 디스패치) |
| 4 | kotlin_krx origin push 상태(`git log origin/main..HEAD`) | **완료**(§2 부기, 읽기 전용 확인 — 동기화됨, push 불필요) |

00e 행 4항목 전부 처리 완료. §9.5(room-testing)에는 공식 문서 간 불일치(Room 3.0 관련) 1건이
미확정으로 남아 있다 — 추측으로 덮지 않고 그대로 이관한다(§9.5).

## 9. 후속 실측 — snakeyaml 계열·테스트 라이브러리 4종 (2026-08-07 2차 디스패치)

§8의 미처리 2·3번을 확정한다. 전부 공식 문서·Maven Central 실조회(2026-08-07), 추측 없음.
Android 인스트루먼트 테스트(에뮬레이터·기기)는 이번 세션에서 돌리지 않았다 — 근거는 공식
문서·Maven 메타데이터이며, 실제 런타임 확인은 각 항목에 명시한 대로 MT1-01a/01b 착수 시
1회 실행으로 넘긴다(정직 고지, 추측 금지 원칙).

### 9.1 snakeyaml-engine — Android(minSdk 29) 호환

- 최신 안정: **3.1**(Maven Central `org.snakeyaml:snakeyaml-engine`, 2026-08-05 게시 — 조회
  시점 기준 2일 전). 빌드 타깃 Java 11 source/target — Android는 D8/R8이 바이트코드를
  desugar하므로 minSdk 29 실행 자체엔 문제 없음(API 존재 여부가 아니라 바이트코드 레벨 이슈).
- **JavaBeans/introspection 미사용 재확인**(공식 README): "The Engine will parse/emit basic
  Java structures (String, List, Map). JavaBeans or any other custom instances are explicitly
  out of scope." — `java.beans`(Android AOSP에 없음, `M1_PLAN_A.md` 316~319행이 지목한 리스크)를
  원천적으로 안 쓴다. 계획이 이미 채택한 `org.snakeyaml:snakeyaml-engine`(vs `org.yaml:snakeyaml`)
  선택의 근거를 실측으로 재확인 — **이탈 근거 없음, kaml 등 대안 검토 불필요(계획 정본 유지)**.
- 카탈로그: `snakeyamlEngine = "3.1"`, `org.snakeyaml:snakeyaml-engine:3.1`(§6).

### 9.2 Konsist

- 최신 안정: **0.17.3**(Maven Central `com.lemonappdev:konsist`, 게시 약 1년 전 — 그 이후 신규
  릴리스 없음. GitHub Releases 동일 확인).
- KGP(Kotlin Gradle Plugin)에 훅을 걸지 않는다 — **내부에 `kotlin-compiler-embeddable:2.0.20`을
  번들**해 테스트 코드 안에서 소스를 AST로 파싱하는 순수 JUnit 라이브러리다. detekt류와 달리
  프로젝트 컴파일 경로 밖에서 동작하므로 프로젝트의 Kotlin 2.1.0과 **직접 충돌하지 않는다**.
- **리스크(중간)**: 공식 호환성 문서가 "최근 3개 Kotlin 릴리스와 호환"이라 서술한 시점(v0.17.0,
  ~2024)에서 멈춰 있고, 내부 파서가 2.0.20에 고정된 채 약 1년째 갱신이 없다. `mobile/`이 Kotlin
  2.1 전용 문법을 실제로 쓰면 그 파일을 못 읽을 가능성이 있다 — 문서만으로 확정 불가, MT1-01a에서
  아키텍처 테스트 1건 실행으로 직접 확인 필수.
- 카탈로그: `konsist = "0.17.3"`(§6).

### 9.3 Robolectric

- 최신 안정: **4.16.1**(2025-01-21). **API 36(Baklava) 공식 지원 확인** — compileSdk 36(§3)과
  정합. 릴리스 노트 원문 그대로 중요 제약: **"you need to use JDK 21 if running tests with SDK
  36 target"** — 이 머신의 유일한 JDK가 이미 JBR **21**(§1·§5)이라 그대로 충족된다.
- Robolectric도 KGP에 훅을 걸지 않는 JVM 테스트 러너(런타임 시뮬레이터)라 프로젝트 Kotlin 버전과
  컴파일 경로 충돌이 없다. 릴리스 노트의 "kotlin monorepo v2.2.0" 갱신 언급은 Robolectric **자체**
  빌드 도구체인 얘기이지 소비자 프로젝트의 Kotlin 제약이 아니다.
- 카탈로그: `robolectric = "4.16.1"`(§6).

### 9.4 androidx.work:work-testing

- 최신 **안정**(alpha/beta 제외): **2.11.2**(2026-03-25, 공식 릴리스 노트). `2.12.0-beta01`
  (2026-07-29)은 아직 베타 — 채택하지 않음.
- 공식 제약: **compileSdk ≥ 33**(문서 상단 고지), minSdk 23(2.11.0-alpha01부터 상향) — 둘 다
  §3의 compileSdk 36 / 계획 minSdk 29보다 낮아 문제 없음. Kotlin 버전 제약은 릴리스 노트에 별도
  명시 없음.
- `work-testing`은 `work-runtime`과 동일 버전 스킴 → **2.11.2** 채택.
- 카탈로그: `androidxWork = "2.11.2"`(§6).

### 9.5 androidx.room:room-testing

- 최신 **안정**: **2.8.4**(2025-11-19, 공식 Room 릴리스 노트에서 직접 확인). Kotlin **2.0 이상
  요구**(2.7.0-alpha13부터 고정, 2.1.0은 상위호환) — 문제 없음. minSdk 21→23 상향(2.8.0-rc02) —
  계획 minSdk 29보다 낮아 문제 없음. compileSdk 하한은 문서에 별도 명시 없음(없다는 사실 그대로
  보고, 추정 안 함). `room-testing`은 별도 아티팩트로 공식 확인(`MigrationTestHelper` 포함),
  room-runtime/room-compiler(KSP)와 동일 버전 스킴.
- **불일치 고지(추측 아님)**: 일반 웹 검색에서 "Room 3.0"(KMP-first 재작성, KSP 전용, Java
  APT/KAPT 폐지, androidx.sqlite 드라이버 기반) 관련 블로그·뉴스(2026-03~04)가 다수 발견됐으나,
  **공식 `developer.android.com/jetpack/androidx/releases/room` 릴리스 노트 페이지를 직접
  조회한 결과에는 3.x 라인이 나타나지 않았다** — 두 소스가 불일치한다. 이 세션은 공식 릴리스
  노트 페이지에서 직접 확인되는 **2.8.4를 채택**하고, Room 3.0 존재 여부·room-testing API 변경
  폭은 **미확정으로 남긴다** — MT1-01a/01d 착수 전 별도 확인 권고(추측으로 덮지 않음).
- 카탈로그: `androidxRoom = "2.8.4"`(§6).

## 10. 재현 절차

1. `D:\wp_2026\branch-console\scratchpad`(또는 임의 위치)에 최소 Android 프로젝트 생성:
   `settings.gradle.kts`(google/mavenCentral repos, `include(":app")`) + root
   `build.gradle.kts`(플러그인 버전 선언 `apply false`) + `app/build.gradle.kts`(§6 카탈로그
   그대로 적용, `jvmToolchain()` 사용 금지) + `app/src/main/AndroidManifest.xml`(최소) +
   `local.properties`(`sdk.dir=<로컬 SDK 경로>`).
2. `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.
3. 캐시된 Gradle 8.13 바이너리 직접 실행(래퍼 생성 없이):
   `~\.gradle\wrapper\dists\gradle-8.13-bin\<hash>\gradle-8.13\bin\gradle.bat tasks --console=plain`.
4. `BUILD SUCCESSFUL`이면 통과.

## 11. 검증

- 스모크: `gradle tasks` → **BUILD SUCCESSFUL in 5s**, 신규 다운로드 0건(§5).
- `mobile/` 미생성 확인: `git status --short`(branch-console) — 본 문서 외 무변경.
- kotlin_krx 무변경 확인: `git status --short`(kotlin_krx) — 기존 미추적 파일 5건 외 무변경,
  `origin/main..HEAD` 및 역방향 모두 빈 결과(§2).
- 인용 URL(§3, §4, §9)은 2026-08-07 실시간 조회 결과이며 추측이 아니다 — 매직넘버가 아닌
  카탈로그 값이므로 SSOT 규칙(CLAUDE.md §1)과 무관하다(코드 하드코딩이 아니라
  `gradle/libs.versions.toml` 후보안).
- 병렬 워커(MT1-02a)가 스테이징한 `contracts/snapshots/`·`scripts/gen_contract_snapshots.py`·
  `tests/test_contracts_snapshot.py`는 본 세션에서 손대지 않았다 — 커밋 대상은 본 문서 1건뿐.

## 12. 생성/변경 파일 목록

- `docs/journal/2026-08-07_MT1-00e_toolchain_matrix.md`(본 문서, §9 추가 갱신 포함)
- 그 외 branch-console 저장소 변경 없음(MT1-02a의 미커밋 파일은 불간섭).
- `D:\android_2025\kotlin_krx`: 변경 없음(읽기·`git log`만 수행).
- 스모크 프로젝트(`scratchpad/smoke/`)는 session-scoped 임시 디렉터리로 저장소 밖에 위치, 이
  커밋에 포함되지 않는다.
