# Keeps only the direct entrypoints invoked across the androidTest boundary from
# ContentDeviceSmokeTest. All Tink, shaded protobuf-lite, Gson, StrictZipReader,
# PmTilesInspector, PackageVerificationPipeline, and @Serializable DTO classes remain
# unpinned so R8 Full Mode shrinks and obfuscates them using only consumer-rules.pro
# and bundled library rules.
-keep class app.seerah.content.PackageKind { *; }
-keep class app.seerah.content.PackageResult { *; }
-keep class app.seerah.content.PackageResult$* { *; }
-keep class app.seerah.content.PackageFailure { *; }
-keep class app.seerah.content.PackageFailure$* { *; }
-keep class app.seerah.content.InvalidPackageCause { *; }
-keep class app.seerah.content.InstalledPackageInfo { *; }
-keep class app.seerah.content.KeyRingState { *; }
-keep class app.seerah.content.KeyRingState$* { *; }
-keep class app.seerah.content.ActivePointer { *; }
-keep class app.seerah.content.ActivePointerStore { *; }
-keep class app.seerah.content.TrustedKeyRingLoader { *; }
-keep class app.seerah.content.TrustedKeyRingSource { *; }
-keep class app.seerah.content.IsolatedTinkEd25519Verifier { *; }
-keep class app.seerah.content.InstalledPackageState { *; }
-keep class app.seerah.content.InstalledPackageState$* { *; }
-keep class app.seerah.content.ContentState { *; }
-keep class app.seerah.content.ContentState$* { *; }
-keep class app.seerah.content.ContentLookupResult { *; }
-keep class app.seerah.content.ContentLookupResult$* { *; }
-keep class app.seerah.content.ContentRepository { *; }
-keep class app.seerah.content.ContentDatabaseManager { *; }
-keep class app.seerah.content.DefaultContentRepository { *; }
-keep class app.seerah.content.RoomContentDatabaseImporter { *; }
-keep class app.seerah.content.db.AndroidContentDatabaseOpener { *; }
-keep class app.seerah.content.DefaultPackageStorage { *; }
-keep class app.seerah.content.DefaultPackageInstaller { *; }
-keep class app.seerah.content.RawResourceKeyRingProvider { *; }
-keep class app.seerah.content.StatFsAvailableSpaceProvider { *; }
-keep class app.seerah.content.AndroidAppVersionCodeProvider { *; }
-keep class app.seerah.content.AndroidPackageStorageLogger { *; }
-keep class app.seerah.content.R$raw {
    public static int trusted_package_keys;
}
