# MT evaluation

Scores PLaMo 3 Translate GGUF models on FLORES+ devtest with chrF++ on the desktop, using the same prompt and decoding
settings as the app's MT engine:

- PFML3 prompt with one leading `<|plamo:bos|>` and the target `lang` only (no source language)
- `tokenizer.ggml.model` overridden to `plamo2` (`--tokenizer native` uses the GGUF's own `plamo3` tokenizer)
- greedy decoding, context 1024, generation budget `min(3 × source tokens + 32, 512)`
- generation ends at end-of-generation or at `<|plamo:tag|>` / `<|plamo:bos|>`
- CPU only (`-ngl 0`), like the phone

It runs on Homebrew `llama.cpp` through `llama-server`, which is a newer llama.cpp than the one the app links, so
scores can differ slightly from on-device output. Timings are Mac CPU timings and say nothing about phone speed.

## Setup

```sh
brew install llama.cpp
python3 -m venv tools/mt-eval/.venv
tools/mt-eval/.venv/bin/pip install sacrebleu==2.5.1

# Models (pinned revisions; any GGUF path works)
hf download pfnet/plamo-3-translate-0.8b-GGUF plamo-3-translate-0.8b-Q4_0.gguf \
  --revision 1cf96ae02133958a20074bfdfab76517bb737d00 --local-dir ~/.cache/voxwk/models
hf download pfnet/plamo-3-translate-2b-GGUF plamo-3-translate-2b-Q4_0.gguf \
  --revision 4f672106b847200bfdc0f6af6c321cd94eba1aed --local-dir ~/.cache/voxwk/models

# FLORES+ is gated: log in with `hf auth login` and accept the terms on the dataset page first.
hf download openlanguagedata/flores_plus --repo-type dataset --revision 1147e2dc59f37fbb5c22fd8478770de02cd399f9 \
  --local-dir tools/mt-eval/data/flores_plus \
  devtest/eng_Latn.jsonl devtest/jpn_Jpan.jsonl devtest/fra_Latn.jsonl devtest/deu_Latn.jsonl devtest/ita_Latn.jsonl \
  devtest/spa_Latn.jsonl devtest/rus_Cyrl.jsonl devtest/cmn_Hans.jsonl devtest/cmn_Hant.jsonl
```

## Run

```sh
tools/mt-eval/.venv/bin/python -I tools/mt-eval/mt_eval.py \
  --model ~/.cache/voxwk/models/plamo-3-translate-0.8b-Q4_0.gguf \
  --pairs fra_Latn:jpn_Jpan,jpn_Jpan:fra_Latn --limit 200
```

- `--limit N` scores a fixed seeded subset (`0` = all 1012 sentences).
- `--pivot eng_Latn` also scores `src → en → tgt` next to the direct route.
- `--cross-script` scores Chinese outputs against the other script's reference as well.

Hypotheses are cached in `out/hyp/` per model hash, tokenizer and dataset, so reruns only translate what is missing.
Each run writes `out/scores-<time>.json` with the sacrebleu signatures and the llama.cpp build.

chrF++ includes word bigrams; FLORES+ Japanese and Chinese are not word-segmented, so chrF (characters only) is
reported alongside it.
