# UniFFI / JNA: bindings do core Rust são acessados por reflexão nas classes
# geradas — manter tudo no pacote core.
-keep class com.filemaster.core.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
