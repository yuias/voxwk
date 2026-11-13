#!/usr/bin/env python3
"""Translate FLORES+ devtest sentences with PLaMo 3 Translate through llama-server and score them with chrF++.

The prompt, tokenizer override, greedy decoding, context size and generation budget follow the app's MT engine so the
scores describe what the app produces (on a different llama.cpp build; see README.md).

  mt_eval.py --model plamo-3-translate-0.8b-Q4_0.gguf --pairs fra_Latn:jpn_Jpan,jpn_Jpan:fra_Latn --limit 200
  mt_eval.py --model ... --pairs fra_Latn:deu_Latn --pivot eng_Latn     # also scores fr -> en -> de
"""
import argparse
import hashlib
import json
import pathlib
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
DATA = HERE / 'data/flores_plus/devtest'
OUT = HERE / 'out'

# FLORES+ code -> PFML3 `lang` value (model card "Supported Languages").
PFML3 = {
    'eng_Latn': 'en', 'jpn_Jpan': 'ja', 'fra_Latn': 'fr', 'deu_Latn': 'de', 'ita_Latn': 'it',
    'spa_Latn': 'es', 'rus_Cyrl': 'ru', 'cmn_Hans': 'zh-hans', 'cmn_Hant': 'zh-hant',
}

# Engine settings copied from the app's MT engine.
N_CTX = 1024
MAX_NEW_CAP = 512
MIN_GEN_ROOM = 16
TAG, BOS = '<|plamo:tag|>', '<|plamo:bos|>'


def prompt(text, tgt):
    return (f'{BOS}{TAG}dataset<|plamo:msg|>translation{TAG}input<|plamo:msg|>{text}'
            f'{TAG}output<|plamo:key|>lang<|plamo:val|>{PFML3[tgt]}<|plamo:msg|>')


class Server:
    def __init__(self, model, tokenizer, port, threads):
        cmd = ['llama-server', '-m', str(model), '-c', str(N_CTX), '-ngl', '0', '-np', '1', '--no-warmup',
               '--port', str(port)]
        if threads:
            cmd += ['-t', str(threads)]
        if tokenizer == 'plamo2':
            cmd += ['--override-kv', 'tokenizer.ggml.model=str:plamo2']
        self.url = 'http://127.0.0.1:%d' % port
        self.log = open(OUT / 'server.log', 'ab')
        self.proc = subprocess.Popen(cmd, stdout=self.log, stderr=subprocess.STDOUT)
        for _ in range(120):
            if self.proc.poll() is not None:
                sys.exit('llama-server exited; see %s' % (OUT / 'server.log'))
            try:
                if json.load(urllib.request.urlopen(self.url + '/health')).get('status') == 'ok':
                    break
            except (urllib.error.URLError, ConnectionError, json.JSONDecodeError):
                pass
            time.sleep(0.5)
        else:
            sys.exit('llama-server did not become healthy')
        self.build = json.load(urllib.request.urlopen(self.url + '/props')).get('build_info', 'unknown')

    def post(self, path, body):
        req = urllib.request.Request(self.url + path, json.dumps(body).encode(), {'Content-Type': 'application/json'})
        return json.load(urllib.request.urlopen(req))

    def tokens(self, text, special=False):
        return self.post('/tokenize', {'content': text, 'parse_special': special})['tokens']

    def translate(self, text, tgt):
        ids = self.tokens(prompt(text, tgt), special=True)
        if len(ids) > N_CTX - MIN_GEN_ROOM:
            return {'hyp': '', 'error': 'prompt too long'}
        budget = min(3 * len(self.tokens(text)) + 32, MAX_NEW_CAP, N_CTX - len(ids))
        t = time.perf_counter()
        # Generation ends at an end-of-generation token or when the model opens the next element (TAG / BOS).
        r = self.post('/completion', {'prompt': ids, 'n_predict': budget, 'temperature': 0, 'cache_prompt': False,
                                      'stop': [TAG, BOS]})
        return {'hyp': r['content'].strip(), 'ms': round((time.perf_counter() - t) * 1000),
                'tokens': r.get('tokens_predicted'), 'stop': r.get('stop_type'), 'budget': budget}

    def close(self):
        self.proc.terminate()
        self.proc.wait(10)
        self.log.close()


def load(lang):
    path = load.data / f'{lang}.jsonl'
    if not path.is_file():
        sys.exit(f'missing {path}; see README.md for the download command')
    rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    return {str(r['id']): r['text'] for r in rows}


def model_id(model):
    sidecar = OUT / 'model-hashes.json'
    known = json.loads(sidecar.read_text()) if sidecar.is_file() else {}
    key = f'{model.resolve()}:{model.stat().st_size}:{model.stat().st_mtime_ns}'
    if key not in known:
        h = hashlib.sha256()
        with open(model, 'rb') as f:
            for block in iter(lambda: f.read(1 << 22), b''):
                h.update(block)
        known[key] = h.hexdigest()
        sidecar.write_text(json.dumps(known, indent=1))
    return known[key]


def run_pair(server, cache_dir, src, tgt, ids, sources, label=None):
    """Translates sources[id] for ids, reusing cached hypotheses. Returns {id: hyp}."""
    path = cache_dir / f'{label or f"{src}-{tgt}"}.jsonl'
    done = {}
    if path.is_file():
        for line in path.read_text(encoding='utf-8').splitlines():
            row = json.loads(line)
            done[row['id']] = row
    todo = [i for i in ids if i not in done]
    with open(path, 'a', encoding='utf-8') as f:
        for n, i in enumerate(todo, 1):
            row = {'id': i, **server.translate(sources[i], tgt)}
            done[i] = row
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
            f.flush()
            if n % 50 == 0:
                print(f'  {path.stem}: {n}/{len(todo)}', file=sys.stderr)
    return {i: done[i]['hyp'] for i in ids}, [done[i] for i in ids]


def score(hyps, refs):
    from sacrebleu.metrics import CHRF
    chrfpp, chrf = CHRF(word_order=2), CHRF()
    return {'chrF++': round(chrfpp.corpus_score(hyps, [refs]).score, 2), 'chrF': round(chrf.corpus_score(hyps, [refs]).score, 2),
            'sig_chrF++': str(chrfpp.get_signature()), 'sig_chrF': str(chrf.get_signature())}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--model', required=True, type=pathlib.Path)
    ap.add_argument('--pairs', required=True, help='comma-separated src:tgt FLORES+ codes')
    ap.add_argument('--pivot', help='also translate src -> PIVOT -> tgt and score that')
    ap.add_argument('--limit', type=int, default=200, help='sentences per pair (seeded subset; 0 = all 1012)')
    ap.add_argument('--tokenizer', choices=['plamo2', 'native'], default='plamo2',
                    help='plamo2 = the override the app uses; native = the GGUF plamo3 tokenizer')
    ap.add_argument('--data', type=pathlib.Path, default=DATA, help='directory with <code>.jsonl files')
    ap.add_argument('--threads', type=int)
    ap.add_argument('--port', type=int, default=18090)
    ap.add_argument('--cross-script', action='store_true',
                    help='for cmn_Hans/cmn_Hant targets also score against the other script reference')
    args = ap.parse_args()

    load.data = args.data
    pairs = [tuple(p.split(':')) for p in args.pairs.split(',')]
    for src, tgt in pairs:
        for lang in (src, tgt, args.pivot):
            if lang and lang not in PFML3:
                sys.exit(f'unknown language {lang}; known: {", ".join(PFML3)}')
    OUT.mkdir(exist_ok=True)
    mid = model_id(args.model)
    server = Server(args.model, args.tokenizer, args.port, args.threads)
    # Keyed by model hash, tokenizer and dataset directory, so hypotheses never mix across any of them.
    data_key = hashlib.sha256(str(args.data.resolve()).encode()).hexdigest()[:8]
    cache_dir = OUT / 'hyp' / f'{args.model.stem}-{mid[:12]}-{args.tokenizer}-{args.data.name}-{data_key}'
    cache_dir.mkdir(parents=True, exist_ok=True)
    results = []
    try:
        texts = {}
        for src, tgt in pairs:
            for lang in (src, tgt, args.pivot):
                if lang and lang not in texts:
                    texts[lang] = load(lang)
            all_ids = sorted(texts[src], key=int)
            ids = all_ids if args.limit == 0 else sorted(random.Random(0).sample(all_ids, args.limit), key=int)
            refs = [texts[tgt][i] for i in ids]
            routes = [('direct', None)]
            if args.pivot and args.pivot not in (src, tgt):
                routes.append(('via ' + args.pivot, args.pivot))
            for route, pivot in routes:
                if pivot:
                    mid_hyps, _ = run_pair(server, cache_dir, src, pivot, ids, texts[src])
                    hyps, rows = run_pair(server, cache_dir, pivot, tgt, ids, mid_hyps, label=f'{src}-{tgt}.via-{pivot}')
                else:
                    hyps, rows = run_pair(server, cache_dir, src, tgt, ids, texts[src])
                hyp_list = [hyps[i] for i in ids]
                res = {'src': src, 'tgt': tgt, 'route': route, 'n': len(ids), **score(hyp_list, refs),
                       'budget_hits': sum(1 for r in rows if r.get('stop') == 'limit'),
                       'ms_mean': round(sum(r.get('ms', 0) for r in rows) / len(rows))}
                if args.cross_script and tgt in ('cmn_Hans', 'cmn_Hant'):
                    other = 'cmn_Hant' if tgt == 'cmn_Hans' else 'cmn_Hans'
                    texts.setdefault(other, load(other))
                    res['chrF_vs_' + other] = score(hyp_list, [texts[other][i] for i in ids])['chrF']
                results.append(res)
                extra = ''.join(f'  {k} {v}' for k, v in res.items() if k.startswith('chrF_vs_'))
                print(f"{src} -> {tgt} [{route}] n={res['n']} chrF++ {res['chrF++']} chrF {res['chrF']} "
                      f"budget_hits {res['budget_hits']} ms {res['ms_mean']}{extra}")
    finally:
        server.close()
    summary = {'model': args.model.name, 'sha256': mid, 'tokenizer': args.tokenizer, 'llama_cpp': server.build,
               'limit': args.limit, 'results': results}
    stamp = time.strftime('%Y%m%d-%H%M%S')
    (OUT / f'scores-{stamp}.json').write_text(json.dumps(summary, indent=1, ensure_ascii=False))
    if results:
        print('signature:', results[0]['sig_chrF++'], '|', results[0]['sig_chrF'])
    print('llama.cpp', server.build, '| scores ->', OUT / f'scores-{stamp}.json')


if __name__ == '__main__':
    main()
