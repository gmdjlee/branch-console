package com.branchconsole.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.branchconsole.app.notif.NotificationChannels
import com.branchconsole.app.production.BranchConsoleWorkerFactory
import com.branchconsole.app.tick.ConfirmTickWorker

private const val TAG = "BranchConsoleApp"

/**
 * MT1-06 프로덕션 진입점 (aaa F-1 해소) — 이전 판은 `ConfirmTickWorker.schedulePeriodic`/
 * `triggerCatchupNow`를 호출하는 곳이 앱 어디에도 없어 확정 틱 파이프라인이 실기기에서
 * 영원히 실행되지 않았다.
 *
 * `runCatching`으로 감싸는 이유: `confirm_time_kst`가 SSOT에 아직 없으면(AD-3b 실측 대기,
 * MT1-00g) [ConfirmTickWorker.schedulePeriodic]이 **명시적으로** 예외를 던진다(조용한 17:00
 * 기본값 금지, CLAUDE.md §1). 그 실패로 **앱 전체가 기동 실패**하면 확정 틱 기능 하나 때문에
 * 다른 모든 화면이 죽는다 — 로그로 드러내고 다음 실행(그 사이 값이 채워지면)에 다시 시도하는
 * 쪽이 올바른 절충이다. 로더 자체의 명시 실패 계약은 그대로다(여기서 삼키는 것은 "앱을 끄느냐"
 * 라는 상위 판단일 뿐, 실패 자체를 숨기지 않는다 — `Log.e`로 남는다).
 *
 * MT1-08c/08d — `Configuration.Provider`를 구현해 [BranchConsoleWorkerFactory]를 등록한다.
 * `ConfirmTickWorker.schedulePeriodic`가 못박은 `PeriodicWorkRequestBuilder<ConfirmTickWorker>`
 * 클래스명을 그 팩토리가 가로채 `ProductionConfirmTickWorker`(dailyCollect 실구현)로 바꿔
 * 낸다 — `tick/ConfirmTickWorker.kt`는 한 줄도 고치지 않는다. **매니페스트 변경이 필요하다**
 * (`AndroidManifest.xml`의 `InitializationProvider` meta-data 제거) — 실측(바이트코드
 * 디컴파일)에 따르면 androidx.startup 기반 기본 `WorkManagerInitializer`가 `Application#onCreate`
 * 이전에 무조건 기본 `Configuration`으로 WorkManager를 즉시 초기화해 버리므로, 그 초기화기를
 * 매니페스트에서 제거해야만 `WorkManagerImpl.getInstance()`의 지연 초기화 경로(그 안에서만
 * `Configuration.Provider` instanceof 검사가 일어난다)가 살아난다.
 *
 * MT1-08a — 노티 채널 3종을 앱 시작마다 확정한다(멱등). 국면 전이·틱 실패 노티는 별도 폴링
 * 워커가 아니라 [com.branchconsole.app.production.ProductionConfirmTickWorker.doWork]가
 * `super.doWork()` 직후 직접 확인한다(aaa M-5 — 확정 틱 완료 시점에 정확히 물려 K-15 표면·
 * 지연을 늘리지 않는다). `provisional_alert`는 프리뷰 갱신 버튼(홈 화면)이 그때그때 확인한다.
 */
class BranchConsoleApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(BranchConsoleWorkerFactory()).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        runCatching {
            ConfirmTickWorker.schedulePeriodic(this)
            ConfirmTickWorker.triggerCatchupNow(this)
        }.onFailure { e ->
            Log.e(TAG, "confirm tick scheduling failed (will retry on next app launch)", e)
        }
    }
}
