package app.seerah.userdata

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal class DataStoreUserDataRepository(private val store: DataStore<ExploreSession>) : UserDataRepository {
    override fun observeSession(): Flow<UserDataResult> = store.data
        .map<ExploreSession, UserDataResult> { UserDataResult.Success(it) }
        .catch { emit(it.toStorageFailure()) }

    override suspend fun updateSession(transform: (ExploreSession) -> ExploreSession): UserDataResult = try {
        UserDataResult.Success(
            store.updateData { session ->
                try {
                    transform(session)
                } catch (exception: IOException) {
                    // A caller exception must bypass the storage failure mapping below.
                    throw TransformIoException(exception)
                }
            },
        )
    } catch (exception: TransformIoException) {
        throw exception.original
    } catch (exception: Exception) {
        exception.toStorageFailure()
    }

    private fun Throwable.toStorageFailure(): UserDataResult = when (this) {
        is UnsupportedSessionVersion -> UserDataResult.UnsupportedFormat(version)
        is CorruptionException -> UserDataResult.UnreadableData
        is IOException -> UserDataResult.IoFailure
        else -> throw this
    }

    private class TransformIoException(val original: IOException) : RuntimeException(original)
}
