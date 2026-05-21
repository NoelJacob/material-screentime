package com.noeljacob.materialscreentime

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.components.Button
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.state.currentState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "screen_time_widget_prefs"
private const val EXCLUDED_PACKAGES_KEY = "excluded_packages"
internal const val WORK_NAME = "screen_time_sync_chain"
private val DISPLAY_TEXT_KEY = stringPreferencesKey("display_text")
private val LAST_UPDATED_AT_KEY = longPreferencesKey("last_updated_at")

class ScreenTimeWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(
        setOf(
            androidx.glance.appwidget.DpSize(120.dp, 120.dp),
            androidx.glance.appwidget.DpSize(240.dp, 120.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val hasPermission = ScreenTimeSyncEngine.hasUsageAccessPermission(context)
        provideContent {
            WidgetContent(context, hasPermission)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, hasPermission: Boolean) {
        val prefs = currentState<Preferences>()
        val display = prefs[DISPLAY_TEXT_KEY] ?: "Tap to refresh"

        androidx.glance.material3.GlanceTheme {
            val colors = androidx.glance.material3.GlanceTheme.colors
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(colors.widgetBackground)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!hasPermission) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Permission Required",
                            style = TextStyle(
                                color = colors.onSurface,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        Button(
                            text = "Grant",
                            onClick = actionStartActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            ),
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = display,
                            style = TextStyle(
                                color = colors.onSurface,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        Button(
                            text = "Refresh",
                            onClick = actionRunCallback<RefreshAction>(),
                        )
                    }
                }
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        if (!ScreenTimeSyncEngine.hasUsageAccessPermission(context)) {
            ScreenTimeSyncEngine.renderPermissionRequired(context)
            return
        }
        ScreenTimeSyncEngine.refreshAndRender(context)
        ScreenTimeSyncEngine.enqueueNextSync(context)
    }
}

class ScreenTimeSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            return Result.success()
        }

        if (!ScreenTimeSyncEngine.hasUsageAccessPermission(applicationContext)) {
            ScreenTimeSyncEngine.renderPermissionRequired(applicationContext)
            return Result.success()
        }

        ScreenTimeSyncEngine.refreshAndRender(applicationContext)
        ScreenTimeSyncEngine.enqueueNextSync(applicationContext)
        return Result.success()
    }
}

internal object ScreenTimeStorage {
    fun getExcludedPackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(EXCLUDED_PACKAGES_KEY, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun saveExcludedPackages(context: Context, excluded: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(EXCLUDED_PACKAGES_KEY, excluded)
            .apply()
    }
}

internal object ScreenTimeSyncEngine {
    private val widget = ScreenTimeWidget()

    suspend fun refreshAndRender(context: Context) {
        val text = refreshState(context)
        renderText(context, text)
    }

    suspend fun refreshState(context: Context): String {
        val text = withContext(Dispatchers.IO) {
            val usageMillis = calculateTodayIncludedUsageMillis(context)
            formatDuration(usageMillis)
        }
        updateWidgetState(context, text)
        return text
    }

    suspend fun renderPermissionRequired(context: Context) {
        renderText(context, "Permission Required")
    }

    fun enqueueNextSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScreenTimeSyncWorker>()
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun hasUsageAccessPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private suspend fun renderText(context: Context, text: String) {
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(ScreenTimeWidget::class.java)
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                updateWidgetPreferences(prefs, text)
            }
            widget.update(context, glanceId)
        }
    }

    private suspend fun updateWidgetState(context: Context, text: String) {
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(ScreenTimeWidget::class.java)
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                updateWidgetPreferences(prefs, text)
            }
        }
    }

    private fun updateWidgetPreferences(prefs: MutablePreferences, text: String) {
        prefs[DISPLAY_TEXT_KEY] = text
        prefs[LAST_UPDATED_AT_KEY] = System.currentTimeMillis()
    }

    private fun calculateTodayIncludedUsageMillis(context: Context): Long {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val excludedPackages = ScreenTimeStorage.getExcludedPackages(context)

        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            now,
        )

        return stats
            .asSequence()
            .filter { stat -> stat.packageName !in excludedPackages }
            .sumOf { stat -> stat.totalTimeInForeground }
    }

    internal fun formatDuration(millis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            "%dh %02dm".format(hours, minutes)
        } else {
            "%dm".format(minutes)
        }
    }
}
