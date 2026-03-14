# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the default proguard-android-optimize.txt file.

# Keep WalkieTalkie service and widget
-keep class com.walkietalkie.service.** { *; }
-keep class com.walkietalkie.widget.** { *; }
