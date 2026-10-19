# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.safewalk.** { *** Companion; }
-keepclasseswithmembers class app.safewalk.** { kotlinx.serialization.KSerializer serializer(...); }
# MapLibre ships its own consumer rules.
