package app.rly3h.voxwk.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPreloaderTest {
  private val needed = listOf("asr_stream", "mt_plamo3_0_8b")

  @Test
  fun nothingPresentLoadsNothing() {
    assertEquals(emptyList<String>() to emptyList<String>(), preloadPlan(needed, emptySet(), emptySet()))
  }

  @Test
  fun loadsOnlyThePresentModelsInOrder() {
    assertEquals(listOf("asr_stream") to emptyList<String>(), preloadPlan(needed, setOf("asr_stream"), emptySet()))
    assertEquals(needed to emptyList<String>(), preloadPlan(needed, needed.toSet(), emptySet()))
  }

  @Test
  fun loadedModelsAreNotLoadedAgain() {
    assertEquals(emptyList<String>() to emptyList<String>(), preloadPlan(needed, needed.toSet(), needed.toSet()))
  }

  @Test
  fun switchingFrom08bTo2bSwapsTheMtModel() {
    val now = listOf("asr_stream", "mt_plamo3_2b")
    val present = setOf("asr_stream", "mt_plamo3_0_8b", "mt_plamo3_2b")
    val loaded = setOf("asr_stream", "mt_plamo3_0_8b")
    assertEquals(listOf("mt_plamo3_2b") to listOf("mt_plamo3_0_8b"), preloadPlan(now, present, loaded))
  }

  @Test
  fun switchingTheRecognizerUnloadsTheOldOne() {
    val now = listOf("vad", "asr", "mt_plamo3_0_8b")
    val present = setOf("asr_stream", "vad", "asr", "mt_plamo3_0_8b")
    val loaded = setOf("asr_stream", "mt_plamo3_0_8b")
    assertEquals(listOf("vad", "asr") to listOf("asr_stream"), preloadPlan(now, present, loaded))
  }
}
