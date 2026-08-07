package com.branchconsole.app.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.branchconsole.app.collectors.CollectorFactory
import com.branchconsole.app.credentials.CredentialsStore
import com.branchconsole.app.format.formatComposite
import com.branchconsole.app.format.formatCoveragePercent
import com.branchconsole.app.preview.PreviewRefreshUseCase
import com.branchconsole.app.preview.PreviewResult
import com.branchconsole.lake.LakeDatabase
import kotlinx.coroutines.launch
import java.time.Instant

// M1 최소 터치 타깃(브리프 §4.3 "터치 타깃 >= 48dp" — 회귀 방지 목적, §2.4 전면 적용은 M2).
private val MIN_TOUCH_TARGET = 48.dp

private fun homeStateLabel(state: HomeState): String =
    when (state) {
        HomeState.NORMAL -> "정상"
        HomeState.PARTIAL -> "부분 결측"
        HomeState.SUPPRESSED -> "국면 판정 불가 (참고용)"
        HomeState.WARMUP -> "이력 수집 중"
        HomeState.GAP -> "공백 이후"
        HomeState.ERROR -> "오류"
        HomeState.EMPTY -> "최초 실행 대기"
    }

/**
 * MT1-08b — 기능판 홈(단일 스크롤 화면, M1_PLAN_C.md §4.3). 국면·composite·상위 지표·마지막 틱
 * 시각 + 프리뷰 갱신 버튼(PREVIEW 배지·as_of·coverage 표기). 색만이 아니라 텍스트 라벨을
 * 항상 동반한다(이중 부호화, §4.5 규칙 1을 M1 배너에도 선적용).
 *
 * DB/네트워크 접근은 화면 자신이 코루틴으로 직접 수행한다 — M1은 기능 검증용이라 별도
 * ViewModel 계층을 두지 않는다(레포에 선례 없음, M2에서 필요해지면 도입). 섹션별 하위
 * composable로 나눈 것은 순전히 가독성·복잡도 관리 목적(detekt LongMethod/CyclomaticComplexity).
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { LakeDatabase.build(context) }
    DisposableEffect(Unit) { onDispose { db.close() } }

    var uiState by remember { mutableStateOf<HomeUiState?>(null) }
    var previewResult by remember { mutableStateOf<PreviewResult?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadToken, previewResult) {
        uiState = HomeData.load(context, db, previewResult)
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val state = uiState
        if (state == null) {
            CircularProgressIndicator()
            return@Column
        }

        HomeStateBanner(state.state)
        HomeSummarySection(state)
        HomeIndicatorsSection(state)
        HomeLastTickSection(state)
        HomePreviewSection(
            state = state,
            previewError = previewError,
            refreshing = refreshing,
            onRefreshClick = {
                refreshing = true
                previewError = null
                scope.launch {
                    runCatching { runPreviewRefresh(context, db) }
                        .onSuccess { previewResult = it }
                        .onFailure { previewError = it.message ?: it::class.simpleName }
                    refreshing = false
                    reloadToken++
                }
            },
        )
    }
}

@Composable
private fun HomeStateBanner(state: HomeState) {
    if (state == HomeState.NORMAL) return
    Card(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "상태: ${homeStateLabel(state)}" }) {
        Text(
            text = "[$state] ${homeStateLabel(state)}",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun HomeSummarySection(state: HomeUiState) {
    Text("국면: ${state.phase ?: "미정"}", style = MaterialTheme.typography.headlineSmall)
    Text("composite: ${formatComposite(state.composite)}")
    state.confirmedCoverage?.let { Text("확정 coverage: ${formatCoveragePercent(it)}") }
    state.registryVersion?.let { Text("registry: $it") }
    if (!state.notificationsEnabled) {
        Text(
            "알림이 꺼져 있습니다 — 국면 전이·틱 실패 알림을 받으려면 알림 권한을 허용하세요.",
            modifier = Modifier.semantics { contentDescription = "알림 권한 꺼짐" },
        )
    }
}

@Composable
private fun HomeIndicatorsSection(state: HomeUiState) {
    Text("상위 지표", style = MaterialTheme.typography.titleMedium)
    if (state.topIndicators.isEmpty()) {
        Text("표시할 지표가 없습니다")
    } else {
        state.topIndicators.forEach { indicator ->
            Text("${indicator.id} (${indicator.axis ?: "-"}) : severity=${indicator.severity ?: "결측"}")
        }
    }
}

@Composable
private fun HomeLastTickSection(state: HomeUiState) {
    Text("마지막 확정 틱", style = MaterialTheme.typography.titleMedium)
    Text(state.lastTickDate?.let { it + if (state.lastTickIsCatchup) " (소급)" else "" } ?: "없음")
    state.gapReason?.let { Text("공백 사유: $it") }
    Text("최근 실행: ${state.lastRunStatus ?: "-"} ${state.lastRunDetail.orEmpty()}")
}

@Composable
private fun HomePreviewSection(
    state: HomeUiState,
    previewError: String?,
    refreshing: Boolean,
    onRefreshClick: () -> Unit,
) {
    Text("프리뷰", style = MaterialTheme.typography.titleMedium)
    state.preview?.let { preview ->
        val composite = formatComposite(preview.filledComposite)
        val coverage = formatCoveragePercent(preview.rawCoverage)
        val asOf = Instant.ofEpochMilli(preview.asOfEpochMillis)
        Text("PREVIEW · composite $composite · coverage $coverage · as_of $asOf")
        if (preview.suppressed) Text("국면 판정 불가 (커버리지 부족 — 참고용)")
        if (preview.staleIndicators.isNotEmpty()) {
            val ids = preview.staleIndicators.joinToString { it.id }
            Text("이월 · 스테일: $ids", modifier = Modifier.semantics { contentDescription = "이월된 스테일 지표: $ids" })
        }
    }
    previewError?.let { Text("프리뷰 실패: $it") }
    Button(modifier = Modifier.sizeIn(minHeight = MIN_TOUCH_TARGET), enabled = !refreshing, onClick = onRefreshClick) {
        Text(if (refreshing) "갱신 중..." else "프리뷰 갱신")
    }
}

private suspend fun runPreviewRefresh(
    context: Context,
    db: LakeDatabase,
): PreviewResult {
    val credentialsStore = CredentialsStore.create(context)
    val collectors = CollectorFactory.createAll(context, credentialsStore)
    return PreviewRefreshUseCase(context, db, collectors).refresh()
}
