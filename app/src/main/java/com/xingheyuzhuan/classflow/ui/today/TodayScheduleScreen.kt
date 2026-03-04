package com.xingheyuzhuan.classflow.ui.today

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.data.model.ScheduleGridStyle
import com.xingheyuzhuan.classflow.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.classflow.ui.theme.ThemeGradients
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.res.stringResource

@Composable
fun TodayScheduleScreen(
    navController: NavHostController,
    viewModel: TodayScheduleViewModel = viewModel(
        factory = TodayScheduleViewModel.TodayScheduleViewModelFactory(
            application = LocalContext.current.applicationContext as Application
        )
    )
) {
    val semesterStatus by viewModel.semesterStatus.collectAsState()
    val todayCourses by viewModel.todayCourses.collectAsState()
    val gridStyle by viewModel.gridStyle.collectAsState()
    val isDark = isSystemInDarkTheme()

    val now = remember { LocalDate.now() }
    val todayDateString = remember(now) { now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }
    val todayDayOfWeekString = remember(now) { now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) }

    val backgroundBrush = ThemeGradients.backgroundGradient()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 16.dp)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.title_today_schedule),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            DateHeaderGlassCard(
                dateText = "$todayDateString $todayDayOfWeekString",
                statusText = semesterStatus,
                isDark = isDark
            )

            Spacer(modifier = Modifier.height(14.dp))
            if (todayCourses.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.text_no_courses_today),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                    val currentTime = LocalTime.now()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = DockSafeBottomPadding)
                    ) {
                        itemsIndexed(
                            items = todayCourses,
                            key = { index, c -> "${c.name}_${c.startTime}_${c.endTime}_$index" }
                        ) { _, course ->
                            val isCourseFinished = try {
                                currentTime.isAfter(LocalTime.parse(course.endTime))
                            } catch (_: Exception) {
                                false
                            }

                            val colorPair = gridStyle.courseColorMaps.getOrElse(course.colorInt) {
                                ScheduleGridStyle.DEFAULT_COLOR_MAPS.first()
                            }
                            val baseColor = if (isDark) colorPair.dark else colorPair.light
                            val glassAlpha = if (isCourseFinished) 0.42f else 0.68f

                            val textColor = if (isDark) Color.White else Color(0xFF112031)
                            val containerColor = baseColor.copy(alpha = glassAlpha)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = if (isDark) 0.22f else 0.65f),
                                                Color.White.copy(alpha = if (isDark) 0.05f else 0.12f)
                                            )
                                        ),
                                        shape = RoundedCornerShape(18.dp)
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = containerColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = course.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = textColor,
                                            textDecoration = if (isCourseFinished) TextDecoration.LineThrough else TextDecoration.None,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (!isCourseFinished) {
                                            Spacer(modifier = Modifier.size(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF39D98A))
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "${course.startTime} - ${course.endTime}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textColor.copy(alpha = 0.92f)
                                    )

                                    val detailParts = buildList {
                                        if (course.position.isNotBlank()) add(course.position)
                                        if (course.teacher.isNotBlank()) add(course.teacher)
                                    }
                                    if (detailParts.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = detailParts.joinToString("  路  "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = textColor.copy(alpha = 0.82f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun DateHeaderGlassCard(dateText: String, statusText: String, isDark: Boolean) {
    val overlay = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val borderTop = if (isDark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val borderBottom = if (isDark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.12f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(borderTop, borderBottom)),
                shape = RoundedCornerShape(22.dp)
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = overlay),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5D3B45)
            )
        }
    }
}

