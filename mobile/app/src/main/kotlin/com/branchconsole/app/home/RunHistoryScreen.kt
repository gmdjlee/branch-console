package com.branchconsole.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.RunLogEntity

/**
 * MT1-08b — 실행 이력 화면. 존재 이유(M1_PLAN_C.md §4.6): "왜 오늘 알림이 없었는가"에 답한다 —
 * `run_log`를 최신순으로 그대로 노출한다(K-15 누락 노출).
 */
@Composable
fun RunHistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { LakeDatabase.build(context) }
    DisposableEffect(Unit) { onDispose { db.close() } }

    var rows by remember { mutableStateOf<List<RunLogEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        rows = db.runLogDao().allOrderedByRanAt().asReversed()
    }

    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("실행 이력", style = MaterialTheme.typography.headlineSmall)
        if (rows.isEmpty()) {
            Text("실행 이력이 없습니다")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows) { row -> RunLogRow(row) }
            }
        }
    }
}

@Composable
private fun RunLogRow(row: RunLogEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("${row.tradingDate ?: "-"} · ${row.status}", style = MaterialTheme.typography.titleSmall)
            row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
