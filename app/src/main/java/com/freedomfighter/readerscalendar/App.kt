package com.freedomfighter.readerscalendar

import android.app.Application
import com.freedomfighter.readerscalendar.data.CalendarStore
import com.freedomfighter.readerscalendar.data.Prefs

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var calendars: CalendarStore
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        calendars = CalendarStore(this)
    }
}
