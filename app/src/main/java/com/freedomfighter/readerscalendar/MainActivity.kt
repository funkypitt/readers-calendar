package com.freedomfighter.readerscalendar

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readerscalendar.ui.AgendaScreen
import com.freedomfighter.readerscalendar.ui.CalendarsScreen
import com.freedomfighter.readerscalendar.ui.DayScreen
import com.freedomfighter.readerscalendar.ui.EditScreen
import com.freedomfighter.readerscalendar.ui.EventScreen
import com.freedomfighter.readerscalendar.ui.LocalColors
import com.freedomfighter.readerscalendar.ui.MonthScreen
import com.freedomfighter.readerscalendar.ui.Nav
import com.freedomfighter.readerscalendar.ui.ReaderTheme
import com.freedomfighter.readerscalendar.ui.Screen
import com.freedomfighter.readerscalendar.ui.SettingsScreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val nav = Nav()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nav.version++ }

    fun askPermission() = permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        if (!app.calendars.hasPermission()) askPermission()
        handle(intent)
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                when (val s = nav.current) {
                    Screen.Agenda -> AgendaScreen(nav, app)
                    is Screen.Month -> MonthScreen(nav, app, s.month)
                    is Screen.Week -> com.freedomfighter.readerscalendar.ui.WeekScreen(nav, app, s.start)
                    is Screen.Day -> DayScreen(nav, app, s.date)
                    is Screen.Event -> EventScreen(nav, app, s.id)
                    is Screen.Edit -> EditScreen(nav, app, s.id, s.date, s.time)
                    Screen.Calendars -> CalendarsScreen(nav, app)
                    Screen.Settings -> SettingsScreen(nav, app)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onResume() { super.onResume(); nav.version++ }

    /** content://com.android.calendar/time/<millis> → that day; /events/<id> → the event; INSERT → new event. */
    private fun handle(intent: Intent?) {
        val data = intent?.data
        when {
            intent?.action == Intent.ACTION_INSERT -> { nav.home(); nav.push(Screen.Edit(0L)) }
            data != null && data.path?.startsWith("/events/") == true -> runCatching { ContentUris.parseId(data) }.getOrNull()?.let { nav.home(); nav.push(Screen.Event(it)) }
            data != null && data.path?.startsWith("/time") == true -> {
                val millis = data.lastPathSegment?.toLongOrNull()
                val date = if (millis != null) Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
                nav.home(); if (date != LocalDate.now()) nav.push(Screen.Day(date))
            }
        }
        intent?.action = null
    }
}

@Composable
private fun Bars() {
    val view = LocalView.current
    val colors = LocalColors.current
    LaunchedEffect(colors.isDark) {
        val window = (view.context as? ComponentActivity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, view).apply { isAppearanceLightStatusBars = !colors.isDark; isAppearanceLightNavigationBars = !colors.isDark }
    }
}
