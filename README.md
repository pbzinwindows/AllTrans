<p align="center">
  <img src="assets/logo.png" width="112" alt="AllTrans logo">
</p>

<h1 align="center">AllTrans</h1>

<p align="center">
  Runtime translation for Android apps.
  <br>
  Translate text inside selected apps without modifying the original APK.
</p>

<p align="center">
  [![Repo Views](https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FAllTrans&label=repo%20views&countColor=%230e75b6&style=flat)](https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FAllTrans)
  <img src="https://img.shields.io/badge/Android-10%20to%2016-3DDC84?logo=android&logoColor=white" alt="Android 10 to 16">
  <img src="https://img.shields.io/badge/LSPosed%20%2F%20Xposed-supported-6C5CE7" alt="LSPosed and Xposed supported">
  <img src="https://img.shields.io/badge/License-GPLv3-2E7D32" alt="GPLv3 license">
</p>

## Overview

AllTrans translates text inside Android apps at runtime. It works like browser page translation, but for app interfaces: choose a source language, choose a target language, select the apps you want, and AllTrans replaces visible text while the app is running.

The project is designed for Xposed-compatible environments such as LSPosed. It can translate many normal Android apps without repacking, resigning, or modifying their APK files.

This enhanced edition focuses on making app translation faster, smarter, and more reliable on modern Android. It includes improvements to translation detection, runtime text replacement, compatibility handling, and LSPosed-based operation, while keeping the setup simple for daily use.

## Features

- Runtime app text translation through Xposed/LSPosed hooks.
- Improved translation detection engine for better text discovery inside apps.
- Smarter runtime replacement for dynamic screens and delayed app content.
- Per-app translation control.
- Global source and target language settings.
- Optional per-app overrides for difficult apps.
- Offline Google translation files support.
- Microsoft Translator support.
- WebView translation delay options.
- Aggressive mode for apps that need deeper text replacement.
- Compatibility options for apps that load slowly or crash when text is replaced too early.
- Compatible with Android 16 and older supported Android versions, focused on Android 10+.

## Screenshots

<p align="center">
  <img src="screenshots/Screen1.png" width="230" alt="AllTrans main screen">
  <img src="screenshots/Screen2.png" width="230" alt="AllTrans app settings">
</p>

## Translation Example

<p align="center">
  <img src="screenshots/Joint1S.png" width="210" alt="Translation example 1">
  <img src="screenshots/Joint2S.png" width="210" alt="Translation example 2">
  <img src="screenshots/Joint3S.png" width="210" alt="Translation example 3">
</p>

## Requirements

- Android 10 or newer, including Android 16.
- LSPosed, Xposed, EdXposed, VirtualXposed, or another compatible Xposed environment.
- The target app must be enabled in the module scope when using LSPosed.
- Some games and heavily custom-rendered apps may not translate because they do not use normal Android text views.

## Basic Setup

1. Install AllTrans.
2. Enable AllTrans in LSPosed/Xposed.
3. Select the apps that AllTrans should hook.
4. Reboot or restart the target app.
5. Open AllTrans and choose the source and target languages.
6. Enable translation for the apps you want.

## Troubleshooting

If an app does not translate, confirm that the app is selected in the LSPosed scope and enabled inside AllTrans.

If an app freezes or crashes, open that app's AllTrans settings and increase the delay before replacing translated text. For WebView-heavy apps, also increase the WebView translation delay.

If only part of an app is translated, try Aggressive Mode for that app.

Games and apps that draw text directly with custom engines may not be compatible.

## Video

A Gadget Hacks video for an older AllTrans version is available here:

<p align="center">
  <a href="https://www.youtube.com/watch?v=sKDtkmISi6k">
    <img src="https://img.youtube.com/vi/sKDtkmISi6k/0.jpg" width="420" alt="AllTrans video tutorial">
  </a>
</p>

## License

AllTrans is licensed under the GNU General Public License v3.0 or later. See `LICENSE.GPL`.
