# Lowpoly Android

## Multiplayer connection modes

- **AUTO** (recommended): uses the shared online PHP relay for cross-device discovery/state, and attempts direct WebRTC connections where possible.
- **WEB**: uses the shared-hosting PHP relay only. Use this for players on different networks, including laptops and phones.
- **LAN**: direct/local multiplayer for devices on the same Wi-Fi or hotspot. It is not an internet-wide mode.

## Android start menu

- **PLAY ONLINE** opens the public website URL entered in the Android start menu. This makes the Android app use the same website and `mp.php` endpoint as laptop/browser players.
- **HOST GAME / JOIN LAN** keep the local Wi-Fi/hotspot multiplayer mode.

## Shared hosting setup

Upload `index-multiplayer-updated.html` as your site's game page (rename it to `index.html` if appropriate) and keep `mp.php` in the same directory. The game page calls `mp.php` using a relative URL, so both must be on the same origin/path.

In the Android app, enter the exact public game URL, for example `https://example.com/game/`. Do not use the example literally.

The shared-hosting `mp.php` relay must be publicly reachable over HTTPS and PHP must be able to write to its temporary directory. The relay currently supports up to 24 simultaneous players and expires inactive players after 8 seconds.

## Build

Open the project in Android Studio and build the `app` debug APK, or use the included GitHub Actions workflow.
