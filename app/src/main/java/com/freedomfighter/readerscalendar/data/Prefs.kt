package com.freedomfighter.readerscalendar.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }
/** The view the app opens on. */
enum class DefaultView { AGENDA, WEEK, WORKDAYS, DAY, MONTH }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    val weekStartsMonday: Boolean = true,
    /** Calendar ids hidden from the views (empty = show every calendar). */
    val hiddenCalendars: Set<Long> = emptySet(),
    /** Calendar used for new events (0 = first writable). */
    val defaultCalendar: Long = 0L,
    val defaultReminderMinutes: Int = 10,
    val defaultView: DefaultView = DefaultView.WEEK
)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        weekStartsMonday = sp.getBoolean("week_monday", true),
        hiddenCalendars = (sp.getStringSet("hidden_calendars", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet(),
        defaultCalendar = sp.getLong("default_calendar", 0L),
        defaultReminderMinutes = sp.getInt("default_reminder", 10),
        defaultView = enumOr(sp.getString("default_view", null), DefaultView.WEEK)
    )
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setAlign(a: Align) = sp.edit().putString("align", a.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setWeekStartsMonday(v: Boolean) = sp.edit().putBoolean("week_monday", v).apply()
    fun setHiddenCalendars(ids: Set<Long>) = sp.edit().putStringSet("hidden_calendars", ids.map { it.toString() }.toSet()).apply()
    fun setDefaultCalendar(id: Long) = sp.edit().putLong("default_calendar", id).apply()
    fun setDefaultReminder(m: Int) = sp.edit().putInt("default_reminder", m).apply()
    fun setDefaultView(v: DefaultView) = sp.edit().putString("default_view", v.name).apply()
    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }
}
