# R8 rules for release builds. Only used when built with -Ppushup.minify=true
# (off by default until a minified build has been tested on a real phone).
#
# Nothing app-specific is needed today:
# - Manifest components (activities, AlarmService, receivers incl. AdminReceiver, whose
#   name `dpm remove-active-admin` relies on) are kept by the rules AAPT generates.
# - Enum values()/valueOf() (AlarmActivity.Mode) are kept by proguard-android-optimize.txt.
# - ML Kit, CameraX and Compose ship their own consumer rules (incl. ML Kit's JNI classes).

# Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
