# FieldMap

A simple, free, ATAK-style map for Android that talks to your **BTECH UV-PRO** (APRS over Bluetooth KISS TNC) and **Meshtastic** radios like the Heltec V4 — at the same time if you want.

## Works with Lantern

FieldMap pairs with [Lantern](https://github.com/sup3rb4dd/lantern), an offline library on a thumb
drive (Wikipedia, medical references, repair guides, survival manuals and maps) that runs on any
Windows PC with no internet. With a Meshtastic node attached, Lantern becomes a reference desk your
team can reach over the mesh:

```
FieldMap (phone) ──Bluetooth── your Meshtastic node ~~~ mesh ~~~ gateway node 🤖 ──USB/WiFi── PC running Lantern
```

From the field, type a question on FieldMap's **Ask Lantern** screen, like `bleach drops per gallon`.
Lantern searches its whole library, including inside its PDF guides, and radios back a short
answer a few messages at a time. Everything you receive is saved on the phone to read again with no
signal. Setup is below under [Ask Lantern](#ask-lantern); Lantern's side is in its
[RELAY.md](https://github.com/sup3rb4dd/lantern/blob/main/RELAY.md).

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
- **Repeaters on the map**: download any state's ham repeaters from [HearHam.live](https://hearham.com/repeaters) (free, once, then works offline), add your own, or import a CSV club list. Tap one for output and input frequencies, offset, tone, callsign, notes, and range and bearing from you
- **Saved places**: private home/family markers that are never transmitted
- **Backup / restore** of places, waypoints and settings to a file
- **Severe weather**: National Weather Service warnings for your location, with warning areas drawn on the map (needs internet)
- **Background mode and alerts**: radios stay linked with the screen off; auto-reconnect when the app opens
- **Ask Lantern**: search an offline library (Wikipedia, medical and repair guides, books) over Meshtastic, through a [Lantern](https://github.com/sup3rb4dd/lantern) drive with Lantern Relay turned on. Answers are saved on the phone to read again with no signal
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

## Ask Lantern
1. Set up a gateway: a Meshtastic node with 🤖 in its name, on your team's channel, plugged into a PC running Lantern with **Relay** turned on and your node on its allowed list (see Lantern's RELAY.md).
2. Connect your own Meshtastic node in FieldMap, then open ☰ ▸ **Ask Lantern** and tap **PICK YOUR LANTERN GATEWAY NODE**. Nodes named with 🤖, "Lantern" or "Relay" are marked ◆, but pick your own team's: FieldMap never guesses, so questions can't go to a stranger's node. **GATEWAY** changes it later.
3. Type a question and tap **SEARCH**. Tap a result to read it, **MORE** for the next part, **SECTIONS** to jump around, and **SAVED** to reread earlier answers offline.

Questions are direct messages to the gateway node, and its answers stay out of team chat. Each answer comes in a few short messages, so give it a minute on a busy mesh. Ask Lantern works over Meshtastic only, not GMRS: automated replies are a gray area under the GMRS rules.

## Repeaters
☰ ▸ **Repeaters (ham & GMRS)**:
- **Download a state** or **near me (100 mi)** pulls HearHam.live's free repeater list once (about 10 MB) and keeps just that area on the phone. A state's box includes repeaters just over its borders. Downloading the same state again refreshes it.
- **Add one**: long-press the map ▸ **Repeater…**, or add one at the crosshair from the menu. Enter the output frequency, offset, tone and callsign.
- **Import a CSV list** with a header row: `Output` (or `Frequency`), `Input` or `Offset`, `Tone`, `Callsign`, `Location`, `Notes`, `Latitude`, `Longitude`. CHIRP CSVs work if they have Latitude and Longitude columns. Rows without a position are skipped.

Repeaters show once you zoom in (up to 200 nearest the middle of the screen): purple for ham, orange for GMRS, gray if listed as off the air. HearHam is a community list, so coverage varies by area; fill gaps with your own or a club's list. Your own repeaters go into backups.

Repeater data from HearHam.live is shared by its community for use in apps like this one. FieldMap doesn't use RepeaterBook, whose terms don't allow repeater maps without written permission.

## Building
GitHub Actions builds the APK on every push to `main` (see `.github/workflows/build.yml`).
Core logic (MGRS, AX.25, KISS, APRS) lives in `app/src/main/java/app/fieldmap/core` and has no Android dependencies.
