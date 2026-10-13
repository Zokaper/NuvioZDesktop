# Nuvio Z Reaction Link (Firefox)

Lets Nuvio Z Desktop's experimental Reaction Mode use an open YouTube tab as the reaction video:
the tab appears in the Reactions panel, picking it makes Nuvio Z play that video itself (the tab is
paused and muted), and opening the next reaction in the same tab switches Nuvio Z over to it.

It talks only to Nuvio Z on `127.0.0.1` (ports 47123-47126), and only about YouTube tabs outside
private windows: URL, title and video id. Nuvio Z accepts connections only from an extension origin.

## Try it (temporary, until Firefox restarts)

`about:debugging#/runtime/this-firefox` -> **Load Temporary Add-on...** -> pick `manifest.json`.

## Install permanently (release Firefox)

Release Firefox only keeps signed add-ons. Sign it as **unlisted** (private: not in the add-on store,
free, automated) with Mozilla's `web-ext` and your AMO API credentials
(https://addons.mozilla.org/developers/addon/api/key/):

    npx web-ext sign --channel=unlisted --source-dir . --api-key=<JWT issuer> --api-secret=<JWT secret>

The signed `.xpi` lands in `web-ext-artifacts/`; drag it onto a Firefox window to install.
