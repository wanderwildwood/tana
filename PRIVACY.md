# Privacy

Files talks to the servers you add, to the devices nearby that you send to or accept files from, and to
nothing else.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## What it asks for, and why

`app/src/main/AndroidManifest.xml` declares six permissions.

- **All files access** (`MANAGE_EXTERNAL_STORAGE`) — to see and change what is on the phone's
  storage and SD card, which is what a file manager is for. Android asks you for this on a page
  of its own; the app cannot grant it to itself, and you can take it back there at any time.
- **Internet** (`INTERNET`) — to reach the servers you add. The only addresses the app ever
  connects to are the ones you typed into it. There is no analytics, no crash reporting, no
  update check and no advertising.
- **Install packages** (`REQUEST_INSTALL_PACKAGES`) — so that tapping an APK hands it to
  Android's installer. Without it the installer refuses. The app never installs anything itself;
  the installer shows you the app and asks, and asks once whether to allow installs from here.
- **Foreground service** (`FOREGROUND_SERVICE`) — so a long copy carries on with the screen off,
  with a notification showing while it runs.
- **Multicast** (`CHANGE_WIFI_MULTICAST_STATE`) — so the Nearby page can hear other devices
  announce themselves on the Wi-Fi. Android drops those messages unless an app asks for them; the
  app asks only while that page is open.
- **Keep awake** (`WAKE_LOCK`) — so a Nearby transfer is not cut off when the screen goes dark.
  Held only while one is under way.

## Server passwords

A server added with a user and password keeps that password in the app's own private storage
on the phone, which no other app can read, and **sealed** there with a key held in the phone's
keystore (AES-256-GCM). The key cannot be taken off the phone, so a copy of the app's settings,
by whatever route, carries no password anyone can read. The settings are also left out of
Android's backups (`allowBackup="false"`). The password is sent only to that server, when
signing in. A server added as a guest has no password to keep.

## Folders from other apps

A folder added from another app is one you chose in Android's own folder picker, and the access
that comes back covers that folder and nothing else. Removing it from the start page hands the
access back.

## Files fetched from a server

To open or share a file that lives on a server, the app copies it into its own cache on the
phone first and hands the copy to the app that will open it. Those copies are deleted the next
time the app starts, once they are a day old.

## Nearby

The Nearby page speaks [LocalSend](https://localsend.org)'s protocol, and does so only while it
is open. Then, and only then, the phone says on the Wi-Fi that it is there, under the name the
page shows, and listens for offers. Closing the page stops all of it. Nothing nearby can send to
the phone without your Accept; an offer you leave for two minutes is declined. What you accept
goes into Downloads, under its own name or a numbered one beside a name already there, and
nothing in a name can put a file anywhere else.

Transfers go over HTTPS. The phone makes itself a certificate the first time and keeps it in the
app's private storage; LocalSend devices know each other by the SHA-256 of their certificates, so
a send goes only to the device whose certificate matches the one it announced. A device on the
same Wi-Fi learns this phone's name and model while the page is open, as it would of any
LocalSend device.

## The one other address in the app

The llama at the foot of the About opens `square.link/u/AGu8oT10` in whatever browser you
have. That is a hand-off: this app does not fetch the page, and it learns nothing about
whether you went there.
