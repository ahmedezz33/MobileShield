# The native engine looks these up by name through JNI
-keep class com.ahmed.shield.engine.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class com.ahmed.shield.ShieldVpnService {
    void nativeExit(java.lang.String);
    void nativeError(int, java.lang.String);
    void logPacket(com.ahmed.shield.engine.Packet);
    void dnsResolved(com.ahmed.shield.engine.ResourceRecord);
    boolean isDomainBlocked(java.lang.String, int);
    int getUidQ(int, int, java.lang.String, int, java.lang.String, int);
    com.ahmed.shield.engine.Allowed isAddressAllowed(com.ahmed.shield.engine.Packet);
    void accountUsage(com.ahmed.shield.engine.Usage);
}
