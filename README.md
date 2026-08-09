# Arduino USB Bridge (Android)

This is a real Android Studio project — not a finished APK. I cannot compile
`.apk` files in this environment (no Android SDK / build tools here), so this
needs to be built once using Android Studio, on any PC/laptop, by you or anyone
willing to help. After that, the resulting APK can be shared/installed on any
phone with no PC needed again.

## What this actually does
- Uses Android's native `UsbManager` API (`MainActivity.kt`) instead of the
  browser's Web Serial API — this works around the fact that Chrome for
  Android does not implement Web Serial at all (confirmed via Chromium's own
  docs).
- Loads your existing web IDE (`arduino-web-ide-2.html`, copied here as
  `app/src/main/assets/index.html`) inside a WebView.
- A small JavaScript bridge (added near the `WEB SERIAL` section of the HTML)
  makes the existing code talk to the native USB layer automatically when
  running inside this app, while still working normally as a website in a
  regular desktop/Chromebook browser.

## How to build the APK
1. Install Android Studio (free) on a Windows/Mac/Linux PC — one-time only,
   just for building.
2. Open this folder (`ArduinoUSBBridge`) as a project in Android Studio.
3. Let Gradle sync (downloads the `usb-serial-for-android` library
   automatically from JitPack — needs internet once).
4. Build → Build Bundle(s)/APK(s) → Build APK(s).
5. The `.apk` will appear in `app/build/outputs/apk/debug/`. Copy that file to
   your phone and install it (you'll need to allow "install unknown apps" for
   whichever app you use to open it).

## Known limitations / things to check
- The native side currently opens the port at a **fixed 9600 baud** — the
  baud rate dropdown in your UI won't actually change it yet. Let me know if
  you want that wired through (it's a small addition to `requestConnection()`
  in `MainActivity.kt`).
- `usb-serial-for-android` supports CH340, FTDI, CP2102, and CDC-ACM chips —
  covers essentially every Uno/Nano/clone board.
- This app requests **runtime** USB permission the first time you connect —
  Android will show its own native "Allow this app to access the USB
  device?" popup. That's expected and is the real, working equivalent of the
  Web Serial popup that never worked on Android Chrome.
- No example sketches or extra libraries are bundled — keeping it lightweight
  was the whole point, per your original goal.

## Files
- `app/src/main/java/com/thameem/arduinoide/MainActivity.kt` — USB
  permission handling, serial read/write, WebView bridge.
- `app/src/main/res/xml/device_filter.xml` — vendor IDs Android should
  recognize as Arduino-compatible.
- `app/src/main/AndroidManifest.xml` — USB host permission + auto-launch
  when a device is plugged in.
- `app/src/main/assets/index.html` — your IDE, with the bridge patch added.
