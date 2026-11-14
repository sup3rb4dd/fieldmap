# FieldMap

A simple, free, ATAK-style map for Android that talks to your **BTECH UV-PRO** (or any Bluetooth KISS TNC) using APRS.

## Features
- Map with your GPS position and an **MGRS** readout for you and the map center
- **Offline maps**: USGS Topo / Satellite layers, download any area for use without cell service
- **Pins**: long-press to drop, share with your team over the radio (as APRS objects)
- **Range & bearing** to any pin, station or typed-in MGRS grid
- **Team positions**: everyone's radio beacons show up on the map
- **Team chat**: APRS messages to your team name, or direct to a callsign (with delivery ✓)
- Raw packet log for troubleshooting

## Install
Download `FieldMap.apk` from the newest release:
https://github.com/sup3rb4dd/fieldmap/releases/latest/download/FieldMap.apk

Open it on your phone and allow "install unknown apps" when Android asks.

## Hook up the UV-PRO
1. On the radio: **Menu ▸ General Settings ▸ KISS TNC ▸ On** (close the BTECH app — it can't share the connection).
2. Pair the radio in **Android Settings ▸ Bluetooth**.
3. In FieldMap tap **RADIO** at the top and pick the UV-PRO.
4. Set your **callsign** in ☰ ▸ Settings. Everyone on the team should use the same **Team name**.
5. Tune every radio to the same frequency (the US APRS frequency is 144.390 MHz).

Transmitting APRS uses amateur frequencies and requires an amateur radio license. Without a callsign set, FieldMap only listens.

## Building
GitHub Actions builds the APK on every push to `main` (see `.github/workflows/build.yml`).
Core logic (MGRS, AX.25, KISS, APRS) lives in `app/src/main/java/app/fieldmap/core` and has no Android dependencies.
