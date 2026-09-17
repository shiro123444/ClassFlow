package com.xingheyuzhuan.shiguangschedule.ui.campus.ujing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingWaterScreen(
    cd: String,
    navBridge: NavBridge,
    viewModel: UjingWaterViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var hasStarted by rememberSaveable(cd) { mutableStateOf(false) }

    LaunchedEffect(cd) {
        if (cd.isNotBlank() && !hasStarted) {
            hasStarted = true
            viewModel.start(cd)
        }
    }

    BackHandler {
        UjingWaterViewModel.clearSession()
        navBridge.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_ujing_water)) },
                navigationIcon = {
                    IconButton(onClick = {
                        UjingWaterViewModel.clearSession()
                        navBridge.popBackStack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_exit)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when (val stage = uiState.stage) {
                is UjingWaterUiStage.Splash -> {
                    SplashScreenView()
                }

                is UjingWaterUiStage.Loading -> {
                    LoadingView(message = stage.message)
                }

                is UjingWaterUiStage.Active -> {
                    ActiveDispensingView(
                        stage = stage,
                        onDone = {
                            UjingWaterViewModel.clearSession()
                            navBridge.popBackStack()
                        }
                    )
                }

                is UjingWaterUiStage.Finished -> {
                    FinishedView(
                        stage = stage,
                        onDone = {
                            UjingWaterViewModel.clearSession()
                            navBridge.popBackStack()
                        },
                        onContinueScan = {
                            UjingWaterViewModel.clearSession()
                            navBridge.replace(Destination.QrScan)
                        }
                    )
                }

                is UjingWaterUiStage.Error -> {
                    ErrorView(
                        message = stage.message,
                        canRetry = stage.canRetry,
                        onRetry = viewModel::retry,
                        onExit = {
                            UjingWaterViewModel.clearSession()
                            navBridge.popBackStack()
                        }
                    )
                }
            }

            if (uiState.needLogin) {
                WbuCampusAuthSheet(
                    onDismiss = { viewModel.onLoginDismissed() },
                    onLoginSuccess = { viewModel.onLoginSuccess() },
                    requireUnifiedCas = true,
                    unifiedAuthOnly = true,
                    title = stringResource(R.string.service_campus_card),
                    tipsScenario = WbuAuthTipsScenario.IDENTITY
                )
            }
        }
    }
}

/**
 * 居中过场动画视图：复用 U净 饮水机自带的 splash 动效图。
 */
@Composable
private fun SplashScreenView() {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "splashScale"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + scaleIn(tween(400)),
            exit = fadeOut(tween(300))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ujing_water_splash),
                    contentDescription = stringResource(R.string.title_ujing_water),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(width = 200.dp, height = 238.dp)
                        .scale(scale)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ujing_water_connecting),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ujing_water_splash),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 120.dp, height = 143.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActiveDispensingView(
    stage: UjingWaterUiStage.Active,
    onDone: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stage.detail?.orderStatusName ?: stringResource(R.string.ujing_water_dispensing),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stage.subject.subjectName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )

                if (stage.mqttConnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ujing_water_realtime_connected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 实时取水量卡片
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.ujing_water_order_info),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow(label = stringResource(R.string.ujing_water_hot_amount), value = "${stage.detail?.hotWaterMl ?: 0} mL")
                InfoRow(label = stringResource(R.string.ujing_water_warm_amount), value = "${stage.detail?.warmWaterMl ?: 0} mL")
                InfoRow(label = stringResource(R.string.ujing_water_pay_type), value = stage.detail?.payTypeName ?: "一卡通免密")
                InfoRow(label = stringResource(R.string.ujing_water_order_no), value = stage.orderResult.orderNo ?: "${stage.orderResult.orderId}")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.ujing_water_action_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_qr_scan_done))
        }
    }
}

@Composable
private fun FinishedView(
    stage: UjingWaterUiStage.Finished,
    onDone: () -> Unit,
    onContinueScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ujing_water_finished_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stage.subject.subjectName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                InfoRow(label = stringResource(R.string.ujing_water_hot_amount), value = "${stage.detail.hotWaterMl} mL")
                InfoRow(label = stringResource(R.string.ujing_water_warm_amount), value = "${stage.detail.warmWaterMl} mL")
                InfoRow(label = stringResource(R.string.ujing_water_cost), value = "¥ ${String.format("%.2f", stage.detail.payPrice)}")
                InfoRow(label = stringResource(R.string.ujing_water_pay_type), value = stage.detail.payTypeName ?: "一卡通免密")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_qr_scan_done))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onContinueScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_continue_scan))
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.title_load_failed),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExit) {
                Text(stringResource(R.string.action_exit))
            }
            if (canRetry) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
