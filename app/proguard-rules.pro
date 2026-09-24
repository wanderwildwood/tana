# smbj routes its own events through mbassador, which finds the handlers by reflection
# (@Handler methods on smbj's classes). R8 cannot see that, so smbj is kept whole.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }

# Bouncy Castle is called directly by smbj's security provider, which R8 follows, so it is
# shrunk like anything else. Its LDAP certificate-store corner refers to javax.naming,
# which Android does not have and nothing here reaches.
-dontwarn javax.naming.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
