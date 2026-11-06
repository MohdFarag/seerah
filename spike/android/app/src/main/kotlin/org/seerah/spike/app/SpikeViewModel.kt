package org.seerah.spike.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.seerah.spike.core.CameraSpec
import org.seerah.spike.core.ContentPack
import org.seerah.spike.core.DownloadResult
import org.seerah.spike.core.Event
import org.seerah.spike.core.InstallResult
import org.seerah.spike.core.LabelVariant
import org.seerah.spike.core.TimeStates
import org.seerah.spike.core.ViewTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.seerah.spike.core.PackStore
import org.seerah.spike.core.ProgressRules
import org.seerah.spike.core.UpdateOffer
import org.seerah.spike.core.Updater
import org.seerah.spike.core.UserState
import java.io.File

data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val pack: ContentPack? = null,
    val packDir: File? = null,
    val user: UserState? = null,
    val notice: String? = null,
    /** Increments when the camera should move (restore or event opened). */
    val cameraRequest: Pair<Int, CameraSpec>? = null,
    val feedUrl: String = "http://127.0.0.1:8765/feed.json",
    val offer: UpdateOffer? = null,
    val updateStatus: String? = null,
    val downloading: Boolean = false,
    val labelFonts: LabelVariant = LabelVariant.NASKH, // experiment: compare variants in the UI (see spike README)
    val historical: JsonObject? = null,
) {
    val year: Int get() = user?.year ?: 1
    val openEvent: Event? get() = user?.openEventId?.let { pack?.event(it) }

    /** Opened event: its own documented time. Otherwise: end of the selected year. */
    val viewTime: ViewTime get() = openEvent?.let { ViewTime.forEvent(it.date) } ?: ViewTime.YearEnd(year)

    /** Names whose change has an unknown order relative to the open event (shown as a notice). */
    val undetermined: List<String> get() {
        val v = viewTime
        return if (v is ViewTime.EventSpan && historical != null) TimeStates.undetermined(historical, v) else emptyList()
    }
}

class SpikeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PackStore(File(app.filesDir, "content"))
    private val updater = Updater(File(app.cacheDir, "downloads"))
    private val db = UserDb.open(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private var cameraSeq = 0

    init {
        viewModelScope.launch {
            val t0 = System.nanoTime()
            withContext(Dispatchers.IO) { ensureSeedPack() }
            loadActive(restoreCamera = true)
            Log.i(TAG, "startup to content ready: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** First run (or unusable store): install the bundled v1 package. Works offline. */
    private fun ensureSeedPack() {
        if (store.activeDir() != null) return
        val tmp = File(getApplication<Application>().cacheDir, "seed-pack.zip")
        getApplication<Application>().assets.open("seed-pack.zip").use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val r = store.install(tmp)
        tmp.delete()
        Log.i(TAG, "seed install: $r")
    }

    private suspend fun loadActive(restoreCamera: Boolean) {
        val loaded = withContext(Dispatchers.IO) {
            val dir = store.activeDir() ?: return@withContext null
            val pack = ContentPack.parse(File(dir, "content.json").readText())
            val saved = db.state().get()?.toCore()
            val hist = Json.parseToJsonElement(File(dir, "historical.geojson").readText()).jsonObject
            Loaded(dir, pack, ProgressRules.reconcile(saved, pack), hist)
        }
        if (loaded == null) {
            _ui.update { it.copy(loading = false, error = "لا توجد حزمة محتوى صالحة") }
            return
        }
        val (dir, pack, rec, hist) = loaded
        _ui.update {
            it.copy(
                loading = false, pack = pack, packDir = dir, user = rec.state, historical = hist,
                notice = rec.notice ?: it.notice,
                cameraRequest = if (restoreCamera) next(CameraSpec(rec.state.year, rec.state.lon, rec.state.lat, rec.state.zoom)) else it.cameraRequest,
            )
        }
        persist()
    }

    private data class Loaded(val dir: File, val pack: ContentPack, val rec: org.seerah.spike.core.Reconciled, val hist: JsonObject)

    fun setLabelFonts(f: LabelVariant) = _ui.update { it.copy(labelFonts = f) }

    private fun next(c: CameraSpec) = (++cameraSeq) to c

    private fun persist() {
        val s = _ui.value.user ?: return
        viewModelScope.launch(Dispatchers.IO) { db.state().put(UserStateRow.of(s)) }
    }

    private fun updateUser(f: (UserState) -> UserState) {
        _ui.update { u -> u.copy(user = u.user?.let(f)) }
        persist()
    }

    fun onCameraIdle(lon: Double, lat: Double, zoom: Double) = updateUser { it.copy(lon = lon, lat = lat, zoom = zoom) }

    /** Changing the time closes the open event (suspends follow-along); the follow point is kept. */
    fun setYear(year: Int) {
        val pack = _ui.value.pack ?: return
        updateUser { it.copy(year = year.coerceIn(pack.years.min, pack.years.max), openEventId = null) }
    }

    /** Opening an event is exploration: sets time and (if known) place, never moves the follow point. */
    fun openEvent(id: String) {
        val e = _ui.value.pack?.event(id) ?: return
        updateUser { it.copy(openEventId = id, year = e.date.year ?: it.year) }
        e.place.cameraTarget()?.let { c -> _ui.update { it.copy(cameraRequest = next(c)) } }
    }

    fun closeEvent() = updateUser { it.copy(openEventId = null) }

    /** Explicit user choice only. */
    fun setFollowPoint(id: String) = updateUser { it.copy(followEventId = id) }

    fun resumeFollow() { _ui.value.user?.followEventId?.let(::openEvent) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun setFeedUrl(url: String) = _ui.update { it.copy(feedUrl = url) }

    fun checkForUpdate() = viewModelScope.launch {
        _ui.update { it.copy(updateStatus = "جارٍ التحقق…", offer = null) }
        val msg = withContext(Dispatchers.IO) {
            runCatching {
                val offer = updater.fetchOffer(_ui.value.feedUrl)
                if (updater.isAvailable(offer, store.activeVersion())) {
                    _ui.update { it.copy(offer = offer) }
                    "يتوفر تحديث ${offer.contentVersion} — الحجم ${"%.2f".format(offer.size / 1_048_576.0)} م.ب"
                } else "المحتوى محدّث"
            }.getOrElse { "تعذر التحقق: ${it.message}" }
        }
        _ui.update { it.copy(updateStatus = msg) }
    }

    /** User-started download; resumes if interrupted; activates only a verified package. */
    fun downloadUpdate() = viewModelScope.launch {
        val offer = _ui.value.offer ?: return@launch
        _ui.update { it.copy(downloading = true) }
        val t0 = System.nanoTime()
        val result = withContext(Dispatchers.IO) {
            updater.download(_ui.value.feedUrl, offer) { done, total ->
                _ui.update { it.copy(updateStatus = "تنزيل ${done * 100 / total}٪") }
            }
        }
        val msg = when (result) {
            is DownloadResult.Interrupted -> "انقطع التنزيل عند ${result.bytes} بايت — اضغط تنزيل للاستكمال"
            is DownloadResult.Failed -> "فشل التنزيل: ${result.reason}"
            is DownloadResult.Done -> when (val r = withContext(Dispatchers.IO) { store.install(result.file, offer.sha256).also { result.file.delete() } }) {
                is InstallResult.Installed -> {
                    loadActive(restoreCamera = false)
                    "تم التحديث إلى ${r.version} (${(System.nanoTime() - t0) / 1_000_000} م.ث) — التقدم محفوظ"
                }
                is InstallResult.Rejected -> "رُفضت الحزمة وبقيت النسخة الحالية: ${r.reason}"
            }
        }
        Log.i(TAG, "update: $msg")
        _ui.update { it.copy(downloading = false, updateStatus = msg, offer = if (result is DownloadResult.Interrupted) offer else null) }
    }

    fun activeVersion(): String? = _ui.value.packDir?.name

    companion object { const val TAG = "SeerahSpike" }
}
