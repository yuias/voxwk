//! translatecore — native pipeline core exposed to the Android app via UniFFI.
//!
//! Hosts llama.cpp GGUF translation (`mt`).

uniffi::setup_scaffolding!();

mod mt;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TranslateError {
    #[error("{0}")]
    Failed(String),
}

/// Receives the partial translation as it is generated, one call per decoded
/// token with the text decoded so far. Implemented on the foreign (Kotlin) side.
#[uniffi::export(callback_interface)]
pub trait TranslationSink: Send {
    fn on_partial(&self, text: String);
}

/// True when this device can run the llama.cpp translator (arm64 needs dot-product
/// instructions). Cheap and safe to call before any model exists.
#[uniffi::export]
pub fn mt_supported() -> bool {
    mt::cpu_supported()
}

/// llama.cpp's CPU feature summary when supported, otherwise a one-line reason.
#[uniffi::export]
pub fn mt_system_info() -> String {
    mt::system_info()
}

/// Loads the GGUF at `model_path` (a file, not a directory) into the resident
/// translation engine. Idempotent for the same path; a different path frees the
/// old model before loading the new one.
#[uniffi::export]
pub fn mt_load(model_path: String) -> Result<(), TranslateError> {
    mt::load(&model_path).map_err(TranslateError::Failed)
}

/// Translates `text` into `tgt_lang` (one of the nine PFML3 codes) with the GGUF at
/// `model_path` (loaded lazily like [`mt_load`]). The source language is not sent:
/// the model detects it. Returns the final translation and pushes the cumulative
/// text so far to `sink` as tokens decode, on the calling thread.
#[uniffi::export]
pub fn mt_translate_streaming(
    model_path: String,
    text: String,
    tgt_lang: String,
    sink: Box<dyn TranslationSink>,
) -> Result<String, TranslateError> {
    mt::translate_streaming(&model_path, &text, &tgt_lang, |s| {
        sink.on_partial(s.to_owned())
    })
    .map_err(TranslateError::Failed)
}

/// Frees the resident translation model. No-op when none is loaded.
#[uniffi::export]
pub fn mt_unload() {
    mt::unload()
}
