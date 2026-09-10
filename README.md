# Reader's Calendar

A black-and-white, text-only calendar for Android, in the family of
[Reader's Launcher](https://github.com/funkypitt/readers-launcher). It reads and writes the
phone's own calendars — the ones your accounts already sync (Google, Infomaniak kSync, DAVx5,
Nextcloud…) — so there is no server setup and nothing new to keep in sync.

* **Agenda**: the coming days as a list, grouped by day. Tap the month name for the month grid.
* **Month**: a grid of numbers; a dot marks days with events, today is inverted.
* **Week**: a time grid in both orientations — seven columns over the hours, events as
  solid blocks so the gaps between them read as free time, overlapping events side by side,
  all-day events in a strip above, the present moment as a line. Swipe sideways for the next
  week, tap a day header for that day, tap an empty slot for a new event at that hour.
* **Workdays**: Monday to Friday at full width; the weekend folded into a narrow strip at the
  right, one letter per day and a dot when the day holds something. Tap the strip for the whole
  week. The blocks' text is one step larger there, since the columns are wider.
* **Day**: the same grid, one column wide, with the place next to the time. In the menu it is
  "today (day view)", next to "today (list)" for the agenda.
* **Creating an event** is the one frequent action, so it is a tap away everywhere: the
  "+ new event" row at the bottom of every view, a tap on an empty slot in a grid, a long press
  on a day in the month grid or on a day heading in the agenda, the + of the launcher's agenda
  tile. The form asks the title first, keyboard up; then one row for the day, one for the times
  (start, then end, two quick numeric prompts); everything else below, already sensible.
* The ⋯ menu is the same on every view: new event first, then the views, the calendars, the
  colours and the settings.
* The app opens on the week; settings › "opens on" switches that to the agenda, the workdays, the day or
  the month. The agenda stays one back away.
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

## Widgets

Two standard home-screen widgets for any launcher: the next event (one line, + for a new one) and today and tomorrow as a list.

## Crédits / Credits

© 2026 Pierre Gallaz. Développé avec [Claude Code](https://claude.com/claude-code) (Anthropic).
Licence MIT, voir `LICENSE`.

© 2026 Pierre Gallaz. Developed with [Claude Code](https://claude.com/claude-code) (Anthropic).
MIT licence, see `LICENSE`.
