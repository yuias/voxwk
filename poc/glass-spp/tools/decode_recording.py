#!/usr/bin/env python3
"""Decode a raw recording-link capture (rec-*.bin from the PoC app) to a 16 kHz mono WAV.

Packet validation reuses parse_record_packet() from the MemoMind SDK reference codec, loaded from the local SDK
clone by path; Opus decoding uses the system libopus through ctypes.

  decode_recording.py rec-20261114-120000.bin        # layout from the 52 13 reply in the capture
  decode_recording.py --mode legacy capture.bin -o out.wav
  decode_recording.py --self-test input.wav                      # encode -> wrap -> decode round trip
"""
import argparse
import ctypes
import ctypes.util
import importlib.util
import math
import pathlib
import struct
import sys
import wave

ROOT = pathlib.Path(__file__).resolve().parents[3]
REFERENCE = ROOT / 'third_party/plugin-open-platform/GlassSDK/docs/examples/bluetooth_wire.py'
RATE = 16000
FRAME = 320  # 20 ms at 16 kHz


def load_reference():
    if not REFERENCE.is_file():
        sys.exit('SDK clone missing: %s (see README "MemoMind Glass SDK")' % REFERENCE)
    spec = importlib.util.spec_from_file_location('bluetooth_wire', REFERENCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_opus():
    candidates = [ctypes.util.find_library('opus'), '/opt/homebrew/opt/opus/lib/libopus.dylib', 'libopus.so.0']
    for name in filter(None, candidates):
        try:
            lib = ctypes.CDLL(name)
            break
        except OSError:
            continue
    else:
        sys.exit('libopus not found (brew install opus)')
    lib.opus_decoder_create.restype = ctypes.c_void_p
    lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
    lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32, ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
    lib.opus_encoder_create.restype = ctypes.c_void_p
    lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
    lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_char_p, ctypes.c_int32]
    lib.opus_encoder_ctl.argtypes = [ctypes.c_void_p, ctypes.c_int]
    return lib


def split_stream(ref, data, compact=None):
    """Yields (stream_command, opus_packets) per AUDIO_PROTOCOL.md's reader algorithm.

    The record layout follows each `52 13` format response in the capture (legacy until one arrives), unless
    [compact] forces it. Candidate packets are validated with the reference parse_record_packet(); a candidate it
    rejects (a false 52 9x during resync) skips one byte. Returns the skipped byte count via split_stream.skipped.
    """
    mode_compact = False if compact is None else compact
    pos, skipped = 0, 0
    while pos < len(data):
        if data[pos] != 0x52 or pos + 2 > len(data):
            pos, skipped = pos + 1, skipped + 1
            continue
        cmd = data[pos + 1]
        if cmd in (0x11, 0x13) and pos + 6 <= len(data):
            if cmd == 0x13 and compact is None:
                mode_compact = data[pos + 2] == 1
            pos += 6
            continue
        if 0x90 <= cmd <= 0x94 and pos + 12 <= len(data):
            total = 12 + data[pos + 6] * (40 if mode_compact else 49)
            if pos + total <= len(data):
                try:
                    yield ref.parse_record_packet(bytes(data[pos:pos + total]), 1 if mode_compact else 0)
                    pos += total
                    continue
                except ValueError:
                    pass
        pos, skipped = pos + 1, skipped + 1
    split_stream.skipped = skipped


def decode(lib, ref, data, compact=None):
    err = ctypes.c_int()
    dec = lib.opus_decoder_create(RATE, 1, ctypes.byref(err))
    assert err.value == 0, 'opus_decoder_create failed: %d' % err.value
    pcm = bytearray()
    out = (ctypes.c_int16 * (FRAME * 6))()
    packets, other = 0, {}
    for stream, opus_packets in split_stream(ref, data, compact):
        if stream != 0x92:
            other[stream] = other.get(stream, 0) + 1
            continue
        for op in opus_packets:
            n = lib.opus_decode(dec, op, len(op), out, FRAME * 6, 0)
            if n < 0:
                raise RuntimeError('opus_decode error %d at packet %d' % (n, packets))
            pcm += bytes(memoryview(out).cast('B')[:n * 2])
            packets += 1
    lib.opus_decoder_destroy(ctypes.c_void_p(dec))
    return bytes(pcm), packets, other, split_stream.skipped


def write_wav(path, pcm):
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


def self_test(lib, ref, wav_path):
    """Encodes a WAV like the glasses (16 kHz, 20 ms, CBR 16 kbit/s = 40-byte packets), wraps it in compact and legacy
    recording packets with stray bytes in between, decodes both and compares them with the input."""
    with wave.open(str(wav_path), 'rb') as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, RATE), 'need 16 kHz mono 16-bit WAV'
        src = w.readframes(w.getnframes())
    err = ctypes.c_int()
    enc = lib.opus_encoder_create(RATE, 1, 2048, ctypes.byref(err))  # OPUS_APPLICATION_VOIP
    assert err.value == 0
    lib.opus_encoder_ctl(enc, 4002, ctypes.c_int(16000))  # OPUS_SET_BITRATE
    lib.opus_encoder_ctl(enc, 4006, ctypes.c_int(0))      # OPUS_SET_VBR off
    frames = len(src) // (FRAME * 2)
    packets = []
    buf = ctypes.create_string_buffer(400)
    for i in range(frames):
        chunk = (ctypes.c_int16 * FRAME).from_buffer_copy(src[i * FRAME * 2:(i + 1) * FRAME * 2])
        n = lib.opus_encode(enc, chunk, FRAME, buf, 400)
        assert n == 40, 'packet %d is %d bytes, expected 40' % (i, n)
        packets.append(buf.raw[:40])
    results = {}
    for compact in (True, False):
        stream = bytearray(b'\x00\xff')
        stream += bytes.fromhex('52 13 01 31 28 00' if compact else '52 13 00 31 28 00') + bytes.fromhex('52 11 00 01 02 03')
        for i in range(0, len(packets), 8):
            group = packets[i:i + 8]
            stream += bytes([0x52, 0x92, 0, 0, 0x09, 0xFF, len(group), 0, 0, 0, 0, 0xFF])
            for p in group:
                stream += p if compact else b'\0' * 4 + b'\x28' + b'\0' * 4 + p
            if i == 8:
                # A false 52 92 header with a broken terminator, then another consumer's stream.
                stream += bytes([0x52, 0x92, 0, 0, 0x09, 0xFF, 1, 0, 0, 0, 0, 0x00]) + b'\x01' * 40
                stream += bytes([0x52, 0x90, 0, 0, 0x09, 0xFF, 1, 0, 0, 0, 0, 0xFF]) + (packets[0] if compact else b'\0' * 4 + b'\x28' + b'\0' * 4 + packets[0])
        # No mode given: the decoder must pick the layout from the 52 13 response in the stream.
        pcm, n, other, skipped = decode(lib, ref, bytes(stream))
        assert n == len(packets), (n, len(packets))
        assert other == {0x90: 1}, other
        results[compact] = pcm
    assert results[True] == results[False], 'compact and legacy decodes differ'
    # Opus adds look-ahead delay, so compare energy envelopes rather than samples.
    a = struct.unpack('<%dh' % (len(src) // 2), src)[:len(results[True]) // 2]
    b = struct.unpack('<%dh' % (len(results[True]) // 2), results[True])
    def env(x):
        return [math.sqrt(sum(v * v for v in x[i:i + FRAME * 5]) / (FRAME * 5)) for i in range(0, len(x) - FRAME * 5, FRAME * 5)]
    ea, eb = env(a), env(b)
    corr = sum(x * y for x, y in zip(ea, eb)) / math.sqrt(sum(x * x for x in ea) * sum(y * y for y in eb))
    assert corr > 0.9, 'energy envelope correlation %.3f' % corr
    print('PASS: %d packets of 40 bytes, layout from 52 13, compact == legacy decode, false header skipped, '
          'other-stream packet kept separate, envelope corr %.3f'
          % (len(packets), corr))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('input')
    ap.add_argument('--mode', choices=['compact', 'legacy'], help='override the layout read from the 52 13 reply')
    ap.add_argument('-o', '--output')
    ap.add_argument('--self-test', action='store_true')
    args = ap.parse_args()
    lib, ref = load_opus(), load_reference()
    if args.self_test:
        return self_test(lib, ref, args.input)
    path = pathlib.Path(args.input)
    pcm, n, other, skipped = decode(lib, ref, path.read_bytes(), None if args.mode is None else args.mode == 'compact')
    out = pathlib.Path(args.output or path.with_suffix('.wav'))
    write_wav(out, pcm)
    print('%s: %d Opus packets (%.2f s), other streams %s, %d bytes skipped -> %s'
          % (path.name, n, n * 0.02, {hex(k): v for k, v in other.items()} or 'none', skipped, out))


if __name__ == '__main__':
    main()
