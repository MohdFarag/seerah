package app.seerah.trial

import app.seerah.map.CameraLimits
import app.seerah.map.RampStep
import app.seerah.map.ReliefSource
import app.seerah.map.ZoomRange
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ViewsTest {
    private val ink = { alpha: Int -> (alpha shl 24) or 0x967648 }

    private fun variant(kind: Variant, folderName: String) = TrialVariant(
        folder = File("/trial/$folderName"),
        variant = kind,
        fingerprint8 = "01234567",
        cameraPath = CameraPathSpec(emptyList(), CameraLimits(0.0, 0.0, 1.0, 1.0, 4.0, 14.0)),
    )

    private val painted = variant(Variant.PAINTED, "trial-painted-01234567")

    private val value = variant(Variant.VALUE, "trial-value-01234567")

    @Test
    fun zoom12View_fadesAt11_5To13_5() {
        val spec = TrialView.ZOOM_12.styleSpec()

        assertEquals(ReliefSource.Painted(maxZoom = 12), spec.relief)
        assertEquals(ZoomRange(11.5, 13.5), spec.reliefFade)
    }

    @Test
    fun zoom11View_isPainted11_sameFade() {
        val spec = TrialView.ZOOM_11.styleSpec()

        assertEquals(ReliefSource.Painted(maxZoom = 11), spec.relief)
        assertEquals(ZoomRange(11.5, 13.5), spec.reliefFade)
    }

    @Test
    fun optionA_fadesAt13_5To15_5() {
        val spec = TrialView.OPTION_A.styleSpec()

        assertEquals(ReliefSource.Painted(maxZoom = 12), spec.relief)
        assertEquals(ZoomRange(13.5, 15.5), spec.reliefFade)
    }

    @Test
    fun optionC_isValue12_withPairedRampSteps() {
        val spec = TrialView.OPTION_C.styleSpec()

        val expected =
            listOf(
                RampStep(42.0, ink(0x00)),
                RampStep(43.0, ink(0x33)),
                RampStep(127.0, ink(0x33)),
                RampStep(128.0, ink(0x66)),
                RampStep(212.0, ink(0x66)),
                RampStep(213.0, ink(0x99)),
            )
        assertEquals(ReliefSource.Value(maxZoom = 12, ramp = expected), spec.relief)
        assertEquals(ZoomRange(13.5, 15.5), spec.reliefFade)
    }

    @Test
    fun colours_areTheSpikes() {
        val colours = TrialView.ZOOM_12.styleSpec().colours

        assertEquals(0xFFF1E6C8.toInt(), colours.land)
        assertEquals(0xFF809B94.toInt(), colours.sea)
        assertEquals(0xFF6F8F88.toInt(), colours.coast)
        assertEquals(0xFF967648.toInt(), colours.ink)
    }

    @Test
    fun viewsFor_matchesVariant() {
        assertEquals(listOf(TrialView.ZOOM_12, TrialView.ZOOM_11, TrialView.OPTION_A), viewsFor(Variant.PAINTED))
        assertEquals(listOf(TrialView.OPTION_C), viewsFor(Variant.VALUE))
    }

    @Test
    fun viewIds_areUniqueAndLowercase() {
        val ids = TrialView.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.map { it.lowercase() }, ids)
    }

    @Test
    fun resolveView_acceptsALoadedFolderAndOneOfItsViews() {
        val resolved = resolveView("trial-painted-01234567/option-a", listOf(painted, value))

        assertEquals(TrialSelection(painted, TrialView.OPTION_A), resolved)
    }

    @Test
    fun resolveView_rejectsAnUnknownFolder() {
        assertNull(resolveView("other-painted-00000000/zoom12", listOf(painted, value)))
    }

    @Test
    fun resolveView_rejectsPathTraversal() {
        assertNull(resolveView("../x/zoom12", listOf(painted)))
        assertNull(resolveView("trial-painted-01234567/../zoom12", listOf(painted)))
    }

    @Test
    fun resolveView_rejectsAViewTheVariantDoesNotOffer() {
        assertNull(resolveView("trial-value-01234567/zoom12", listOf(painted, value)))
    }

    @Test
    fun resolveView_rejectsNullAndMalformedValues() {
        assertNull(resolveView(null, listOf(painted)))
        assertNull(resolveView("", listOf(painted)))
        assertNull(resolveView("trial-painted-01234567", listOf(painted)))
        assertNull(resolveView("trial-painted-01234567/zoom12/extra", listOf(painted)))
    }

    @Test
    fun selectionKey_resolvesBackToTheSameSelection() {
        val selection = TrialSelection(value, TrialView.OPTION_C)

        assertEquals("trial-value-01234567/option-c", selection.key)
        assertEquals(selection, resolveView(selection.key, listOf(painted, value)))
    }

    @Test
    fun startSelection_prefersTheIntentOverTheLastUsedView() {
        val start =
            startSelection("trial-painted-01234567/zoom11", "trial-value-01234567/option-c", listOf(painted, value))

        assertEquals(TrialSelection(painted, TrialView.ZOOM_11), start)
    }

    @Test
    fun startSelection_fallsBackToTheLastUsedViewWhenTheIntentIsInvalid() {
        val start = startSelection("../x/zoom12", "trial-value-01234567/option-c", listOf(painted, value))

        assertEquals(TrialSelection(value, TrialView.OPTION_C), start)
    }

    @Test
    fun startSelection_isNullWhenNeitherValueResolves() {
        assertNull(startSelection(null, "gone-folder/zoom12", listOf(painted, value)))
    }
}
