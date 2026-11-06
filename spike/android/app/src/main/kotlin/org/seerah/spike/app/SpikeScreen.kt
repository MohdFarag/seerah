package org.seerah.spike.app

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.seerah.spike.core.Event
import org.seerah.spike.core.LabelVariant
import org.seerah.spike.core.toArabicDigits

/** Minimal spike UI. Not a product design: layout and controls exist only to exercise the tech. */
@Composable
fun SpikeScreen(vm: SpikeViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val metrics = remember { Metrics.shared }
    val context = LocalContext.current
    var metricsLine by remember { mutableStateOf("") }

    LaunchedEffect(ui.packDir) {
        while (true) {
            metricsLine = metrics.snapshot(context, ui.packDir).line()
            delay(2_000)
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val pack = ui.pack
            val dir = ui.packDir
            if (pack == null || dir == null) {
                Box(Modifier.fillMaxSize().padding(24.dp)) { Text(ui.error ?: "جارٍ التحميل…") }
                return@Surface
            }
            Column(Modifier.fillMaxSize()) {
                Text(
                    "بيانات اختبار — ليست محتوى السيرة · حزمة ${dir.name}",
                    Modifier.fillMaxWidth().background(Color(0xFFFFE08A)).padding(6.dp), fontSize = 12.sp,
                )
                MapPane(
                    packDir = dir, pack = pack, view = ui.viewTime, markerYear = ui.year, fonts = ui.labelFonts,
                    cameraRequest = ui.cameraRequest, metrics = metrics,
                    onCameraIdle = vm::onCameraIdle, onEventClick = vm::openEvent,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (!ui.mapOnly) Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ui.notice?.let { n -> Row { Text(n, Modifier.weight(1f)); TextButton(vm::dismissNotice) { Text("حسنًا") } } }
                    YearControls(ui.year, pack.years.min, pack.years.max, pack.years.label, ui.openEvent != null, vm::setYear)
                    val events = pack.eventsInYear(ui.year)
                    if (events.isEmpty()) {
                        Text("لا توجد أحداث مضافة لهذه السنة حاليًا")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pack.previousEvent(ui.year)?.let { OutlinedButton({ vm.openEvent(it.id) }) { Text("الحدث السابق") } }
                            pack.nextEvent(ui.year)?.let { OutlinedButton({ vm.openEvent(it.id) }) { Text("الحدث التالي") } }
                        }
                    }
                    events.forEach { e -> OutlinedButton({ vm.openEvent(e.id) }, Modifier.fillMaxWidth()) { Text(e.title) } }
                    Text("فهرس الأحداث (كل السنوات):", fontSize = 12.sp)
                    pack.events.forEach { e ->
                        TextButton({ vm.openEvent(e.id) }) { Text("${e.title} — ${e.date.label(pack.years.label)}", fontSize = 13.sp) }
                    }
                    ui.openEvent?.let { EventCard(it, pack.years.label, ui.user?.followEventId == it.id, ui.undetermined, vm) }
                    ui.user?.followEventId?.takeIf { it != ui.user?.openEventId }?.let {
                        OutlinedButton(vm::resumeFollow) { Text("استئناف المتابعة") }
                    }
                    HorizontalDivider()
                    Text("خط أسماء الخريطة (تجربة):", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabelVariant.entries.forEach { f ->
                            if (f == ui.labelFonts) Button({}) { Text(f.title, fontSize = 12.sp) }
                            else OutlinedButton({ vm.setLabelFonts(f) }) { Text(f.title, fontSize = 12.sp) }
                        }
                    }
                    HorizontalDivider()
                    UpdatePanel(ui, vm)
                    HorizontalDivider()
                    Text(metricsLine, fontSize = 11.sp)
                    TextButton({ Log.i(SpikeViewModel.TAG, "METRICS ${metrics.snapshot(context, dir).line()}") }) {
                        Text("تسجيل القياس في Logcat", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun YearControls(year: Int, min: Int, max: Int, label: String, eventOpen: Boolean, onYear: (Int) -> Unit) {
    var jump by remember { mutableStateOf("") }
    val meaning = if (eventOpen) "الخريطة على زمن الحدث المفتوح" else "الخريطة تمثل حالة نهاية السنة"
    Text("$label ${year.toArabicDigits()} — $meaning", fontWeight = FontWeight.Bold)
    Slider(
        value = year.toFloat(), onValueChange = { onYear(Math.round(it)) },
        valueRange = min.toFloat()..max.toFloat(), steps = (max - min - 1).coerceAtLeast(0),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(jump, { jump = it }, Modifier.weight(1f), label = { Text("انتقال مباشر إلى سنة") }, singleLine = true)
        Button({ jump.trim().toIntOrNull()?.let(onYear) }) { Text("انتقال") }
    }
}

@Composable
private fun EventCard(e: Event, yearLabel: String, isFollowPoint: Boolean, undetermined: List<String>, vm: SpikeViewModel) {
    var showExplanation by remember(e.id) { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.title, fontWeight = FontWeight.Bold)
            Text(e.date.label(yearLabel), fontSize = 13.sp)
            e.place.note()?.let { Text(it, fontSize = 13.sp, color = Color(0xFF8A4B00)) }
            Text("الخريطة تعرض حالة زمن الحدث بدقة تاريخه، لا نهاية السنة", fontSize = 12.sp)
            if (undetermined.isNotEmpty()) {
                Text(
                    "ترتيب هذا التغيّر بالنسبة للحدث غير معروف، فتُعرض الحالة السابقة: ${undetermined.joinToString("، ")}",
                    fontSize = 12.sp, color = Color(0xFF8A4B00),
                )
            }
            e.texts.forEach { t ->
                Text(t.body, fontSize = 20.sp, lineHeight = 34.sp) // original text first
                Text("المصدر: ${t.source.work}، ${t.source.locator}", fontSize = 12.sp)
            }
            e.explanation?.let { ex ->
                TextButton({ showExplanation = !showExplanation }) { Text(if (showExplanation) "إخفاء الشرح" else "الشرح") }
                if (showExplanation) Text(ex, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isFollowPoint) OutlinedButton({ vm.setFollowPoint(e.id) }) { Text("اجعله نقطة المتابعة") }
                else Text("نقطة المتابعة الحالية", fontSize = 12.sp)
                TextButton(vm::closeEvent) { Text("إغلاق") }
            }
        }
    }
}

@Composable
private fun UpdatePanel(ui: UiState, vm: SpikeViewModel) {
    Text("تحديث المحتوى (مستقل عن إصدار التطبيق)", fontWeight = FontWeight.Bold)
    OutlinedTextField(ui.feedUrl, vm::setFeedUrl, Modifier.fillMaxWidth(), label = { Text("رابط خادم الاختبار") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(vm::checkForUpdate, enabled = !ui.downloading) { Text("تحقق من التحديث") }
        if (ui.offer != null) Button(vm::downloadUpdate, enabled = !ui.downloading) { Text("تنزيل") }
    }
    ui.updateStatus?.let { Text(it, fontSize = 13.sp) }
}
