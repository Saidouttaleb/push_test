# Instagram WebView — Build Instructions

## What the app does

- Loads Instagram's **mobile web** interface inside a WebView.
- Injects CSS + JavaScript after every page load to:
  - Hide **suggested posts**, sponsored posts, and the "Suggested for you" HR separator.
  - Hide the **Explore / Search** and **Reels feed** navigation tabs.
  - Hide **suggested story** bubbles (only followed accounts' stories remain).
  - Disable navigation to `/explore`, `/reels/` (the feed), and `/shop`.
- When the app is opened via a **shared reel link** (e.g. from a DM):
  - Only that single reel is shown.
  - All surrounding UI chrome (nav, sidebar, related reels) is hidden.
  - Touch-swipe and scroll to the next reel are blocked.
  - The back button closes the app instead of navigating Instagram.
- Does **not** collect, store, or transmit any user data.
- Enforces HTTPS-only (no cleartext traffic).

## Prerequisites

| Tool | Minimum version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | API 34 (compileSdk) |
| JDK | 17 (bundled with Android Studio) |
| Gradle | 8.4 (downloaded automatically) |
| Android device / emulator | API 26 (Android 8.0) or newer |

---

## 1 — Open the project

1. Launch **Android Studio**.
2. Choose **File → Open…** and navigate to the `InstagramWebView/` folder.
3. Click **OK**. Wait for Gradle sync to finish (first sync downloads ~200 MB).

---

## 2 — (Optional) Change the package name

The default package is `com.example.instagramwebview`.  
If you plan to distribute the APK, replace it everywhere:

```
app/build.gradle          → applicationId
AndroidManifest.xml       → package attribute
java/com/example/...      → rename directory and update package declarations
```

---

## 3 — Build a debug APK

```bash
# From the project root (InstagramWebView/)
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Or in Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

---

## 4 — Build a release APK (signed)

### 4a — Create a keystore (one-time)

```bash
keytool -genkeypair \
  -alias my_key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore my_release_key.jks
```

### 4b — Configure signing in `app/build.gradle`

Add a `signingConfigs` block and reference it in `buildTypes.release`:

```groovy
android {
    signingConfigs {
        release {
            storeFile     file("../my_release_key.jks")
            storePassword "YOUR_STORE_PASSWORD"
            keyAlias      "my_key"
            keyPassword   "YOUR_KEY_PASSWORD"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                          'proguard-rules.pro'
        }
    }
}
```

> **Security tip:** Store passwords in `~/.gradle/gradle.properties` or use
> Android Studio's **Generate Signed Bundle / APK** wizard rather than
> hard-coding them in `build.gradle`.

### 4c — Assemble the signed APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

---

## 5 — Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or drag-and-drop the APK onto a connected device / emulator in Android Studio.

---

## 6 — Sideload without ADB (for sharing)

1. Enable **Settings → Security → Install unknown apps** on the target device.
2. Transfer the `.apk` file via USB, Google Drive, or similar.
3. Tap the file in the Files app and follow the on-screen prompts.

---

## Permissions requested

| Permission | Reason |
|---|---|
| `INTERNET` | Required to load Instagram's web interface |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Required only if the user uploads media (profile picture, post) |
| `READ_EXTERNAL_STORAGE` (API ≤ 32) | Legacy fallback for the above |

No location, camera, microphone, or contacts permissions are requested.

---

## Limitations & known caveats

- Instagram's web interface changes CSS class names and DOM structure
  frequently. The JS/CSS selectors in `MainActivity.kt` may need updating
  if Instagram redesigns their site.
- Push notifications are not supported (no FCM integration).
- Instagram may detect WebView user-agents and show a banner asking you to
  use their app. The desktop UA string mitigates this.
- This app is for personal use. Review Instagram's Terms of Service before
  distributing.
