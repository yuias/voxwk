package app.rly3h.voxwk.data

import app.rly3h.voxwk.lang.AsrEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ModelCatalogTest {
  // Gradle runs unit tests with the module directory as the working directory.
  private val text = File("src/main/assets/models.json").readText()
  private val manifest: ModelManifest = Json { ignoreUnknownKeys = true }.decodeFromString(text)

  private fun file(id: String): FileSpec = manifest.models.first { it.id == id }.files.single()

  @Test
  fun plamo08bPinIsExact() {
    val f = file("mt_plamo3_0_8b")
    assertEquals(
      "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/1cf96ae02133958a20074bfdfab76517bb737d00/plamo-3-translate-0.8b-Q4_0.gguf",
      f.url,
    )
    assertEquals(547543232L, f.size)
    assertEquals("33deed2822f799de4d15bb03bea1ac40efa7d663d9239ee54cc7f65f5ab92917", f.sha256)
  }

  @Test
  fun plamo2bPinIsExact() {
    val f = file("mt_plamo3_2b")
    assertEquals(
      "https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/4f672106b847200bfdc0f6af6c321cd94eba1aed/plamo-3-translate-2b-Q4_0.gguf",
      f.url,
    )
    assertEquals(1698577120L, f.size)
    assertEquals("429b7934c88332262b4cca9fd5de36eb02e00f42dcd08ceb511a23e7ee4abafb", f.sha256)
  }

  @Test
  fun everyRequiredModelIdExists() {
    val ids = manifest.models.map { it.id }.toSet()
    val engineSets = listOf(
      setOf(AsrEngine.NEMOTRON),
      setOf(AsrEngine.SENSEVOICE),
      setOf(AsrEngine.NEMOTRON, AsrEngine.SENSEVOICE),
    )
    for (engines in engineSets) for (mt in MtModel.entries) {
      assertEquals(emptyList<String>(), requiredModelIds(engines, mt).filter { it !in ids })
    }
  }

  @Test
  fun requiredModelIdsAreInDownloadOrder() {
    assertEquals(
      listOf("asr_stream", "vad", "asr", "mt_plamo3_2b"),
      requiredModelIds(setOf(AsrEngine.NEMOTRON, AsrEngine.SENSEVOICE), MtModel.PLAMO3_2B),
    )
    assertEquals(listOf("asr_stream", "mt_plamo3_0_8b"), requiredModelIds(setOf(AsrEngine.NEMOTRON), MtModel.PLAMO3_0_8B))
  }

  @Test
  fun manifestHasNoObsoleteDirs() {
    assertFalse(Json.parseToJsonElement(text).jsonObject.containsKey("obsoleteDirs"))
  }
}
