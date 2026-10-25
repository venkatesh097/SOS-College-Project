# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android.getDefaultProguardFile('proguard-android.txt').

# Keep Room entities, DAOs, and Database classes
-keep class com.example.loracommunication.data.** { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Keep protocol models and parser
-keep class com.example.loracommunication.protocol.** { *; }

# Keep UI components referenced by reflection or XML
-keep class com.example.loracommunication.ui.** { *; }
