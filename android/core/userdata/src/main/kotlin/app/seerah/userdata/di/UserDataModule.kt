package app.seerah.userdata.di

import android.content.Context
import app.seerah.userdata.DataStoreUserDataRepository
import app.seerah.userdata.UserDataRepository
import app.seerah.userdata.createExploreSessionStore
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
import kotlinx.coroutines.SupervisorJob

private const val SESSION_FILE = "datastore/explore-session.json"

/** Dispatcher used for local session storage. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class UserDataIoDispatcher

@Module
@InstallIn(SingletonComponent::class)
internal object UserDataModule {
    @Provides
    @UserDataIoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideUserDataRepository(
        @ApplicationContext context: Context,
        @UserDataIoDispatcher dispatcher: CoroutineDispatcher,
    ): UserDataRepository = DataStoreUserDataRepository(
        createExploreSessionStore(File(context.filesDir, SESSION_FILE), SupervisorJob() + dispatcher),
    )
}
