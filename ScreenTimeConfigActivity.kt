package com.noeljacob.materialscreentime

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private const val APP_ICON_PX = 72

class ScreenTimeConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED)
        setContent {
            MaterialTheme {
                ConfigScreen()
            }
        }
    }

    private fun finishWithResult() {
        val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    @Composable
    private fun ConfigScreen() {
        val hasPermission = ScreenTimeSyncEngine.hasUsageAccessPermission(this)
        val scope = rememberCoroutineScope()
        var uiState by remember { mutableStateOf(ConfigUiState.empty()) }

        LaunchedEffect(hasPermission) {
            uiState = if (hasPermission) {
                withContext(Dispatchers.IO) { loadUiState() }
            } else {
                ConfigUiState.empty()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ScreenTimeWidget().updateAll(this@ScreenTimeConfigActivity)
                            }
                            finishWithResult()
                        }
                    },
                ) {
                    Text("Done")
                }
            }

            if (!hasPermission) {
                PermissionRequiredContent {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                return
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = uiState.apps, key = { it.packageName }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconBitmap = remember(entry.icon) {
                            entry.icon.toBitmap(APP_ICON_PX, APP_ICON_PX).asImageBitmap()
                        }
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = entry.appName,
                            modifier = Modifier.size(36.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(text = entry.appName, style = MaterialTheme.typography.bodyLarge)
                            Text(text = entry.screenTimeFormatted, style = MaterialTheme.typography.bodyMedium)
                        }
                        androidx.compose.material3.Checkbox(
                            checked = entry.isChecked,
                            onCheckedChange = { isChecked ->
                                val updatedApps = uiState.apps.map { app ->
                                    if (app.packageName == entry.packageName) {
                                        app.copy(isChecked = isChecked)
                                    } else {
                                        app
                                    }
                                }
                                uiState = uiState.copy(apps = updatedApps)
                                val excluded = updatedApps.filterNot { it.isChecked }
                                    .map { it.packageName }
                                    .toSet()
                                scope.launch(Dispatchers.IO) {
                                    ScreenTimeStorage.saveExcludedPackages(
                                        this@ScreenTimeConfigActivity,
                                        excluded,
                                    )
                                    ScreenTimeSyncEngine.refreshAndRender(this@ScreenTimeConfigActivity)
                                    ScreenTimeSyncEngine.enqueueNextSync(this@ScreenTimeConfigActivity)
                                    ScreenTimeWidget().updateAll(this@ScreenTimeConfigActivity)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadUiState(): ConfigUiState = withContext(Dispatchers.IO) {
        val packageManager = packageManager
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val excluded = ScreenTimeStorage.getExcludedPackages(this@ScreenTimeConfigActivity)

        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()

        val aggregatedUsage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)

        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }

        val appModels = packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val packageName = pkg.packageName
            val usageMillis = aggregatedUsage[packageName]?.totalTimeInForeground ?: 0L
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val icon = packageManager.getApplicationIcon(appInfo)
            val screenTimeFormatted = ScreenTimeSyncEngine.formatDuration(usageMillis)
            val model = AppUiModel(
                packageName = packageName,
                appName = appName,
                icon = icon,
                screenTimeFormatted = screenTimeFormatted,
                isChecked = packageName !in excluded,
            )
            AppModelSortEntry(model, usageMillis, appName.lowercase())
        }
            .sortedWith(
                compareByDescending<AppModelSortEntry> { it.usageMillis }
                    .thenBy { it.nameKey },
            )
            .map { it.model }

        ConfigUiState(apps = appModels)
    }

    @Composable
    private fun PermissionRequiredContent(onGrantClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Permission Required", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Grant Usage Access permission to configure and calculate screen time.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = onGrantClick) {
                Text("Open Settings")
            }
        }
    }
}

data class AppUiModel(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val screenTimeFormatted: String,
    val isChecked: Boolean,
)

data class ConfigUiState(
    val apps: List<AppUiModel>,
) {
    companion object {
        fun empty(): ConfigUiState = ConfigUiState(apps = emptyList())
    }
}

private data class AppModelSortEntry(
    val model: AppUiModel,
    val usageMillis: Long,
    val nameKey: String,
)
