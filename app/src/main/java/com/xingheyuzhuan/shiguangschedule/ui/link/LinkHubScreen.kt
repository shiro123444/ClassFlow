package com.xingheyuzhuan.shiguangschedule.ui.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubFact
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubNode
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubOrigin
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubUrl
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuLoadingPlaceholder

/**
 * 通用链接节点读侧页面：展示节点内容与来源，用户确认后才落地。
 *
 * 页面外壳（标题栏「分享的内容」+ 卡片）**只在真正需要用户读内容时才出现**：
 * 解析中、正在落地、以及处理器声明 `autoApply` / `autoApplyInline`（如 `campus_shower`）的免确认节点，
 * 全程只有一层与目标页首屏同款的全屏品牌过渡 —— 因此免确认链接不会「先把确认页闪一下再跳走」。
 * 一旦外壳出现过（进入确认卡片）就保持，避免用户点「应用」后状态回到 Applying 又缩回过渡态。
 *
 * 入口：NFC 触碰、外部链接（VIEW）、扫一扫；解析规则见 [LinkHubUrl] 与 `LINK_HUB_PROTOCOL.md`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkHubScreen(
    navBridge: NavBridge,
    code: String?,
    inline: String?,
    origin: String? = null,
    viewModel: LinkHubViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val copiedHint = stringResource(R.string.link_hub_copied)
    val rawLink = remember(code, inline, origin) { rebuildLink(code, inline, origin) }

    // 是否需要页面外壳（标题栏 + 卡片）。免确认节点从第一帧到跳转全程为 false：
    // 没有外壳就没有「确认页」，也就不存在「闪一道确认页」。
    var shellShown by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is LinkHubUiState.Resolving && state !is LinkHubUiState.Applying) shellShown = true
    }

    LaunchedEffect(code, inline, origin) { viewModel.start(code, inline, origin) }

    // 应用成功后跳内置 WebView：本页是过渡页，直接替换栈顶
    LaunchedEffect(Unit) {
        viewModel.openWebView.collect { url ->
            // 复用内置 WebView，但隐藏 WBU 专用的「导入课程」引导栏
            navBridge.replace(Destination.WebView(initialUrl = url, hideImportBar = true))
        }
    }

    // 免确认类型（如 campus_shower）：解析成功即直接进入对应网页应用容器
    LaunchedEffect(Unit) {
        viewModel.openWebApp.collect { request ->
            navBridge.replace(
                Destination.WebApp(
                    appId = request.appId,
                    initialTargetUrl = request.initialUrl,
                    pendingAutoScan = request.pendingAutoScan
                )
            )
        }
    }

    BackHandler { navBridge.popBackStack() }

    // 过渡态：全屏品牌动画，与 Destination.WebApp 的首屏是同一个组件，硬切时像素一致
    if (!shellShown) {
        WbuLoadingPlaceholder()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.link_hub_title)) },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.link_hub_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val current = state) {
                LinkHubUiState.Resolving -> LoadingBlock(stringResource(R.string.link_hub_resolving))

                LinkHubUiState.Applying -> LoadingBlock(stringResource(R.string.link_hub_applying))

                is LinkHubUiState.Confirm -> ConfirmBlock(
                    node = current.node,
                    facts = current.facts,
                    onApply = viewModel::apply,
                    onCancel = { navBridge.popBackStack() }
                )

                is LinkHubUiState.Unsupported -> UnsupportedBlock(
                    state = current,
                    onCopy = {
                        copyToClipboard(context, rawLink)
                        Toast.makeText(context, copiedHint, Toast.LENGTH_SHORT).show()
                    },
                    onBack = { navBridge.popBackStack() }
                )

                is LinkHubUiState.Failed -> FailedBlock(
                    messageRes = current.messageRes,
                    retryable = current.retryable,
                    openInBrowserUrl = current.openInBrowserUrl,
                    needsLogin = current.needsLogin,
                    onLogin = { showAuthSheet = true },
                    onRetry = viewModel::retry,
                    onOpenInBrowser = { url -> openInExternalBrowser(context, url) },
                    onBack = { navBridge.popBackStack() }
                )

                is LinkHubUiState.Applied -> AppliedBlock(
                    messageRes = current.messageRes,
                    onDone = { navBridge.popBackStack() }
                )
            }
        }
    }

    // 「重新登录」入口：登录态缺失时以前只能反复点重试，登录成功后自动把刚才的节点再落地一次
    if (showAuthSheet) {
        WbuCampusAuthSheet(
            onDismiss = { showAuthSheet = false },
            onLoginSuccess = {
                showAuthSheet = false
                viewModel.onLoginSuccess()
            },
            requireUnifiedCas = true,
            // 节点落地只需要统一认证会话：不校验校园网、不登录教务
            unifiedAuthOnly = true,
            tipsScenario = WbuAuthTipsScenario.IDENTITY,
            title = stringResource(R.string.title_login_unified_auth)
        )
    }
}

/** 用规范化形式重建原始链接（供「复制链接」使用）。 */
private fun rebuildLink(code: String?, inline: String?, origin: String?): String = when {
    // 内嵌链接对外只认 hub 域名：复制出来给别人扫，不应带上本机联调地址
    !inline.isNullOrEmpty() -> LinkHubUrl.buildInlineUrl(payload = inline, code = code)
    !code.isNullOrEmpty() -> LinkHubUrl.buildCodeUrl(code = code, origin = origin)
    else -> ""
}

/**
 * 用系统浏览器打开地址。
 *
 * 用于「服务端没按 JSON 返回」时的逃生口：这种页面通常自己会跳 `intent://` 唤起 App，
 * 那是浏览器（有用户手势、认 App Links）的活，塞进内置 WebView 只会得到
 * 「网页无法打开 / ERR_UNKNOWN_URL_SCHEME」。
 */
private fun openInExternalBrowser(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        Toast.makeText(context, context.getString(R.string.link_hub_error_network), Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("ClassFlow", text))
}

@StringRes
private fun typeLabelRes(type: String): Int = when (type.trim().lowercase()) {
    LinkHubType.OPEN -> R.string.link_hub_type_open
    LinkHubType.TEXT -> R.string.link_hub_type_text
    LinkHubType.PROXY -> R.string.link_hub_type_proxy
    LinkHubType.LAYOUT_PLUGIN -> R.string.link_hub_type_plugin
    else -> R.string.link_hub_type_unknown
}

/**
 * 卡片内的加载态。
 *
 * 只在**外壳已经出现**（即用户已经看到过确认卡片）之后的中间态使用：
 * 此时页面是「标题栏 + 卡片」的形态，加载指示必须留在卡片里，
 * 不能用会填满全屏的品牌过渡（它嵌在可滚动 Column 中，无限高度约束下测不出来）。
 */
@Composable
private fun LoadingBlock(label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(44.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkHubCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun ConfirmBlock(
    node: LinkHubNode,
    facts: List<LinkHubFact>,
    onApply: () -> Unit,
    onCancel: () -> Unit
) {
    val envelope = node.envelope
    val typeLabel = stringResource(typeLabelRes(envelope.type))
    val inline = node.origin == LinkHubOrigin.INLINE

    LinkHubCard {
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        // 内嵌形态不携带 title：此时只显示类型标签，避免标题与标签重复
        envelope.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        envelope.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (facts.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
            facts.forEach { fact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(fact.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = fact.value,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
        Text(
            text = stringResource(
                if (inline) R.string.link_hub_origin_inline else R.string.link_hub_origin_server
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (inline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
        if (inline) {
            Text(
                text = stringResource(R.string.link_hub_inline_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.link_hub_apply))
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.link_hub_cancel))
            }
        }
    }
}

@Composable
private fun UnsupportedBlock(
    state: LinkHubUiState.Unsupported,
    onCopy: () -> Unit,
    onBack: () -> Unit
) {
    val typeText = state.type.ifBlank { stringResource(R.string.link_hub_type_unknown) }

    LinkHubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.link_hub_unsupported_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = if (state.reserved) {
                stringResource(R.string.link_hub_reserved_type)
            } else {
                stringResource(R.string.link_hub_unsupported_type, typeText)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.link_hub_copy_link))
            }
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.link_hub_back))
            }
        }
    }
}

@Composable
private fun FailedBlock(
    @StringRes messageRes: Int,
    retryable: Boolean,
    openInBrowserUrl: String?,
    needsLogin: Boolean,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onBack: () -> Unit
) {
    LinkHubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (needsLogin) {
                Button(
                    onClick = onLogin,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_relogin))
                }
            }
            if (retryable) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.link_hub_retry))
                }
            }
            if (openInBrowserUrl != null) {
                OutlinedButton(
                    onClick = { onOpenInBrowser(openInBrowserUrl) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.link_hub_open_in_browser))
                }
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.link_hub_back))
            }
        }
    }
}

@Composable
private fun AppliedBlock(@StringRes messageRes: Int, onDone: () -> Unit) {
    LinkHubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        ) {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.link_hub_done))
            }
        }
    }
}
