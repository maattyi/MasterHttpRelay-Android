# Setup walkthrough

End-to-end checklist for getting VelumVPN Android v1 running on a
device, mirroring the Windows V3 setup.

## 1 — Backend (one-time)

You need at minimum:

1. **A Google Apps Script web-app deployment** with `Code.gs` from the
   Windows app's `app/gas/` folder.
2. (Optional) **A Cloudflare Worker** with `worker.js` from
   `app/cloudflare/`.

Both are deployed exactly the same as for the desktop app — see the
`README.md` of the Windows project. Pick a strong `MASTER_RELAY_AUTH_KEY`
in Apps Script *Script Properties* and remember it; you'll paste it
into the phone in step 3.

## 2 — Build / install the APK

```bash
cd android-app
./gradlew :app:assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Or open the project folder in Android Studio and **Run** on a connected
device.

## 3 — First launch on the phone

1. Open the app. The "Idle" health pill is green — no relay running yet.
2. Tap **Profiles → New profile**.
3. Fill:
   - *Name* — anything (e.g. "Home")
   - *Auth key* — same value as `MASTER_RELAY_AUTH_KEY` in Apps Script
   - *Apps Script IDs* — one per line; each is the deployment ID
     visible in the Apps Script web-app URL after `/macros/s/` and
     before `/exec`
   - (Optional) *Worker host* — the workers.dev URL (without scheme)
     and tick **Use Cloudflare Worker chain**
4. **Save** → back in the list, tap **Activate**.
5. **Settings → Install / Export CA**, tap the export button. The
   share-sheet will open: pick **Files** to save, then go to
   *Android Settings → Security & privacy → Encryption & credentials →
   Install a certificate → CA certificate*, navigate to where you saved
   the `.cer` and confirm.
6. **Dashboard → Start VPN**. Confirm the consent dialog.

You should see live bandwidth start to flow on the dashboard within a
few seconds of opening any app on the device.

## 4 — Troubleshooting

| Symptom | Fix |
|---|---|
| Health pill stays "Unstable" | Check the script IDs are correct; check Script Properties has the auth key set |
| No HTTPS sites work | The CA cert was not installed correctly — repeat step 3.5 |
| Specific app blocks everything | Use **per-app proxy mode** in Settings, then exclude that app |
| High battery drain | Enable **Battery-saver mode** in Settings; this caps concurrency |
| Proxy fine but VPN won't connect | Some manufacturer ROMs (MIUI, OxygenOS) auto-kill VPN services in the background — disable battery optimization for the app |

## 5 — Sharing config across devices

Profiles → kebab menu (or directly via the export button) → **Export**
writes a JSON file. Copy that file to another device (or to the desktop
app) and import it: same script IDs, same auth key, same profile.
