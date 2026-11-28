package app.seerah.content.di

import android.content.Context
import app.seerah.content.AndroidAppVersionCodeProvider
import app.seerah.content.AndroidPackageStorageLogger
import app.seerah.content.AppVersionCodeProvider
import app.seerah.content.AvailableSpaceProvider
import app.seerah.content.DefaultPackageInstaller
import app.seerah.content.DefaultPackageStorage
import app.seerah.content.Ed25519Verifier
import app.seerah.content.IsolatedTinkEd25519Verifier
import app.seerah.content.PackageInstaller
import app.seerah.content.PackageStorage
import app.seerah.content.PackageStorageLogger
import app.seerah.content.PackageStorageMaintenance
import app.seerah.content.RawResourceKeyRingProvider
import app.seerah.content.StatFsAvailableSpaceProvider
import app.seerah.content.TrustedKeyRingSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private const val CONTENT_ROOT_DIR_NAME = "content"

/** Qualifier for `:core:content`'s blocking disk/crypto I/O dispatcher. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ContentBindingsModule {
    @Binds
    abstract fun bindAvailableSpaceProvider(impl: StatFsAvailableSpaceProvider): AvailableSpaceProvider

    @Binds
    abstract fun bindAppVersionCodeProvider(impl: AndroidAppVersionCodeProvider): AppVersionCodeProvider

    @Binds
    abstract fun bindPackageStorageLogger(impl: AndroidPackageStorageLogger): PackageStorageLogger

    @Binds
    abstract fun bindPackageStorage(impl: DefaultPackageStorage): PackageStorage

    @Binds
    abstract fun bindPackageStorageMaintenance(impl: DefaultPackageStorage): PackageStorageMaintenance

    @Binds
    abstract fun bindKeyRingSource(impl: RawResourceKeyRingProvider): TrustedKeyRingSource

    @Binds
    abstract fun bindPackageInstaller(impl: DefaultPackageInstaller): PackageInstaller
}

@Module
@InstallIn(SingletonComponent::class)
internal object ContentProvidesModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideContentRootDir(@ApplicationContext context: Context): File =
        File(context.filesDir, CONTENT_ROOT_DIR_NAME)

    @Provides
    @Singleton
    fun provideEd25519Verifier(keyRingSource: TrustedKeyRingSource): Ed25519Verifier =
        IsolatedTinkEd25519Verifier(keyRingSource::currentKeyRing)
}
