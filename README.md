# C25K Custom Coach

C25K Custom Coach is a customizable Couch-to-5K training companion. Set your preferred run and walk intervals, define the total workout length or number of cycles, and choose optional warm-up and cool-down segments. The app keeps you on track with Material 3 UI controls, Text-to-Speech audio cues that duck other media, and a resilient foreground service that stays active even when the screen is off.

<p>
  <img src="docs/screenshots/setup.png" alt="Setting up a workout: run and walk intervals, total time, and warm-up and cool-down walks" width="240">
  <img src="docs/screenshots/workout.png" alt="A workout in progress: warm-up, 1:59 left, cycle 1 of 3, with music controls" width="240">
  <img src="docs/screenshots/notification.png" alt="Pause and Stop workout controls in the notification during a workout" width="240">
</p>

## Why I built it

I built this for my wife. The Couch to 5K apps she tried locked her into their own plan, and she wanted her own run and walk intervals, cues she could hear over her music, and a timer that kept going with the screen locked.

## Features
- Adjustable run and walk interval lengths.
- Configure either a total workout duration or an explicit number of run/walk cycles.
- Optional three-minute warm-up and cool-down walks.
- Foreground service with a persistent notification offering pause, resume, and stop actions.
- Text-to-Speech prompts using the device locale while respectfully ducking other audio playback.
- Works when the device is locked, and exposes controls directly in the notification shade.
- Choose a YouTube Music playlist for each workout, with previous, play/pause and next controls on the workout screen.
- A month-by-month calendar of your workouts, with a summary for any day, and the option to add a finished workout to your phone's calendar app.
- Lifetime totals: completed workouts, run and walk time, average cycles per workout and active minutes. You can export your workout history to share it.

## Getting Started
1. Open the project in Android Studio Iguana or newer.
2. Sync Gradle to download dependencies (compileSdk 34, minSdk 26).
3. Build and run on a device or emulator with Google Play services for best TTS support.

From the main screen, enter your desired intervals and tap **Start workout** to begin. Use the notification controls or the in-app **Stop workout** button to finish early.

## Licence

Licensed under the Apache License 2.0. See [LICENSE](LICENSE).
