# smbj finds its message handlers by reflection through mbassador, and picks its
# cryptography by name out of Bouncy Castle. Neither is visible to R8, so both are kept
# whole rather than guessed at a class at a time.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
