# Knock — timeline task reminders for Android

Say or type a batch of tasks in one go ("call the bank at 10:30, submit my assignment by 2, gym at 6…"). Knock splits them onto today's timeline and **keeps reminding you until you mark each one done**.

Kotlin · Jetpack Compose · Material 3 · Room · AlarmManager + WorkManager · DataStore · Play Services geofencing. No backend, no accounts; everything stays on the device. minSdk 26, targetSdk 35.

## Get the APK without installing Android Studio

1. Push this folder to a GitHub repository (public or private).
2. Open the **Actions** tab → **Build debug APK** → the workflow runs on every push (or trigger it with *Run workflow*).
3. When it finishes, download the **knock-debug-apk** artifact and unzip it → `app-debug.apk`.

The workflow (`.github/workflows/build.yml`) installs JDK 17 and the Android SDK, runs `./gradlew assembleDebug`, and uploads the APK.

## Build locally

```bash
# needs JDK 17 and the Android SDK (ANDROID_HOME set, or Android Studio installed)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

## Sideload

1. Copy `app-debug.apk` to the phone (USB, Drive, email…) or run `adb install app-debug.apk`.
2. Tap the file → allow installs from this source if asked → Install.
3. On first launch, grant **Notifications**, **Exact alarms** and **Unrestricted battery** — Knock can't remind you without them. Mic and Location are optional.
4. Settings → **Load demo day** fills in the sample timeline.

## What's inside

| Area | Where |
|---|---|
| NL parser (deterministic, on device) | `parse/TaskParser.kt` |
| Reminder engine, alarms, geofences, digests | `reminder/ReminderEngine.kt`, `reminder/NotificationHelper.kt` |
| Room entities/DAO, settings, all task mutations | `data/` |
| Screens (onboarding, permissions, capture, confirm, home, calendar, detail, settings, progress) | `ui/screens/` |
| Full-screen reminder | `ReminderActivity.kt` |

### Reminder loop (as specified)
- One exact alarm (`setExactAndAllowWhileIdle`) is always set for the earliest pending reminder. When it fires, every task that's due is processed together — so a phone that was off produces **one digest**, never a burst.
- Re-fires every *interval* minutes by priority (High 5 / Normal 15 / Low 60, per-task override) until done or skipped.
- Snooze: 10 min / 1 h / Tonight 9 PM / pick a time; max 5 per task per day, then only Reschedule or Skip.
- Skip needs a reason (toggle in Settings); saved to history; recurring tasks ask "this one or all future".
- Overdue > 3 days: interval reminders stop, one daily "Still doing X?" notification instead.
- Quiet hours (23:00–07:00 default): reminders are held and delivered as a single catch-up digest; High priority can bypass if enabled (uses a DND-bypass channel — allow it in system notification settings).
- Alarms are re-registered on boot, time/timezone change and app update. A timezone change shows a "keep local vs shift" banner.
- Location tasks use a 200 m geofence when location permission is granted; otherwise the time reminder is the fallback and Settings shows "Location off".

## Notes / known gaps
- Fonts: the design calls for Plus Jakarta Sans + JetBrains Mono. The app uses the system sans and monospace so the project has no bundled font files; drop the `.ttf`s into `res/font/` and wire them in `ui/theme/Theme.kt` if you want the exact typefaces.
- Place names are resolved with Android's `Geocoder`, which needs Play Services / a network on most devices. If a place can't be resolved, the task simply keeps its time-based reminder.
- Speech uses the device's `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`; whether it works fully offline depends on the installed speech models.
- The GitHub Actions build is the first compile of this code — if it fails, the log in the Actions tab will show the exact line; fixes are usually one-liners.
