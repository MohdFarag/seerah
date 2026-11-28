package app.seerah.userdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seerah.model.CalendarAxis
import app.seerah.model.FollowAlongBookmark
import app.seerah.model.TimelinePoint
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreSessionStoreTest {
    @Test
    fun savedSession_survivesStoreRecreationOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.filesDir.toPath(), "userdata-test-").toFile()
        val file = File(directory, "explore-session.json")
        val session = ExploreSession(
            time = TimelinePoint(CalendarAxis.PRE_HIJRA, 1, 2),
            focus = ExploreFocus.Place("place"),
            bookmark = FollowAlongBookmark("story", "chapter", "scene", "step", 0),
        )

        try {
            val firstJob = SupervisorJob()
            try {
                val store = createExploreSessionStore(file, firstJob + Dispatchers.IO)
                val repository = DataStoreUserDataRepository(store)
                assertEquals(UserDataResult.Success(ExploreSession()), repository.observeSession().first())
                assertEquals(UserDataResult.Success(session), repository.updateSession { session })
            } finally {
                firstJob.cancelAndJoin()
            }
            assertTrue(file.isFile)

            val reopenedJob = SupervisorJob()
            try {
                val reopened = createExploreSessionStore(file, reopenedJob + Dispatchers.IO)
                val repository = DataStoreUserDataRepository(reopened)
                assertEquals(UserDataResult.Success(session), repository.observeSession().first())
            } finally {
                reopenedJob.cancelAndJoin()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
