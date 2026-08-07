package com.branchconsole.app.collectors

import android.content.Context
import com.branchconsole.app.collectors.fred.FredCollector
import com.branchconsole.app.collectors.krx.KrxCollector
import com.branchconsole.app.collectors.yahoo.YahooCollector
import com.branchconsole.app.credentials.CredentialsStore

/**
 * MT1-08c/08d — KRX·야후·FRED 3어댑터 조립 지점. ECOS·CDS는 여기 없다(00b/00d 확정 미수집 —
 * [WarmupBackfillOrchestrator.DEFAULT_NOT_COLLECTED]와 동일 근거, ECOS는 키가 있어도 소비되지
 * 않는다). 일일 확정 수집(`ProductionConfirmTickWorker.dailyCollect`)과 프리뷰 갱신
 * (`PreviewRefreshUseCase`) 둘 다 이 팩토리를 공유한다 — 조립 로직을 두 곳에 중복시키지 않는다.
 */
object CollectorFactory {
    fun createAll(
        context: Context,
        credentialsStore: CredentialsStore,
    ): List<Collector> =
        listOf(
            KrxCollector.create(context, credentialsStore.krxCredentialsProvider()),
            YahooCollector.create(context),
            FredCollector.create(context, credentialsStore.fredCredentialsProvider()),
        )
}
