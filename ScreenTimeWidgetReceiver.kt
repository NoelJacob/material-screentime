package com.noeljacob.materialscreentime

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScreenTimeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScreenTimeWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != Intent.ACTION_USER_PRESENT) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ScreenTimeSyncEngine.hasUsageAccessPermission(context)) {
                    ScreenTimeSyncEngine.refreshAndRender(context)
                } else {
                    ScreenTimeSyncEngine.renderPermissionRequired(context)
                }
                ScreenTimeSyncEngine.enqueueNextSync(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
