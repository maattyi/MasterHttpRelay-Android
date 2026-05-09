# GooseRelayVPN integration

GooseRelayVPN's protocol is implemented in Go and uses a different wire
format. We don't speak its frame protocol directly — instead V3 adopts
the **operational concepts** from Goose into our own GAS-based engine:

* Tunnel auth keys per profile (`tunnelKey` in `Profile.kt`)
* Multi-deployment-ID round-robin with health scoring
  (`MultiIdDispatcher.kt`)
* Smart-balance across profiles
* Profile import/export as JSON

If you have an existing Goose `client_config.json`, copy each entry
under `script_keys[].id` into a profile's *Apps Script IDs* field and
copy `tunnel_key` into the *Tunnel key* field. The auth key from the
GAS deployment goes into *Auth key*.
