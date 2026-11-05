# kotlinx.serialization (spike core models)
-keepclassmembers class org.seerah.spike.core.** { *** Companion; }
-keepclasseswithmembers class org.seerah.spike.core.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class org.seerah.spike.core.**$$serializer { *; }
