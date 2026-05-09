# Cloudflare edge integration

The Android RelayClient talks to Apps Script, which optionally forwards
to a Cloudflare Worker. The Worker code is shared with the desktop
project — see `windows-app/app/cloudflare/worker.js`. No Android-side
changes are needed when toggling Cloudflare-chain mode on a profile;
the difference is purely backend configuration.
