# Native Android Project

This Android Studio project is a native Kotlin app for the Karnataka Vehicle Validation flow.

## Included

- Native Android UI with home screen and result cards
- Automatic Petrol -> Diesel fallback lookup
- HTML form submission against the Karnataka Transport source
- Gradle wrapper
- Debug APK build output

## Open in Android Studio

Open the `android-native` folder in Android Studio.

## Build from command line

```powershell
cd android-native
.\gradlew.bat assembleDebug
```

## Current output

The latest debug APK was built successfully at:

`app\build\outputs\apk\debug\app-debug.apk`

## Important note

The Karnataka source site currently has a certificate problem, so this Android app uses the same unsafe TLS workaround as the desktop app to keep the live lookup working.
