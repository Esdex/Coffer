# Keep rules for the release build's R8 (audit 1, Info: R8 was off, shipping test helpers and full names).

# TDLib's Java interface: the native library creates and reads these classes by name through JNI.
-keep class org.drinkless.tdlib.** { *; }

# libsodium through lazysodium and JNA: JNA maps the native functions onto these interfaces by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**

# zxcvbn loads its dictionaries and keyboard layouts by name from its own package.
-keep class com.nulabinc.zxcvbn.** { *; }

# The BIP39 word list is read next to the class (Bip39::class.java.getResourceAsStream("english.txt")):
# a renamed package would not find it.
-keeppackagenames me.esdex.coffer.core.crypto.bip39
-keep class me.esdex.coffer.core.crypto.bip39.Bip39 { *; }
