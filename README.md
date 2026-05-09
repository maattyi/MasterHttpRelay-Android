# VELUM — Android v1.0.0

Professional Android relay engine and VPN client.

## Architecture

```
Apps on this phone
       ↓
RelayVpnService  (System VpnService — TUN routes traffic to local proxy)
       ↓
HttpProxyServer  (127.0.0.1:8080)
       ↓
RelayClient      (V3 engine port)
       ↓
Google Apps Script / Cloudflare Worker
```

## Features

* **Modern UI** — High-contrast professional design with glassmorphism and smooth animations.
* **Floating Navigation** — Intuitive pill-shaped navigation anchored for ease of use.
* **Transparent Proxying** — Integrated HTTP and SOCKS5 listeners for flexible routing.
* **Certificate Management** — Built-in root CA generation and one-tap system installation.
* **Per-App Routing** — Precise control over which applications use the tunnel.
* **Live Diagnostics** — Real-time logging and bandwidth monitoring.

## Build Instructions

```bash
# Build the production APK:
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/velum-v1.apk
```

## Setup

1. **Activate Profile**: In the Profiles tab, add your Apps Script deployment ID and auth key.
2. **Install Certificate**: In Settings, use "Install Root CA" to enable HTTPS relaying.
3. **Connect**: Tap the main button on the Dashboard to start the VPN.

## Project Structure

* `core/`: Configuration, persistence, and state management.
* `net/`: Core relay engine (RelayClient).
* `proxy/`: HTTP and SOCKS5 local servers.
* `vpn/`: Android VpnService implementation.
* `cert/`: MITM certificate generation and management.
* `ui/`: Compose-based user interface and theme.
