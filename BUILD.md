# Building the VLC Android APK from this repository

This guide covers building the VLC for Android application (`.apk`) from source on a Linux
machine (Ubuntu/Debian is the officially supported setup; macOS and Windows 10 via WSL can work
but are not officially supported).

There are two build paths:

- **Path A — JVM-only build (no native compilation):** Gradle fetches prebuilt `libvlc` and
  `medialibrary` artifacts from Maven Central. This is by far the fastest way to get an APK and
  is enough to try or modify the app UI/logic (like the new external audio track picker).
- **Path B — full native build (LibVLC + Medialibrary from source):** uses
  `buildsystem/compile.sh` to compile the VLC engine itself. Only needed if you modify the
  native/JNI code.

---

## 1. Prerequisites

### 1.1 Install the required tools

```bash
sudo apt update
sudo apt install openjdk-17-jdk git wget unzip
```

### 1.2 Install the Android SDK

1. Download Android command line tools:
   <https://developer.android.com/studio#command-line-tools-only>
2. Unpack them to, for example, `~/Android/sdk/cmdline-tools/latest` and accept the licenses:

   ```bash
   mkdir -p ~/Android/sdk/cmdline-tools
   unzip commandlinetools-linux-*.zip -d ~/Android/sdk/cmdline-tools
   mv ~/Android/sdk/cmdline-tools/cmdline-tools ~/Android/sdk/cmdline-tools/latest
   yes | ~/Android/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/Android/sdk --licenses
   ~/Android/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/Android/sdk "platforms;android-36" "build-tools;36.0.0" "platform-tools"
   ```

3. Export the SDK location (this repo's build also reads it from `local.properties`, which
   `compile.sh` regenerates for you):

   ```bash
   export ANDROID_SDK=$HOME/Android/sdk
   ```

> The application itself does **not** need the NDK for Path A. The NDK is only required by the
> native build (Path B), where `compile.sh` handles it (see section 4).

### 1.3 (First build only) remote-access web client

The Remote Access feature embeds a web client. For **debug builds** it is taken from
`application/remote-access-client/`; in **release** builds a prebuilt artifact is used instead.
A debug build without it still works and just prints a warning at the end of `compile.sh`.

---

## 2. Path A — Quick APK build (recommended, no VLC engine compilation)

From the repository root:

```bash
./gradlew assembleDebug
```

If there is no `gradlew` wrapper yet (this repository does not ship one), generate it once with a
local Gradle (the version expected by the project is printed in `buildsystem/compile.sh`,
currently **Gradle 9.3.1**):

```bash
gradle wrapper --gradle-version 9.3.1   # run from the repository root
```

or simply bootstrap everything through the project's own script, which installs the correct
Gradle, generates the wrapper, sets up `local.properties` and then runs the same JVM build:

```bash
sh ./buildsystem/compile.sh -t    # uses prebuilt contribs; builds & installs on a device
```

**Output:** `application/app/build/outputs/apk/debug/VLC-Android-*-debug-*.apk`
(one APK per ABI, plus a universal one).

### Build variants

| Variant | Command | Notes |
|---|---|---|
| Debug | `./gradlew assembleDebug` | debug signing, `.debug` app id, can run side-by-side with the store app |
| Dev | `./gradlew assembleDev` | debug code with locally built LibVLC/Medialibrary modules |
| Release | `./gradlew assembleRelease` | unsigned; for Play Store use `bundleRelease` |
| Signed release | `./gradlew assembleSignedRelease` | needs keystore properties, see below |

Signing a release build: create a `gradle.properties` entry set (or let `compile.sh` do it) with
`keyStoreFile`, `storealias`, `storepwd` (or the `PASSWORD_KEYSTORE` environment variable), then
run `assembleSignedRelease`.

Install on a connected device/emulator:

```bash
adb install -r application/app/build/outputs/apk/debug/VLC-Android-*-debug-arm64-v8a.apk
```

---

## 3. Building for Android TV / Android Automotive

TV UI ships in the same application module — no special variant is needed. The `vlcBundle` build
types (used by CI for bundles) require Android 11+ (minSdk 30 for that type only).

---

## 4. Path B — Full native build (LibVLC + Medialibrary from source)

Use this only when you change native code (`libvlcjni`, VLC core, medialibrary C++).
Read the wiki page first: <https://wiki.videolan.org/AndroidCompile/>

```bash
sudo apt install automake ant autopoint cmake build-essential libtool-bin \
    patch pkg-config protobuf-compiler ragel subversion unzip git \
    flex python wget openjdk-17-jdk
export ANDROID_SDK=$HOME/Android/sdk
export ANDROID_NDK=$HOME/Android/ndk   # NDK r27+ for VLC 4, NDK 21 for the VLC 3 track
sh ./buildsystem/compile.sh -a arm64   # or arm, x86, x86_64, all
```

Useful `compile.sh` flags:

| Flag | Meaning |
|---|---|
| `-a <abi>` | target ABI: `arm`, `arm64`, `x86`, `x86_64`, `all` |
| `-r` / `--release` | release build (also produces an `.aab` bundle) |
| `--signrelease` | release build signed with your keystore |
| `-l` | build **only** LibVLC (produces an `.aar`) |
| `-ml` | build **only** the medialibrary (produces an `.aar`) |
| `-t` | use prebuilt contribs (faster) |
| `-s <file> -p <pwd>` | keystore for signed builds |
| `--init` | (re)initialize the libvlcjni sources |

The script clones `libvlcjni` into `./libvlcjni/` automatically, compiles the VLC engine,
publishes it to your local Maven repository and finally runs the same Gradle app build
(`assembleDev` by default). The APK lands in the same `application/app/build/outputs/apk/` folder.

**VLC 4 track:** add `-vlc4` if you want to build against the VLC 4 engine
(this switches several modules to their `vlc4/` source sets and needs NDK r28).

> Note: VLC 3 builds are pinned to NDK 21 because it is the last one supporting Android API 17.

---

## 5. Verifying this build after code changes

- `./gradlew assembleDebug` — the fastest signal that everything still compiles.
- `./gradlew :application:vlc-android:testDebugUnitTest` — unit tests for the app modules.
- Try the new feature: play a video → tap the screen → ⋮ (or the tracks button) → *Audio* →
  expand the section with the arrow → **Select audio file** → pick an `.mp3`/`.m4a`/… file →
  the external audio track plays with the video and is remembered for that video
  (it appears next to *Audio delay* in the same menu, in the same style).

---

## 6. Troubleshooting

- **`SDK location not found`** — create `local.properties` in the repo root with
  `sdk.dir=/absolute/path/to/Android/sdk`, or export `ANDROID_SDK` and run `compile.sh` once.
- **Gradle version mismatch** — the wrapper must match the version declared in
  `buildsystem/compile.sh` (`GRADLE_VERSION`); delete `gradlew` and re-run the script if unsure.
- **`Failed to install the following Android SDK packages`** — run the `sdkmanager` command from
  section 1.2 with the same `--sdk_root` used by the build.
- **Out of memory during build** — `org.gradle.jvmargs` in `gradle.properties` is already set to
  `-Xmx4g`; free RAM or lower `org.gradle.parallel` workers.
- **Debug build warns about missing remote access web client** — expected when
  `application/remote-access-client/remoteaccess/dist` is absent; release builds embed a prebuilt
  artifact instead.
