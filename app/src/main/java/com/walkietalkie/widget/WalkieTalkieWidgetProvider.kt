package com.walkietalkie.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.walkietalkie.R
import com.walkietalkie.service.WalkieTalkieService

/**
 * Home-screen widget with a single Talk toggle button.
 *
 * Because [RemoteViews] does not support `OnTouchListener`, true
 * press-and-hold is impossible from a widget. Instead we use a
 * **toggle**: tap once to start transmitting, tap again to stop.
 *
 * On first tap the widget also ensures the [WalkieTalkieService]
 * foreground service is running so audio can be captured.
 */
class WalkieTalkieWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.walkietalkie.widget.TOGGLE_TRANSMIT"

        /** Tracks the current TX state across all widget instances. */
        @Volatile
        private var isTx = false

        /** Force-refresh every widget instance (e.g. when service state changes). */
        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, WalkieTalkieWidgetProvider::class.java)
            )
            val intent = Intent(context, WalkieTalkieWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    // ── Widget lifecycle ─────────────────────────────────────────────────

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        isTx = !isTx

        // Build the service intent
        val svcAction = if (isTx)
            WalkieTalkieService.ACTION_START_TRANSMIT
        else
            WalkieTalkieService.ACTION_STOP_TRANSMIT

        val svcIntent = Intent(context, WalkieTalkieService::class.java).apply {
            action = svcAction
        }

        try {
            ContextCompat.startForegroundService(context, svcIntent)
        } catch (_: Exception) {
            // Service not yet running — start it first, then retry
            val start = Intent(context, WalkieTalkieService::class.java).apply {
                action = WalkieTalkieService.ACTION_START_SERVICE
            }
            ContextCompat.startForegroundService(context, start)
            ContextCompat.startForegroundService(context, svcIntent)
        }

        // Refresh widget appearance
        val mgr = AppWidgetManager.getInstance(context)
        val comp = ComponentName(context, WalkieTalkieWidgetProvider::class.java)
        mgr.getAppWidgetIds(comp).forEach { updateWidget(context, mgr, it) }
    }

    // ── Widget rendering ─────────────────────────────────────────────────

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.walkie_talkie_widget)

        if (isTx) {
            views.setTextViewText(R.id.widget_btn_talk, "⬛  STOP")
            views.setInt(
                R.id.widget_btn_talk, "setBackgroundResource",
                R.drawable.widget_btn_transmitting
            )
        } else {
            views.setTextViewText(R.id.widget_btn_talk, "\uD83C\uDF99  TALK")
            views.setInt(
                R.id.widget_btn_talk, "setBackgroundResource",
                R.drawable.widget_btn_idle
            )
        }

        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, WalkieTalkieWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_talk, pi)

        manager.updateAppWidget(widgetId, views)
    }
}
