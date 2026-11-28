package app.seerah.userdata

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import app.seerah.domain.FollowAlongEngine
import app.seerah.domain.FollowAlongState
import app.seerah.model.CalendarAxis
import app.seerah.model.FollowAlongBookmark
import app.seerah.model.MapTargetRef
import app.seerah.model.ShotSize
import app.seerah.model.StepCamera
import app.seerah.model.StepPanel
import app.seerah.model.StepTime
import app.seerah.model.Story
import app.seerah.model.StoryChapter
import app.seerah.model.StoryScene
import app.seerah.model.StoryStep
import app.seerah.model.TimelinePoint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreUserDataRepositoryTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val time = TimelinePoint(CalendarAxis.HIJRI, 2, 9)
    private val focus = ExploreFocus.Event("event")
    private val bookmark = FollowAlongBookmark("story", "chapter", "scene", "step", 2)
    private val initial = ExploreSession(time, focus, bookmark)

    @Test
    fun freshStore_emitsEmptyThenCommittedStateAndReopensFromDisk() = runTest {
        val file = sessionFile()
        val opened = open(file)
        val firstRead = CompletableDeferred<Unit>()
        val observed = async {
            val results = mutableListOf<UserDataResult>()
            opened.repository.observeSession().take(2).collect {
                results.add(it)
                firstRead.complete(Unit)
            }
            results
        }
        firstRead.await()
        assertEquals(UserDataResult.Success(initial), opened.repository.updateSession { initial })
        assertEquals(
            listOf<UserDataResult>(UserDataResult.Success(ExploreSession()), UserDataResult.Success(initial)),
            observed.await(),
        )
        opened.job.cancelAndJoin()

        assertEquals(UserDataResult.Success(initial), open(file).repository.observeSession().first())
    }

    @Test
    fun concurrentUpdates_composeAgainstTheLatestCommittedSession() = runTest {
        val repository = open(sessionFile()).repository
        val saveSelection = async { repository.updateSession { it.copy(time = time, focus = focus) } }
        val saveBookmark = async { repository.updateSession { it.copy(bookmark = bookmark) } }
        assertIs<UserDataResult.Success>(saveSelection.await())
        assertIs<UserDataResult.Success>(saveBookmark.await())
        assertEquals(UserDataResult.Success(initial), repository.observeSession().first())
    }

    @Test
    fun invalidOrNewerFile_remainsUnchangedAndNeverCallsTransform() = runTest {
        val invalidUtf8 = "{\"version\":1,\"focus\":{\"kind\":\"event\",\"id\":\"".encodeToByteArray() +
            byteArrayOf(0xC3.toByte(), 0x28) + "\"}}".encodeToByteArray()
        val cases = listOf(
            invalidUtf8 to UserDataResult.UnreadableData,
            "{".encodeToByteArray() to UserDataResult.UnreadableData,
            """{"version":2,"newField":true}""".encodeToByteArray() to UserDataResult.UnsupportedFormat(2),
        )
        val file = sessionFile()
        for ((bytes, expected) in cases) {
            file.writeBytes(bytes)
            val opened = open(file)
            assertEquals(listOf(expected), opened.repository.observeSession().toList())
            var transformed = false
            val result = opened.repository.updateSession {
                transformed = true
                initial
            }
            assertEquals(expected, result)
            assertFalse(transformed)
            assertContentEquals(bytes, file.readBytes())
            opened.job.cancelAndJoin()
        }
    }

    @Test
    fun readIoFailure_endsCollectionAndAllowsAReadRetryOnTheSameStore() = runTest {
        val file = sessionFile()
        seed(file)
        val serializer = FaultInjectingSerializer()
        val repository = open(file, serializer).repository
        serializer.readFailure = true

        assertEquals(listOf(UserDataResult.IoFailure), repository.observeSession().toList())
        serializer.readFailure = false
        assertEquals(UserDataResult.Success(initial), repository.observeSession().first())
    }

    @Test
    fun writeIoFailure_preservesThePriorCommitAfterReopening() = runTest {
        val file = sessionFile()
        seed(file)
        val opened = open(file, FaultInjectingSerializer().apply { writeFailure = true })

        assertEquals(UserDataResult.IoFailure, opened.repository.updateSession { it.copy(focus = null) })
        assertEquals(UserDataResult.Success(initial), opened.repository.observeSession().first())
        opened.job.cancelAndJoin()
        assertEquals(UserDataResult.Success(initial), open(file).repository.observeSession().first())
    }

    @Test
    fun transformIoException_propagatesAndPreservesThePriorCommit() = runTest {
        val file = sessionFile()
        seed(file)
        val opened = open(file)
        val callerFailure = IOException("Caller failure")

        val thrown = assertFailsWith<IOException> {
            opened.repository.updateSession { throw callerFailure }
        }
        assertSame(callerFailure, thrown)
        opened.job.cancelAndJoin()
        assertEquals(UserDataResult.Success(initial), open(file).repository.observeSession().first())
    }

    @Test
    fun cancellationBeforeCommit_preservesThePriorCommitAfterReopening() = runTest {
        val file = sessionFile()
        seed(file)
        val atWrite = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val serializer = object : Serializer<ExploreSession> by ExploreSessionSerializer {
            override suspend fun writeTo(t: ExploreSession, output: OutputStream) {
                atWrite.complete(Unit)
                allowWrite.await()
                ExploreSessionSerializer.writeTo(t, output)
            }
        }
        val opened = open(file, serializer)
        val update = async { opened.repository.updateSession { it.copy(focus = null) } }
        atWrite.await()

        opened.job.cancelAndJoin()
        assertFailsWith<CancellationException> { update.await() }
        allowWrite.complete(Unit)
        assertEquals(UserDataResult.Success(initial), open(file).repository.observeSession().first())
    }

    @Test
    fun persistedBookmark_resolvesAgainstChangedContentWithoutRewritingTheFile() = runTest {
        val file = sessionFile()
        seed(file)
        val original = file.readBytes()
        val result = assertIs<UserDataResult.Success>(open(file).repository.observeSession().first())
        val restoredBookmark = result.session.bookmark

        val reordered = story(scene("scene", 1, "step", "later"), scene("next", 2, "after"))
        val removedStep = story(scene("scene", 1, "remaining"), scene("next", 2, "after"))
        val removedScene = story(scene("next", 2, "after", "end"))
        val cases = listOf(reordered to "step", removedStep to "remaining", removedScene to "end")
        for ((content, expectedStep) in cases) {
            val state = assertIs<FollowAlongState.Suspended>(
                FollowAlongEngine.restoreOnRelaunch(restoredBookmark, listOf(content)),
            )
            assertTrue(state.offerResume)
            assertEquals(expectedStep, state.bookmark.stepId)
        }
        assertEquals(FollowAlongState.Inactive, FollowAlongEngine.restoreOnRelaunch(restoredBookmark, emptyList()))
        assertContentEquals(original, file.readBytes())
    }

    private fun sessionFile() = File(directory.root, "explore-session.json")

    private suspend fun TestScope.seed(file: File) {
        val opened = open(file)
        assertEquals(UserDataResult.Success(initial), opened.repository.updateSession { initial })
        opened.job.cancelAndJoin()
    }

    private fun TestScope.open(
        file: File,
        serializer: Serializer<ExploreSession> = ExploreSessionSerializer,
    ): OpenStore {
        val job = SupervisorJob(backgroundScope.coroutineContext.job)
        val context = backgroundScope.coroutineContext + job
        val store = if (serializer === ExploreSessionSerializer) {
            createExploreSessionStore(file, context)
        } else {
            DataStore.Builder(FileStorage(serializer = serializer, produceFile = { file }), context).build()
        }
        return OpenStore(DataStoreUserDataRepository(store), job)
    }

    private fun story(vararg scenes: StoryScene) = Story(
        id = "story",
        title = "Example story",
        chapters = listOf(StoryChapter(id = "chapter", order = 1, title = "Example chapter", scenes = scenes.toList())),
    )

    private fun scene(id: String, order: Int, vararg stepIds: String) = StoryScene(
        id = id,
        eventId = "event",
        order = order,
        title = "Example scene",
        steps = stepIds.mapIndexed { index, stepId ->
            StoryStep(
                id = stepId,
                order = index + 1,
                camera = StepCamera.Frame(listOf(MapTargetRef.PlaceRef("place")), ShotSize.MEDIUM),
                time = StepTime.At(time),
                panel = StepPanel(titleCard = index == 0),
            )
        },
    )

    private data class OpenStore(val repository: UserDataRepository, val job: Job)

    private class FaultInjectingSerializer : Serializer<ExploreSession> by ExploreSessionSerializer {
        var readFailure = false
        var writeFailure = false

        override suspend fun readFrom(input: InputStream): ExploreSession {
            if (readFailure) throw IOException("Injected read failure")
            return ExploreSessionSerializer.readFrom(input)
        }

        override suspend fun writeTo(t: ExploreSession, output: OutputStream) {
            if (writeFailure) throw IOException("Injected write failure")
            ExploreSessionSerializer.writeTo(t, output)
        }
    }
}
