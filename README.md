# Flow Launcher

A compact Android TV home screen built with native Java views, laid out like the Apple TV home screen: a Top Shelf of content from the focused app, a six-column app grid, an app-switcher style Recents screen, and animated app transitions.

![Flow home screen](docs/images/home.png)

## Features

- Apple TV style layout: Top Shelf above a six-column grid of 5:3 app tiles, with the top row in a semi-transparent dark dock as in tvOS 26 and the next row peeking below it. The focused tile quickly grows and lifts with a soft shadow (lower rows also show its name), and it tips when you reach the edge of the grid.
- The Top Shelf follows apps in the top row, and the backdrop picks up a soft glow in that app's color. At rest only the Top Shelf and the top row show; moving down to the next rows slides the whole page up.
- Contextual content from Android TV channels and Watch Next, where installed apps publish it.
- Expanding app-opening and collapsing return animations.
- Reorder apps, hide them from the home screen, and restore them from **Hidden apps** under the settings gear.
- Per-app tile styles: original banner, icon and name, icon on dark, name only, and supported brand logos. Custom pictures appear when configured.
- Recent apps in an Apple TV style app switcher: large centered cards with the app's icon and name, reopen, swipe up to dismiss, and Clear all.
- Clock, Android settings shortcut, and a minimal Flow Clock screensaver.
- Native Android implementation with no third-party runtime libraries.

### Opening an app

Recorded on an onn. 4K Streaming Box. The demo follows Nuvio Legacy from its dock tile through its splash screen to its loaded home screen.

![Nuvio opening and landing](docs/images/opening.gif)

[View the loaded Nuvio screen](docs/images/app-landing.png)

### Personalization and recent apps

| App menu | Tile styles |
| --- | --- |
| ![App menu](docs/images/app-menu.png) | ![Tile styles](docs/images/tile-styles.png) |

![Recent apps](docs/images/recents.png)

### Recent apps and closing animations

Open Recents with double Home, then press Up to dismiss the selected Nuvio app. The capture shows the Nuvio card moving upward and the empty state.

![Recent apps entrance and app dismissal](docs/images/recents-dismiss.gif)

Press Home from an app to return to the dock with a collapsing tile animation. Returning home does not force-stop the app.

![App-to-home return animation](docs/images/app-return.gif)

## Install

Requires **Android TV / Google TV running Android 9 or newer**. Package ID: `com.gfs.flow`.

1. Download the latest Flow APK from [Releases](https://github.com/sghmire/flow-launcher-tv/releases/latest) and transfer it to your TV.
2. Open the APK with a file manager. If prompted, allow that file manager to **install unknown apps**, then choose **Install**.
3. Open **Flow** from your apps list.
4. To use Flow when you press Home, choose it in your TV's default Home app settings or Home chooser, if supported.

For updates, install the newer APK over the existing version to keep your layout and preferences. If you previously installed a development build signed with a different key, you must uninstall it first; this removes Flow's saved settings.

Home-button support varies by TV. You can switch back through the default Home settings; there is no need to disable the stock launcher.

<details>
<summary>Optional setup: content rows, recent-app history, and ADB</summary>

Allow TV-listing access if Flow requests it. Recents always lists apps opened from Flow; to include apps opened elsewhere (for example from a voice search or another app), enable **Usage access** for Flow in the TV settings where available. Content rows depend on what installed apps publish and may be empty.

If you prefer installing from a computer, enable ADB debugging on the TV and authorize your computer. Use the address and port shown by the TV; wireless pairing, when required, uses a separate pairing port.

```sh
# Only if the TV requires wireless pairing:
adb pair TV_IP:PAIRING_PORT

adb connect TV_IP:DEBUG_PORT
export ANDROID_SERIAL=TV_IP:DEBUG_PORT
adb install -r /path/to/flow.apk
adb shell am start -n com.gfs.flow/.HomeActivity
```

USB connections can use their device serial instead. Optional permissions for content rows and recent-app history:

```sh
adb shell pm grant com.gfs.flow android.permission.READ_TV_LISTINGS
adb shell appops set com.gfs.flow GET_USAGE_STATS allow
```

On TVs supporting this command, set Flow as the default Home app:

```sh
adb shell cmd package set-home-activity com.gfs.flow/.HomeActivity
```

</details>

## Clock screensaver

Flow's gear opens Android settings directly (when apps are hidden, it first offers Settings or Hidden apps). Select **Flow Clock** in your TV's screensaver settings if that menu is available. Android manages the idle delay and activation. Some firmware, including the tested onn. box, does not expose a screensaver picker; selecting Flow Clock on those devices requires ADB.

The screensaver shows a dim clock centered on black, follows the device's 12/24-hour preference, and exits on remote input. It runs in a separate process and creates its view only when started, so the launcher does not preload screensaver images or animations. There are no images or per-frame animation loops. Actual memory overhead has not yet been measured on a TV. Screensaver settings and availability vary by firmware.

## Controls

| Button | Action |
| --- | --- |
| Left / Right / Up / Down | Move between tiles in the grid or between cards |
| Up from the top row | Focus the first Top Shelf card, when available |
| Down from the Top Shelf | Return to the app it belongs to |
| OK / Enter | Open the focused app, card, folder, or action |
| Hold OK, or Menu on an app | Open Move, Change icon, Hide from home, and App info |
| D-pad in Move mode | Reorder the selected app through the grid |
| OK / Back in Move mode | Save / cancel the move |
| Hold OK, or Menu, on a hidden app | Show it on home again, Change icon, or App info |
| Back | Close the current menu, Hidden apps, or Recents overlay |
| Home | Return to Flow; while already home, return to the first app |
| Double Home, or an app-switch key | Open Recents when the TV delivers these keys to Flow |
| Up on a recent app | Dismiss it and request background-process cleanup |

Recents lists apps opened from Flow, plus system usage history when Flow has Usage access (a hint appears in Recents when it does not). It is not a system task-manager snapshot. Dismiss/Clear all is not a guaranteed force-stop; Android and device firmware control background-process behavior.




## Artwork

App banners and recommendation artwork come from installed apps and their TV providers. Supported logo artwork is fetched from [Simple Icons](https://simpleicons.org/) and cached locally. App names, logos, and content shown in the screenshots belong to their respective owners.
