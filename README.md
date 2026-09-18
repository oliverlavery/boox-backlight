# boox-backlight

Adaptive front-light learning service for the **Onyx Boox Palma 2 Pro** (and other
CTM-based Boox e-ink devices).

Boox's built-in auto-brightness is tuned for carta panels and runs too dim on the
Palma 2 Pro's emissive front light. This is a small Kotlin service that:

1. **Observes** — ambient light (STK3x3x lux sensor) + your manual brightness/warmth
   adjustments (via the `screen_ctm_brightness` / `screen_ctm_temperature` system
   settings, which the control center writes on every slider drag and preset tap).
2. **Learns** — a factored model: brightness as a function of lux (9 log-scale
   buckets), warmth as a function of time of day (6×4-hour buckets) with a small
   dark-room warmth modifier. EWMA per cell; your latest deliberate adjustment is
   the authoritative value for that cell (recency authority). ~14 floats total —
   one week of ordinary use to converge.
3. **Acts** — on lux-bucket transitions (after a 3-second stability hold), applies
   the learned values for the new bucket.

## Override-fight prevention

The service loses every argument with the user, immediately and silently:

- **Deadband** — never applies unless the learned value differs by >3 native steps
- **Recency gate** — no actuation within 4 minutes of a manual adjustment
- **Echo suppression** — ignores the settings-key changes caused by its own writes
- **Pause** — Quick Settings tile toggle (Boox-style circular icon, inverted when on)

## Actuation path

Writes go through **Gentle Glow**'s explicit broadcast receiver (calin-darie's
open-source front-light app, which must be installed):

```
am broadcast -n com.onyx.darie.calin.gentleglowonyxboox/.ChangeLightReceiver \
  -a com.onyx.darie.calin.gentleglowonyxboox.CHANGE_LIGHT \
  --ei BRIGHTNESS <0-100> --ei WARMTH <0-100>
```

Calibration on Palma 2 Pro: native CTM scale is 0–32 on both channels; GG's
0–100 maps linearly (`native = round(gg × 0.32)`); the service uses the inverse
with a ceil bias to land exactly on the target step.

The `screen_ctm_*` settings keys are a **state mirror only** — writable, reflected
in the control-center UI, but writing them does not actuate the light. Real
actuation requires Gentle Glow (or reflection into the hidden
`android.onyx.hardware.DeviceController` — a future goal).

## Sleep/doze notes (Boox quirks, learned the hard way)

- The **wakeup variant** of the lux sensor keeps delivering in doze.
- Handler timers (`postDelayed`) **freeze in doze** — hysteresis is evaluated from
  sensor events against `SystemClock.elapsedRealtime()` instead.
- Boox firmware **kills apps on configuration changes** (`killAppForConfigChange`)
  — the activity declares `android:configChanges` for everything.
- `SCREEN_ON` flushes any pending bucket commit; `BOOT_COMPLETED` restarts the
  service after reboot; `START_STICKY` + cold-start apply covers process death.
- The sensor sees *total ambient light*: rooms with similar lux are intentionally
  indistinguishable.

## Building

```
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 (AGP 8.5 fails on newer JDKs). Launch on device with:

```
adb shell monkey -p dev.shadow.booxbacklight -c android.intent.category.LAUNCHER 1
```

(`am start` is refused by Boox for this activity.)

## Data

- `files/observations.jsonl` — raw observation stream (lux events, user
  adjustments, apply/skip decisions)
- `files/light-model.json` — learned model state (survives reboot)

## Status

Working end-to-end on Palma 2 Pro (Boox firmware 4.x). Learning from ordinary use;
cold-start priors are conservative defaults.

## License

MIT
