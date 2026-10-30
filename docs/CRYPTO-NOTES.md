# Cryptography notes, for a reviewer

A scan of the release APK finds the strings `DES/ECB/NoPadding` and `RC4`, and a table of old
TLS cipher-suite names. None of them is this app's own use of cryptography. This page says
where each comes from, and what the app itself uses.

## What the app uses itself

| Use | Primitive | Where |
|---|---|---|
| A server's saved password | AES-256-GCM, key made in and never leaving the Android Keystore | `work/Secrets.kt` |
| Nearby (file transfer between two phones) | TLS (the system's `SSLContext`), a self-signed certificate per phone, trusted only if its SHA-256 fingerprint matches the one the other phone announced | `nearby/Identity.kt`, `nearby/HttpServer.kt` |
| Checking a received file | SHA-256 | `nearby/Nearby.kt`, `ui/BrowserViewModel.kt` |
| Identifying a PDF page mark | SHA-256 | `work/PageMarks.kt` |
| Nextcloud / WebDAV | HTTPS through OkHttp and the system's TLS (plain http only if the reader types an http:// address) | `store/DavStore.kt` |

The app's own source has no DES, 3DES, RC4, MD5, SHA-1 or ECB mode.

## Where `DES/ECB/NoPadding` and `RC4` come from

**What.** `DES/ECB/NoPadding` and `RC4` are used by the **NTLM** password check that Windows and
Samba servers (and many NAS boxes) speak when you sign in to a Samba share. NTLM is old and is
built from these primitives: the "LM" and NTLMv1 responses are made with DES in ECB mode on 8-byte
blocks, and NTLM session security can seal with RC4. They are part of the protocol, not a choice.

**Which library.** `smbj` (com.hierynomus:smbj 0.15.0, Apache-2.0) is the Samba client. It asks a
security provider for the primitives; this app gives it **Bouncy Castle** (`bcprov-jdk18on` 1.79,
MIT) through `BCSecurityProvider` in `store/SmbStore.kt`, because smbj needs an explicit provider
for them (Android's own does not offer them all). The strings are in `smbj`'s NTLM classes and in Bouncy Castle's DES and RC4
engines, which are in the APK because they are in those jars.

**Why they are not a weakness here.**
- Files only talks to a Samba server the reader typed in. smbj speaks SMB 2 and 3 only (it does
  not implement SMB 1) and signs in with NTLMv2 (HMAC-MD5 over the password hash); the DES and RC4
  code is in the jars because the NTLM classes carry the older response forms as well.
- The password never leaves the phone except to that server, and is stored with AES-256-GCM
  (above).
- Nothing here encrypts user data with DES or RC4; no file the reader keeps is protected by them.

**What was considered.** Taking Bouncy Castle out would break Samba sign-in. Shrinking it with
narrower R8 keep rules would drop unused engines from the release build, and may be done later;
it does not change what the app does. Samba support is the reason the library is here.

## The TLS cipher-suite name table

OkHttp (used for WebDAV and Nextcloud) carries the names of the TLS cipher suites it knows,
including old ones such as those ending `_CBC_SHA` or naming `RC4`. They are names in a lookup
table; OkHttp's default connection spec offers only modern suites (TLS 1.2 and 1.3 with AEAD
ciphers) and Android's TLS stack decides the rest.

## Not covered

Android's own Keystore and TLS implementation are the platform's.
