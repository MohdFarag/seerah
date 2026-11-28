package app.seerah.userdata

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import java.io.File
import kotlin.coroutines.CoroutineContext

internal fun createExploreSessionStore(file: File, context: CoroutineContext): DataStore<ExploreSession> =
    DataStore.Builder(
        storage = FileStorage(serializer = ExploreSessionSerializer, produceFile = { file }),
        context = context,
    ).build()
