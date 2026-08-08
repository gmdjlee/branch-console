package com.branchconsole.app.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.branchconsole.app.AppInfo
import com.branchconsole.app.credentials.CredentialFields
import com.branchconsole.app.credentials.CredentialsStore
import com.branchconsole.app.diagnostics.DiagnosticExport
import com.branchconsole.app.notif.NotificationGate
import com.branchconsole.lake.LakeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * MT1-08c/08d — 자격증명 입력·[검증]·OEM 절전 예외 안내·알림 권한 요청을 한 화면에 담는다
 * (브리프 §3 "온보딩 최소" — 별도 마법사 단계 없이 설정 화면 하나로 최초 온보딩과 이후 편집을
 * 겸한다, M1은 기능 검증용). ECOS는 00b 확정(BLOCKED)이라 "선택(미발급 시 관련 지표 미수집)"
 * 으로 표기한다(브리프 §4). `ANTHROPIC_API_KEY` 입력란은 없다(M1은 LLM 미호출).
 *
 * 섹션별 하위 composable로 나눈 것은 순전히 가독성·복잡도 관리 목적(detekt LongMethod/
 * CyclomaticComplexity).
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // K-17 storage relies on the real AndroidKeyStore provider, which some environments lack
    // (e.g. Robolectric/JVM tests -- see CredentialsStoreTest KDoc). Guard against a hard crash
    // and surface it as an inline error instead (same "fail visibly, don't take the screen down
    // with it" judgment as BranchConsoleApplication's runCatching around WorkManager scheduling).
    val store = remember { runCatching { CredentialsStore.create(context) }.getOrNull() }
    val scope = rememberCoroutineScope()

    var fields by remember { mutableStateOf(CredentialFields()) }
    LaunchedEffect(store) { store?.let { fields = it.load() } }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall)
        Text(AppInfo.MODULE_NAME) // registry_version은 홈 화면에 표시(HomeData.registryVersion).

        NotificationPermissionSection(context)
        BatteryOptimizationSection(context)
        CredentialsSection(store = store, fields = fields, onFieldsChange = { fields = it }, scope = scope)
        DiagnosticExportSection(context, scope)
    }
}

/**
 * MT1-08d — 진단 JSON 내보내기 진입점(docs/runbooks/M1_SMOKE.md S-1~S-4 증빙 수집). SAF
 * `CreateDocument`로 사용자가 저장 위치를 고르게 한다 — 자동 업로드·고정 경로 쓰기 없음(K-17
 * 유출 표면 최소화와 같은 판단).
 */
@Composable
private fun DiagnosticExportSection(
    context: Context,
    scope: CoroutineScope,
) {
    val db = remember { LakeDatabase.build(context) }
    DisposableEffect(Unit) { onDispose { db.close() } }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingJson
            status =
                if (uri == null || json == null) {
                    "내보내기 취소됨"
                } else {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                        .fold({ "저장 완료" }, { "저장 실패: ${it.message}" })
                }
        }

    Text("진단 내보내기 (MT1-08d)", style = MaterialTheme.typography.titleMedium)
    Button(onClick = {
        scope.launch {
            pendingJson = DiagnosticExport.build(context, db)
            saveLauncher.launch(DiagnosticExport.fileName())
        }
    }) {
        Text("진단 JSON 내보내기")
    }
    status?.let { Text(it) }
}

@Composable
private fun NotificationPermissionSection(context: Context) {
    var notificationsEnabled by remember { mutableStateOf(NotificationGate.isEnabled(context)) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsEnabled = NotificationGate.isEnabled(context)
        }

    Text("알림 권한: ${if (notificationsEnabled) "허용됨" else "꺼짐"}")
    if (!notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("알림 권한 요청")
        }
    }
}

@Composable
private fun BatteryOptimizationSection(context: Context) {
    Text("절전 예외(K-15)", style = MaterialTheme.typography.titleMedium)
    val ignoringBatteryOptimizations = remember { BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) }
    if (!ignoringBatteryOptimizations) {
        Text(BatteryOptimizationHelper.guidanceText(Build.MANUFACTURER))
        Button(onClick = { context.startActivity(BatteryOptimizationHelper.openSettingsIntent()) }) {
            Text("배터리 설정 열기")
        }
    } else {
        Text("절전 예외가 이미 허용되어 있습니다")
    }
}

@Composable
private fun CredentialsSection(
    store: CredentialsStore?,
    fields: CredentialFields,
    onFieldsChange: (CredentialFields) -> Unit,
    scope: CoroutineScope,
) {
    var fredVerify by remember { mutableStateOf<String?>(null) }
    var krxVerify by remember { mutableStateOf<String?>(null) }

    Text("자격증명", style = MaterialTheme.typography.titleMedium)
    if (store == null) {
        Text("암호화 저장소를 열 수 없습니다 — 기기 보안 저장소(Keystore) 상태를 확인하세요.")
    }
    OutlinedTextField(
        value = fields.krxId.orEmpty(),
        onValueChange = { onFieldsChange(fields.copy(krxId = it)) },
        label = { Text("KRX ID") },
    )
    OutlinedTextField(
        value = fields.krxPassword.orEmpty(),
        onValueChange = { onFieldsChange(fields.copy(krxPassword = it)) },
        label = { Text("KRX 비밀번호") },
    )
    Button(onClick = {
        // 실기기 S-0 — 검증 경로도 CredentialFields.trimmed()를 거친다. 결정적 초크포인트는
        // CredentialsStore.load(legacy 치유 포함)이고, 여기는 미저장 폼 입력 커버용(aaa O-2).
        scope.launch {
            val trimmed = fields.trimmed()
            krxVerify = CredentialVerification.verifyKrx(trimmed.krxId.orEmpty(), trimmed.krxPassword.orEmpty()).label()
        }
    }) {
        Text("KRX 검증")
    }
    krxVerify?.let { Text(it) }

    OutlinedTextField(
        value = fields.fredApiKey.orEmpty(),
        onValueChange = { onFieldsChange(fields.copy(fredApiKey = it)) },
        label = { Text("FRED API 키") },
    )
    Button(onClick = {
        scope.launch { fredVerify = CredentialVerification.verifyFred(fields.trimmed().fredApiKey.orEmpty()).label() }
    }) {
        Text("FRED 검증")
    }
    fredVerify?.let { Text(it) }

    OutlinedTextField(
        value = fields.ecosApiKey.orEmpty(),
        onValueChange = { onFieldsChange(fields.copy(ecosApiKey = it)) },
        label = { Text("ECOS API 키 (선택 — 미발급 시 관련 지표 미수집)") },
    )
    // 실기기 관찰(S-0 계열) — KIS appkey 입력 중 다음 필드(appsecret)가 키보드에 가려짐.
    // 귀속: imeAction=Next + FocusRequester 체이닝 부재(OutlinedTextField 기본값은 Done) —
    // 자동 스크롤/포커스 이동이 없어 verticalScroll만으로는 안 보인다. M2 UI 정비 이관, 여기서는
    // 수정하지 않는다(구조 변경 금지 범위 밖).
    OutlinedTextField(
        value = fields.kisAppKey.orEmpty(),
        onValueChange = { onFieldsChange(fields.copy(kisAppKey = it)) },
        label = { Text("KIS appkey (선택)") },
    )
    OutlinedTextField(
        value = fields.kisAppSecret.orEmpty(),
        onValueChange = { onFieldsChange(fields.copy(kisAppSecret = it)) },
        label = { Text("KIS appsecret (선택)") },
    )

    Button(enabled = store != null, onClick = { store?.save(fields) }) { Text("저장") }
}
