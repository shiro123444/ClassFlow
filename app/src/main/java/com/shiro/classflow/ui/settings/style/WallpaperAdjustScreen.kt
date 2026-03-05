package com.shiro.classflow.ui.settings.style

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.shiro.classflow.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperAdjustScreen(
    onBack: () -> Unit,
    viewModel: StyleSettingsViewModel = viewModel(factory = StyleSettingsViewModelFactory)
) {
    val styleState by viewModel.styleState.collectAsStateWithLifecycle()
    val demoUiState by viewModel.demoUiState.collectAsStateWithLifecycle()

    var phoneSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val persistTransform = {
        viewModel.updateWallpaperTransform(scale, offsetX, offsetY)
    }

    BackHandler {
        persistTransform()
        onBack()
    }

    LaunchedEffect(styleState?.backgroundImagePath) {
        styleState?.let { style ->
            scale = style.backgroundScale
            offsetX = style.backgroundOffsetX
            offsetY = style.backgroundOffsetY
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            persistTransform()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("壁纸微调") },
                navigationIcon = {
                    IconButton(onClick = {
                        persistTransform()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            viewModel.resetWallpaperTransform()
                        }
                    ) {
                        Text(stringResource(R.string.action_reset_wallpaper_position))
                    }
                }
            )
        }
    ) { innerPadding ->
        val style = styleState

        if (style == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (style.backgroundImagePath.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请先在个性化配置中选择壁纸",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.label_wallpaper_gesture_adjust),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    PhonePreviewShell(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .aspectRatio(9f / 19.5f)
                            .offset(y = 56.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { phoneSize = it }
                        ) {
                            val widthPx = phoneSize.width.toFloat().coerceAtLeast(1f)
                            val heightPx = phoneSize.height.toFloat().coerceAtLeast(1f)
                            val previewDimAlpha = style.backgroundDimAlpha.coerceAtMost(0.35f)
                            val wallpaperModel = remember(style.backgroundImagePath) { File(style.backgroundImagePath) }

                            AsyncImage(
                                model = wallpaperModel,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = widthPx * offsetX
                                        translationY = heightPx * offsetY
                                    },
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = previewDimAlpha)
                                    )
                            )

                            ScheduleGridContent(
                                style = style,
                                demoUiState = demoUiState,
                                drawBackground = false
                            )

                            PhoneStatusBarOverlay()

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(style.backgroundImagePath, phoneSize) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val dragWidthPx = phoneSize.width.toFloat().coerceAtLeast(1f)
                                            val dragHeightPx = phoneSize.height.toFloat().coerceAtLeast(1f)
                                            val newOffsetX = (offsetX + dragAmount.x / dragWidthPx).coerceIn(-0.5f, 0.5f)
                                            val newOffsetY = (offsetY + dragAmount.y / dragHeightPx).coerceIn(-0.5f, 0.5f)
                                            offsetX = newOffsetX
                                            offsetY = newOffsetY
                                        }
                                    }
                                    .pointerInput(style.backgroundImagePath, phoneSize) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val transformWidthPx = phoneSize.width.toFloat().coerceAtLeast(1f)
                                            val transformHeightPx = phoneSize.height.toFloat().coerceAtLeast(1f)
                                            val newScale = (scale * zoom).coerceIn(0.8f, 5f)
                                            val newOffsetX = (offsetX + pan.x / transformWidthPx).coerceIn(-0.5f, 0.5f)
                                            val newOffsetY = (offsetY + pan.y / transformHeightPx).coerceIn(-0.5f, 0.5f)
                                            scale = newScale
                                            offsetX = newOffsetX
                                            offsetY = newOffsetY
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 78.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_wallpaper_transform_values, scale, offsetX, offsetY),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "支持双指缩放与拖动；退出页面时自动保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

@Composable
private fun PhonePreviewShell(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(34.dp), clip = false)
            .clip(RoundedCornerShape(34.dp))
            .background(Color(0xFF111722))
            .border(1.dp, Color(0xFF2A3242), RoundedCornerShape(34.dp))
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.surface),
            content = content
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .width(106.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.78f))
        )
    }
}

@Composable
private fun PhoneStatusBarOverlay() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "9:41",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.92f),
            fontWeight = FontWeight.SemiBold
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f))
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f))
            )
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 1.dp)
                        .width(10.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.92f))
                )
            }
        }
    }
}
