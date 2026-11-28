# Consumer ProGuard rules for :core:content.
# tink-android:1.24.0 bundles META-INF/proguard/protobuf.pro and kotlinx-serialization-core
# bundles META-INF/proguard/kotlinx-serialization-common.pro automatically via R8.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
