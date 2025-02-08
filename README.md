[![Android CI](https://github.com/cbeyls/MediaSession2MQTT/actions/workflows/android.yml/badge.svg)](https://github.com/cbeyls/MediaSession2MQTT/actions/workflows/android.yml)

# MediaSession2MQTT
**Publish the current Android MediaSession state to an MQTT broker**

This Android application is designed to run as a background service on Android TV devices (or any other kind of Android device used as a media player) and publish the current state of media playback to an MQTT broker.

This allows, for example, to monitor in real time the media playback status of the device in home automation software like Home Assistant, and create automations based on the media playback state.

Contrary to other integrations like ADB commands or Google Cast polling, installing this application enables reliable **local push** monitoring for any application that supports MediaSession.
The application is designed to be as lightweight as possible, and uses the very efficient and low latency MQTT protocol to publish its state messages.

## How to build manually

Import the project in Android Studio or use Gradle in command line:

```
./gradlew assembleRelease
```

The result apk file will be placed in `app/release/`. Don't forget to sign the APK before trying to install it on a device.

## Installation

Download the latest APK file on your device. For Android TV, you can get the file from a USB stick or over the network by using a file manager application, or you can use [ADB](https://developer.android.com/tools/adb) to directly install the app remotely.

For ADB, you first need to enable ADB debugging in the developer options of the TV, then type the following commands:

```
adb connect [IP address of your TV]
adb install ./mediasession2mqtt-1.1.3.apk
```

or for an upgrade:

```
adb install -r ./mediasession2mqtt-1.1.3.apk
```

## Configuration

After installing the app, go the the "Apps" list in the Settings menu of your device and find the entry for "MediaSession2MQTT" (the app is designed to not appear in most launchers but only on that screen).

From the application details screen, click "Open" to open the configuration screen of MediaSession2MQTT.

In the configuration screen, start by entering your MQTT broker configuration (protocol version, host name or IP address, port, and username and password if your broker requires authentication). Note that only the unencrypted TCP protocol is currently supported.

Test your connection by clicking on "Test Connection".

Next, specify the QOS level you need for MQTT messages (QOS 0 should be enough for most local connections).

Then, change the device id if you have more than one device connecting to the MQTT broker (default is `1`).

Finally, you need to give the app full access to system notifications. To do so, click on "Open system notification access settings" and check the box for "MediaSession2MQTT".

After navigating back to the MediaSession2MQTT configuration screen, you should now see the following message appear in the status section: ***Actively listening to MediaSessions***.

If you don't see it, try to force stop the app process or restart your device.

### Manually enabling system notification access using ADB

If an error message appears when clicking on "Open system notification access settings", it means that your device doesn't provide an user interface to change these settings, but you can still change them manually using ADB.

To do so, you need to install an ADB client and first connect to the device, either using a USB cable or through the network using the command:

```
adb connect [IP address of your device]
```

Then, type the following command.

For Android 8 and below:

```
adb shell settings put secure enabled_notification_listeners %nlisteners:be.digitalia.mediasession2mqtt/be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
```

For Android 9 and above:

```
adb shell cmd notification allow_listener be.digitalia.mediasession2mqtt/be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
```

After typing the command, it's recommended to restart the device to make sure the change has been registered properly.

As soon as the app configuration screen shows "Actively listening to MediaSessions" and the MQTT connection test was successful, you're good to go!

## Home Assistant MQTT Discovery

This app provides an integration for Home Assistant since version 1.1.0. Check the box "Enable Home Assistant integration" in the settings screen and the MQTT Discovery configuration will also be published, allowing Home Assistant to detect and configure MediaSession2MQTT as a new device automatically.

## The MQTT API

This application is designed to only push state messages and not listen to MQTT commands. The MQTT connection is kept open as long as possible and no keepalive packets are sent. If the connection gets interrupted for any reason, it will be automatically re-established lazily when the next MQTT message needs to be published.

The application publishes the following 3 topics to the MQTT broker (replace `{deviceId}` with your actual device id which is `1` by default):

### mediaSession/{deviceId}/playbackState
The current playback state of the player connected to the current MediaSession, if any. Can be one of the following values: `idle`, `playing`, `paused`.

Note that the `buffering` state is intentionally not supported for the following reasons:
- Since buffering can happen at any time, adding this state makes it harder to detect transitions to and from the `playing` state;
- Buffering can not be considered as a sub-state of `playing` because some applications pre-buffer playback even before the user requests playing the content (e.g. Amazon Prime Video).

### mediaSession/{deviceId}/applicationId

The Android application id of the currently active MediaSession, or an empty String (`""`) if no MediaSession is currently active.

Examples of possible values:

- `com.google.android.youtube.tv`: YouTube for Android TV
- `com.netflix.ninja`: Netflix for Android TV
- `com.amazon.amazonvideo.livingroom`: Amazon Prime Video for Android TV
- `com.disney.disneyplus`: Disney+ for Android TV
- `com.apple.atve.android.appletv`: Apple TV+
- `org.videolan.vlc`: VLC Media Player

### mediaSession/{deviceId}/mediaTitle

The title of the currently playing or paused media, or an empty String (`""`) if no media is currently playing or paused or the title is unavailable.

Note that many applications don't report any title, for example: Netflix, Disney+ for Android TV or Amazon Prime Video for Android TV.

## A note about the Netflix app

The Netflix app reports the `playing` state right from the home screen, especially if video previews are enabled. To limit this effect, you can disable video previews in Netflix or add a condition in your home automation rules to ignore the action if the Netflix application id is detected.

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## Used libraries

* [KMQTT](https://github.com/davidepianca98/KMQTT) by Davide Pianca
* [Dagger](https://dagger.dev/) by The Dagger Authors
* [Kotlin Standard Library](https://github.com/JetBrains/kotlin) by JetBrains s.r.o. and Kotlin Programming Language contributors
* [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) by JetBrains s.r.o.

## Contributors

* Christophe Beyls
