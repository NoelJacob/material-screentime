package com.noeljacob.materialscreentime

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ConfigScreen()
            }
        }
    }

    @Composable
    private fun ConfigScreen() {
        val hasPermission = ScreenTimeSyncEngine.hasUsageAccessPermission(this)
        if (!hasPermission) {
            PermissionRequiredContent {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            return
        }

        val appEntries by produceState(initialValue = emptyList<AppUsageEntry>()) {
            value = withContext(Dispatchers.IO) {
                loadAppUsageEntries()
            }
        }

        var excluded by remember { mutableStateOf(ScreenTimeStorage.getExcludedPackages(this)) }
        val scope = rememberCoroutineScope()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(appEntries, key = { it.packageName }) { entry ->
                val checked = entry.packageName !in excluded
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = entry.icon.asImageBitmap(),
                        contentDescription = entry.appName,
                        modifier = Modifier.size(36.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(text = entry.appName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = ScreenTimeSyncEngine.formatDuration(entry.todayUsageMillis),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            excluded = if (isChecked) {
                                excluded - entry.packageName
                            } else {
                                excluded + entry.packageName
                            }
                            ScreenTimeStorage.saveExcludedPackages(this@ScreenTimeConfigActivity, excluded)
                            scope.launch(Dispatchers.IO) {
                                ScreenTimeSyncEngine.refreshAndRender(this@ScreenTimeConfigActivity)
                                ScreenTimeSyncEngine.enqueueNextSync(this@ScreenTimeConfigActivity)
                            }
                        },
                    )
                }
            }
        }
    }

    private suspend fun loadAppUsageEntries(): List<AppUsageEntry> = withContext(Dispatchers.IO) {
        val packageManager = packageManager
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager

        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()

        val usageByPackage = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            now,
        ).associate { usage ->
            usage.packageName to usage.totalTimeInForeground
        }

        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        applications
            .mapNotNull { app ->
                runCatching {
                    app.toUsageEntry(packageManager, usageByPackage[app.packageName] ?: 0L)
                }.getOrNull()
            }
            .sortedWith(compareByDescending<AppUsageEntry> { it.todayUsageMillis }.thenBy { it.appName.lowercase() })
    }

    private fun ApplicationInfo.toUsageEntry(
        pm: PackageManager,
        usageMillis: Long,
    ): AppUsageEntry {
        return AppUsageEntry(
            packageName = packageName,
            appName = pm.getApplicationLabel(this).toString(),
            icon = pm.getApplicationIcon(this).toBitmap(APP_ICON_PX, APP_ICON_PX),
            todayUsageMillis = usageMillis,
        )
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

data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val icon: Bitmap,
    val todayUsageMillis: Long,
)
