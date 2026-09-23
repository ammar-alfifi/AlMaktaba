# R8 rules for the release build.
#
# Most of what this build needs already ships as *consumer* rules inside the libraries themselves,
# and those are applied automatically: `io.legere:pdfiumandroid` keeps its whole JNI surface,
# Room keeps its generated `_Impl` classes and entities, Hilt keeps its generated components,
# Compose and Coil keep what they load reflectively. This file therefore holds only what is about
# *this* app rather than about a dependency.
#
# The release build was checked with `assembleRelease` after these were added; the parts of R8 that
# only a running app can confirm (native PDF rendering, the Room database opening) still need a
# device, exactly as the README says of every device-only path.

# Keep the original file and line numbers so a crash from a release build points at a real source
# line instead of at a renamed class.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The settings store persists enums by `name` and rebuilds them with `enumValues()` / a name match
# (see `SettingsDataStore`). R8 must not remove or rename those members, or a stored preference
# would resolve to nothing and silently reset.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room's generated database is named only through `MyLibraryDatabase`; keep the generated subclass
# so `Room.databaseBuilder(...)` can find it after shrinking. The Room consumer rules cover the
# annotations themselves, so this is deliberately narrow.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Hilt's aggregated dependency graph is referenced from generated code by class name.
-keep,allowobfuscation @interface dagger.hilt.**

# junrar and juniversalchardet pull in optional classes they never touch at runtime on Android;
# R8 warns about them during the release build but they are genuinely absent.
-dontwarn com.github.junrar.**
-dontwarn org.mozilla.universalchardet.**
-dontwarn org.mozilla.intl.chardet.**
