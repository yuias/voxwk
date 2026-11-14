//! Opus decoding for the MemoMind Glass microphone: 20 ms packets in, PCM16 LE out.

use std::sync::{Arc, Mutex};

use opus::{Channels, Decoder};

const SAMPLE_RATE: u32 = 16_000;
/// Longest frame Opus can produce (120 ms) at 16 kHz, so any valid packet fits.
const MAX_FRAME_SAMPLES: usize = 1920;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AudioError {
    #[error("{0}")]
    Failed(String),
}

/// Decodes the Glass microphone's Opus packets (16 kHz, mono). The decoder is
/// stateful, so use one instance per recording and feed packets in order.
#[derive(uniffi::Object)]
pub struct OpusDecoder {
    inner: Mutex<Decoder>,
}

#[uniffi::export]
impl OpusDecoder {
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>, AudioError> {
        let decoder = Decoder::new(SAMPLE_RATE, Channels::Mono)
            .map_err(|e| AudioError::Failed(format!("opus decoder: {e}")))?;
        Ok(Arc::new(Self { inner: Mutex::new(decoder) }))
    }

    /// One Opus packet → PCM16 LE mono at 16 kHz (320 samples for the Glass 20 ms frames).
    pub fn decode(&self, packet: Vec<u8>) -> Result<Vec<u8>, AudioError> {
        let mut pcm = [0i16; MAX_FRAME_SAMPLES];
        let n = self
            .inner
            .lock()
            .unwrap()
            .decode(&packet, &mut pcm, false)
            .map_err(|e| AudioError::Failed(format!("opus decode: {e}")))?;
        Ok(pcm[..n].iter().flat_map(|s| s.to_le_bytes()).collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opus::{Application, Bitrate, Encoder};

    const FRAME: usize = 320;

    #[test]
    fn decodes_glass_frames() {
        let pcm: Vec<i16> = (0..SAMPLE_RATE as usize)
            .map(|i| {
                let t = i as f32 / SAMPLE_RATE as f32;
                ((2.0 * std::f32::consts::PI * 440.0 * t).sin() * 12000.0) as i16
            })
            .collect();

        let mut enc = Encoder::new(SAMPLE_RATE, Channels::Mono, Application::Voip).unwrap();
        enc.set_bitrate(Bitrate::Bits(16_000)).unwrap();
        enc.set_vbr(false).unwrap();

        let dec = OpusDecoder::new().unwrap();
        let mut decoded: Vec<i16> = Vec::new();
        for frame in pcm.chunks_exact(FRAME) {
            let packet = enc.encode_vec(frame, 40).unwrap();
            assert_eq!(packet.len(), 40);
            let out = dec.decode(packet).unwrap();
            assert_eq!(out.len(), FRAME * 2, "320 samples per packet");
            decoded.extend(out.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
        }

        // The codec delays the signal by a few ms; take the best correlation over a small lag range.
        let best = (0..80usize)
            .map(|lag| {
                let a = &pcm[..pcm.len() - lag - 2 * FRAME];
                let b = &decoded[lag..lag + a.len()];
                let dot: f64 = a.iter().zip(b).map(|(&x, &y)| x as f64 * y as f64).sum();
                let na: f64 = a.iter().map(|&x| (x as f64).powi(2)).sum::<f64>().sqrt();
                let nb: f64 = b.iter().map(|&x| (x as f64).powi(2)).sum::<f64>().sqrt();
                dot / (na * nb)
            })
            .fold(f64::MIN, f64::max);
        assert!(best > 0.9, "normalised cross-correlation {best}");
    }
}
