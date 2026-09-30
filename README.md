# Apple TV Remote

[![Release](https://img.shields.io/github/v/release/jrs8205/Apple-TV-Remote)](https://github.com/jrs8205/Apple-TV-Remote/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/jrs8205/Apple-TV-Remote/total)](https://github.com/jrs8205/Apple-TV-Remote/releases)

An open Android remote control for Apple TV. It talks to the Apple TV directly over the local
network using the Companion Link protocol, so no account, cloud service or extra hardware is needed.

**One button from a dark living room to the Apple TV home screen.** With an LG webOS TV the power
button switches the television on over Wi-Fi, selects the Apple TV's HDMI input and wakes the
Apple TV, even from deep sleep. No TV remote, no input menu.

## Features

- Finds Apple TVs on the local network and pairs with the PIN shown on the TV
- Siri Remote style layout with the controls placed near the bottom of the screen
- Touchpad, directional swipe and d-pad navigation modes; holding the pad opens the app options on the
  Home Screen for rearranging, foldering and deleting apps
- Play/pause, skip, volume, mute, back, home and power
- Text entry when the Apple TV shows a keyboard
- Playback controls in the notification shade and a Quick Settings tile
- LG webOS TV integration: one tap turns the LG TV on over the network, switches it to the Apple
  TV's HDMI input and wakes the Apple TV through HDMI-CEC
- Pairs with the LG TV once; the TV's Wake-on-LAN address is learned automatically
- Reconnects on its own when the Apple TV changes its address or port
- English and Finnish

## LG TV integration

An Apple TV in deep sleep does not answer on the network and ignores Wake-on-LAN packets, so the
app wakes it through the television instead. In Settings, LG TV, enter the TV's IP address and
pair with it (the TV asks for confirmation on screen). Pairing reads the TV's input list and selects
the input the TV labels as the Apple TV; check it under Apple TV input. The TV must have "Turn on
via Wi-Fi" (or mobile) and SIMPLINK (HDMI-CEC) enabled. After that the power button on the remote
turns the TV on, switches the input and connects.

The TV's certificate is pinned on pairing; if the TV ever presents a different one, pair again.

## Installing

Download the latest APK from the [Releases](https://github.com/jrs8205/Apple-TV-Remote/releases/latest)
page and open it on the phone. Android asks once to allow installs from the browser or file
manager.

Requires Android 14 (API 34) or newer. On Android 17 the app asks for local network access,
which it needs to discover and reach the Apple TV.

## Building

Open the project in Android Studio or run:

```
gradlew.bat assembleDebug
```

Unit tests and lint run once with:

```
gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
```

A signed release needs a `keystore.properties` file at the project root with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`; without it `assembleRelease` produces an unsigned
APK. Neither the properties file nor the keystore belongs in version control.

## License

GPL-3.0. See [LICENSE](LICENSE).
