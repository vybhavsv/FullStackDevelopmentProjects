# KA-Vehicle-PUC-Check App

This project now supports three ways to use the same app:

- Browser app on desktop with a local Python server.
- Installable PWA on Android through Chrome.
- Windows desktop executable built with PyInstaller.

## Features

- Welcome home page with vehicle registration input.
- Checks the Karnataka Transport Department page with `Petrol` first.
- Falls back to `Diesel` automatically when Petrol returns no records.
- Shows the matched result table directly inside the app.
- Exposes a PWA manifest and service worker so the app can be installed on Android.

## Run locally

```powershell
python app.py
```

Then open [http://127.0.0.1:8000](http://127.0.0.1:8000).

## Install on Android

1. Start the app on a machine that your phone can reach on the same network.
2. Open the app in Chrome on your Android phone.
3. Use Chrome's `Add to Home screen` or tap the in-app `Install on Android` button when Chrome offers it.

This creates an installed app experience on Android, but it is not a signed APK build.

## Build a Windows executable

Install dependencies and run:

```powershell
python -m pip install -r requirements.txt
build_exe.bat
```

The executable will be created in `dist\KA-Vehicle-PUC-Check.exe`.

## Important note

The Karnataka source website currently presents a certificate issue on this machine, so the app disables TLS verification for that upstream request.

