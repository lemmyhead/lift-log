# Lift Log

A powerlifting session logger with a rest timer you drive from the lock screen.

## The loop it's built around

You write the ramp before you train, then execute it with one button. That button is on the
lock screen, so the phone never has to be unlocked between sets.

One set is four presses, because racking the bar and starting the rest timer are separate
moments in the gym:

| Phase | Button says | What happens |
|---|---|---|
| `Ready for next set` | **Start set** | The next planned set begins; its weight and reps are already written |
| `Set in progress · 0:42` | **End set** | The set is banked with its work time. Rest starts accruing from here |
| `Racked · 0:18` | **Start rest** | The countdown begins against *this* set's rest target |
| `Resting · 2:31` | **End rest** | The countdown stops; you are ready for the next set |

**Rest is measured from the rack, but counted down from the press.** The rest logged
against a set is the whole interval between finishing it and starting the next one —
including the walk back to your phone and the walk back to the bar. The countdown is only
the part you deliberately timed. Both are shown while resting, so the two are never
confused.

## Planning the ramp

An exercise is a list of sets, each with its own weight, reps, rest target and kind. A
squat ramp is not five identical sets:

```
1 · 100 kg × 5 · warmup   · rest 2:00
2 · 120 kg × 3 · warmup   · rest 3:00
3 · 140 kg × 3 · warmup   · rest 3:30
4 · 160 kg × 5 · working  · rest 5:00
5 · 160 kg × 5 · working  · rest 6:00
6 · 160 kg × 5 · working  · rest 7:00
7 · 120 kg × 5 · deload
```

Add sets with **+ Plan a set**; tap any unfinished row to edit or remove it. Adding a
movement you have trained before brings back last session's whole ramp, so a week-to-week
progression usually means changing one number. Run out of planned sets and the app carries
the last one forward rather than stalling mid-session.

After a set is done, tapping it edits what you *actually* did — the plan is kept alongside,
so a missed rep is visible rather than silently overwritten.

Each set carries a kind — **warmup**, **working**, **backoff** or **deload**. It is just a
label: it is recorded against the set, shown in the log and written to the export, so a
ramp still reads correctly months later.

## Rest alarm

Every planned set carries its own rest target, and the phone sounds a notification and
vibrates when it elapses. Presets run 0:45 to 7:00, plus off.

The timer keeps counting past the target rather than stopping, so the log records the rest
you actually took — the card shows `0:15 over your 5:00 target` once you run long.

The alert is scheduled through `AlarmManager` as an exact wake-up alarm, which is the only
mechanism that reliably fires while the phone is idle in a bag. The `USE_EXACT_ALARM`
permission it relies on is granted automatically on Android 13+, so there is nothing to
enable. Ending a rest early, or finishing the session, cancels the pending alarm.

The alert names the set you are going back to — `Next: Squat · set 3 · 160 kg × 5` — and
carries two buttons, so the phone still does not have to be unlocked:

| Button | What happens |
|---|---|
| **Start set** | Closes the rest and begins the next planned set |
| **+1:00** | Another minute, counted from now. A one-off — the set's planned rest target is left alone |

## Structure

```
training  →  exercise  →  set  →  reps
```

Each set stores what was planned and what was done: weight, reps, RPE (optional), the kind
of set, its rest target, how long the set took, and the rest that followed it. Each session
records its total duration.

## Export

**Export CSV** (share icon, top right) writes one row per completed set — 18 columns of
exactly what you logged, with nothing computed or inferred:

```
session_id, session_start, session_end, session_duration_s,
exercise_order, exercise, set_number, label,
planned_reps, planned_weight_kg, reps, weight_kg,
rpe, volume_kg, rest_target_s, work_s, rest_s, completed_at
```

Both the plan and the performance are there, so a set that did not go as written is visible
rather than averaged away. The file goes out through Android's share sheet — Drive, email,
or straight into a spreadsheet.

## Install

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Copy it to the phone and open it, then allow installs from that source when Android asks.
Or over USB with debugging enabled:

```bash
~/.liftlog-toolchain/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant the notification permission on first launch — without it the lock-screen controls
have nowhere to appear and you'd be back to unlocking the phone between sets.

## Xiaomi / HyperOS setup

Granting the notification permission is enough on stock Android, but **not** on MIUI or
HyperOS. Confirmed on a Redmi 13 (HyperOS 1.0.8.0, Android 14): the notification appeared
in the pull-down shade but was filtered off the lock screen entirely. Redo these after a
factory reset or a reinstall.

**Show it on the lock screen.** The session channel is created as `IMPORTANCE_LOW` on
purpose, so the ticking timer never beeps at you mid-set. HyperOS files low-importance
channels under "silent" and keeps silent notifications off the lock screen. Two settings
undo that:

- `Settings > Notifications & Control Center > Lock screen notifications` — show all
  notifications *and* their content.
  *(Definições > Notificações e Centro de controlo > Notificações do ecrã de bloqueio)*
- `Settings > Notifications & Control Center > App notifications > Lift Log` — open the
  **Workout session** channel and enable **Show on lock screen**.
  *(… > Notificações de aplicações > Lift Log)*

Changing a channel by hand marks it user-locked, so the app can't override it afterwards —
which is what you want here.

**Keep the session alive.** HyperOS kills background services aggressively during a long
screen-off session:

- `Settings > Apps > Manage apps > Lift Log` → **Autostart** on, **Battery saver** set to
  **No restrictions**. *(… > Início automático / Poupança de bateria → Sem restrições)*
- In Recents, long-press the Lift Log card and tap the **padlock** to hold it in memory.

**Expanding the notification.** Action buttons live in a notification's expanded layout on
every Android build, HyperOS included — swipe down on the notification card, or tap the `⌄`
at its right edge, to reveal the phase button (`Start set` / `End set` / `Start rest` /
`End rest`) and `End training`.

## Rebuilding

```bash
cd "/Users/stasskrypnikov/Documents/mob app" && . ~/.liftlog-toolchain/env.sh && gradle :app:assembleDebug
```

The toolchain (JDK 17, Android SDK 34, Gradle 8.7) lives in `~/.liftlog-toolchain` and is
self-contained — nothing was installed into system directories.

## Data

The log is a single SQLite database in the app's private storage. No network permission is
declared, so the app itself never talks to anything — there is no account, no sync and no
telemetry.

It does opt that database into Android's own backup, though, and deliberately so: the
training log is the only copy of your history, and a phone that goes in a river should not
take a year of squats with it. Both rule files name it explicitly —
`res/xml/backup_rules.xml` for Android 11 and below, `res/xml/data_extraction_rules.xml`
for Android 12+:

| Path | What it covers |
|---|---|
| Cloud backup | Android uploads `liftlog.db` to the Google account on the phone, whenever the phone's own backup is switched on under `Settings > Google > Backup`. Reinstalling on a new handset restores the log with the app |
| Device transfer | The database comes across during a phone-to-phone migration |

**So the log can leave the phone — through Google's backup, not through the app.** If you
want it confined to the handset, set `android:allowBackup="false"` in
`app/src/main/AndroidManifest.xml` and rebuild, or turn the phone's backup off system-wide.
Either way the CSV export stays the only route the app itself offers.
