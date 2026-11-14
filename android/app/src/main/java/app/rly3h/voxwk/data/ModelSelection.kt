package app.rly3h.voxwk.data

import app.rly3h.voxwk.lang.AsrEngine

enum class MtModel(val modelId: String) {
  PLAMO3_0_8B("mt_plamo3_0_8b"),
  PLAMO3_2B("mt_plamo3_2b"),
}

/** Models a session needs; [engines] = the recognizers its directions use (see asrEngineFor). Download order. */
fun requiredModelIds(engines: Set<AsrEngine>, mt: MtModel): List<String> = buildList {
  if (AsrEngine.NEMOTRON in engines) add("asr_stream")
  if (AsrEngine.SENSEVOICE in engines) {
    add("vad")
    add("asr")
  }
  add(mt.modelId)
}
