# Music's R8 rules. The libraries (Room, Media3, kotlinx.serialization, Navigation 3) bring their own.

# Enum values are stored by name (DataStore settings, backups, the saved queue): keep the names as
# written, whatever R8 makes of the classes around them.
-keepclassmembers enum sh.hnet.music.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Stack traces in error details stay readable enough to report
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
