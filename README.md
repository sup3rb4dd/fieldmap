# FieldMap

A simple, free, ATAK-style map for Android that talks to your **BTECH UV-PRO** (APRS over Bluetooth KISS TNC) and **Meshtastic** radios like the Heltec V4 — at the same time if you want.

## Features
- Map with your GPS position and an **MGRS** readout for you and the map center
- **Offline maps**: USGS Topo and HD Satellite (NAIP imagery to zoom 19), download any area for use without cell service
- **Pins**: long-press to drop, share with your team over the radio (as APRS objects)
- **Range & bearing** to any pin, station or typed-in MGRS grid
- **Team positions**: everyone's radio beacons show up on the map
- **Team chat**: APRS messages to your team name, or direct to a callsign (with delivery ✓)
- **Draw routes and areas**, measure them, follow a route point to point, and share them with the team
- **Terrain**: ground elevation at the crosshair, and a line-of-sight check with a terrain profile between you and any point
- **Works with ATAK / WinTAK / iTAK** over shared Wi-Fi or a plain-TCP TAK server: positions, markers, drawings and group chat
- **Import KML, KMZ and GPX files**; add any z/x/y map server or a raster MBTiles file as a map layer
- **SOS**: one button sends your name and grid to the team every 2 minutes; their phones sound an alarm and show you in red
- **Check-in and quick messages**: "OK + my grid" in one tap; tap any grid in chat to see it on the map
- **Steering arrow, ETA and compass**: track a waypoint or teammate and follow the arrow; hold N↑ for a heading-up map
- **Trails**: your own breadcrumb track (export as GPX) and teammates' recent paths
- **Saved places**: private home/family markers that are never transmitted
- **Backup / restore** of places, waypoints and settings to a file
- **Severe weather**: National Weather Service warnings for your location, with warning areas drawn on the map (needs internet)
- **Background mode and alerts**: radios stay linked with the screen off; auto-reconnect when the app opens
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

## Hook up Meshtastic (Heltec V4 etc.)
1. Close the official Meshtastic app (only one app can hold the Bluetooth connection).
2. In FieldMap tap **RADIO ▸ Meshtastic**, pick your node. If Android asks for a PIN, use the one on the node's screen (default 123456).
3. Nodes with positions appear on the map in purple, waypoints show as pins, and channel messages land in Chat.
Pins you share and team chat go out on every connected radio. Your position goes to the mesh at most every 5 minutes to save airtime.

## Building
GitHub Actions builds the APK on every push to `main` (see `.github/workflows/build.yml`).
Core logic (MGRS, AX.25, KISS, APRS) lives in `app/src/main/java/app/fieldmap/core` and has no Android dependencies.
