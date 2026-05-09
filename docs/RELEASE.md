# Release / signing guide

## 1 — Generate the release keystore (one-time)

```bash
keytool -genkey -v \
  -keystore velum-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias velum
```

Store the `.jks` file outside the repo (e.g. `~/keys/`). **Back it up** —
losing it means you can never publish updates of the same app under the
same package name.

## 2 — Wire the keystore into the build

Two equivalent options:

### Option A — local file (recommended for laptop builds)

Copy `keystore.properties.template` to `keystore.properties` in the
project root and fill in the four fields. The Gradle script reads
this file automatically when building **release**.

### Option B — environment variables (CI / GitHub Actions)

```bash
export MASTERRELAY_KEYSTORE_PATH=$HOME/keys/velum-release.jks
export MASTERRELAY_KEYSTORE_PASSWORD='…'
export MASTERRELAY_KEY_ALIAS='velum'
export MASTERRELAY_KEY_PASSWORD='…'
```

## 3 — Build the signed release APK

```bash
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/Velum-Forest-v1.apk
```

The APK is signed with v1 + v2 + v3 + v4 schemes, which is what Play
Protect / sideloading checks expect. Signed sideloads no longer trigger
the "unknown / dangerous app" warning that an unsigned debug APK does.

## 4 — Build the App Bundle (for Play submission)

```bash
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

## 5 — Verify the signature

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/Velum-Forest-v1.apk
```

Expected output begins with:

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

## 6 — Install for testing

```bash
adb install -r -t app/build/outputs/apk/release/velum-v1.apk
```

`-t` allows installing test-signed APKs; remove it if you've signed with a
production keystore.
