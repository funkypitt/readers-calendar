# Reader's Calendar

A black-and-white, text-only calendar for Android, in the family of
[Reader's Launcher](https://github.com/funkypitt/readers-launcher). It reads and writes the
phone's own calendars — the ones your accounts already sync (Google, Infomaniak kSync, DAVx5,
Nextcloud…) — so there is no server setup and nothing new to keep in sync.

* **Agenda**: the coming days as a list, grouped by day. Tap the month name for the month grid.
* **Month**: a grid of numbers; a dot marks days with events, today is inverted.
* **Week**: in portrait, the seven days as a list; in landscape, the time grid — seven
  columns over the hours, events as outlined boxes, all-day events in a strip above, the
  present moment as a line.
* **Event**: title, when, repetition, reminder, then the calendar it belongs to as a quiet
  line of text (no colour codes), location and description.
* **Edit / create**: title, all day, start and end (date from a month grid, time typed as
  text), calendar, reminder, repetition (daily, weekly, monthly, yearly), location, description.
* **Calendars**: choose which ones are shown; pick the default one for new events.
* Landscape puts the month grid beside the agenda, and splits the event and edit screens in
  two columns.

Same look as the other Reader's apps: white on black or black on white, serif / sans / mono,
three text sizes. English and French.

Other apps open it for a day (`content://com.android.calendar/time/…`), an event or a new
event, so it can be the default calendar app, including for the launcher's agenda tile.

## Build

```
./gradlew assembleDebug
```

Kotlin, Jetpack Compose (foundation only), CalendarContract. No other dependency. MIT.
