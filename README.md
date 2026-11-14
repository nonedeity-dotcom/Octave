# Octave

Android music player: add songs from your phone and listen in the app (background playback, notification controls).

Stack: Kotlin, Jetpack Compose (Material 3), Media3 (ExoPlayer + MediaSessionService).

## Run
1. Install Android Studio (latest).
2. File -> Open -> select the `Octave` folder. Wait for Gradle sync.
3. Connect your phone (USB debugging on) or start an emulator, press Run.

## GitHub build
Every push to `main` builds a signed APK and publishes it in the repo's **Releases**.
Download `Octave.apk` there and install it over the old version: updates keep your songs.
Keep `octave-release.jks` safe and keep the repo private. A new key means Android will refuse updates.
