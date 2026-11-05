"""Offline replay of the log reviewer (LogReviewer.java) over a saved game log.

usage: python tools/review_replay.py LOG [LOG...] --from "04Nov2026 23:00:00" --to "05Nov2026 00:05:00" [--window 300] [--out findings.txt]
LOGs may be .log or .log.gz (debug.log has every [Claude ...] line). Same prompt (src/main/resources/claude/review_prompt.md),
same schema and model as the live reviewer. The API key is read from the instance config (anthropicApiKey), never printed.
"""
import argparse, datetime, gzip, json, os, re, sys

import anthropic

HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT = open(os.path.join(HERE, '..', 'src', 'main', 'resources', 'claude', 'review_prompt.md'), encoding='utf-8').read()
INST = os.environ.get('INST', r'C:\Users\lipid\curseforge\minecraft\Instances\Claude in Minecraft')
MODEL = 'claude-haiku-5-5'
SKIP = ('[Claude pack]', '[Claude read]', '[Claude test]', '[Claude plan] thinking')
SCHEMA = {
    'type': 'object', 'additionalProperties': False, 'required': ['findings'],
    'properties': {'findings': {'type': 'array', 'items': {
        'type': 'object', 'additionalProperties': False, 'required': ['what', 'expert', 'lines'],
        'properties': {'what': {'type': 'string'}, 'expert': {'type': 'string'}, 'lines': {'type': 'array', 'items': {'type': 'string'}}}}}}}
LINE = re.compile(r'^\[(\d\d\w{3}\d{4}) (\d\d:\d\d:\d\d)\.\d+\] .*?\]: (\[Claude [a-z]+\].*)$')


def key():
    for l in open(os.path.join(INST, 'config', 'claude-common.toml'), encoding='utf-8'):
        m = re.match(r'\s*anthropicApiKey\s*=\s*"([^"]+)"', l)
        if m: return m.group(1)
    sys.exit('no anthropicApiKey in the instance config')


def lines(paths, t0, t1):
    out = []
    for p in paths:
        f = gzip.open(p, 'rt', encoding='utf-8', errors='replace') if p.endswith('.gz') else open(p, encoding='utf-8', errors='replace')
        for raw in f:
            m = LINE.match(raw.rstrip('\n'))
            if not m or m.group(3).startswith(SKIP): continue
            t = datetime.datetime.strptime(m.group(1) + ' ' + m.group(2), '%d%b%Y %H:%M:%S')
            if t0 <= t <= t1: out.append((t, m.group(2) + ' ' + m.group(3)[:260]))
    out.sort(key=lambda x: x[0])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('logs', nargs='+'); ap.add_argument('--from', dest='t0', required=True); ap.add_argument('--to', dest='t1', required=True)
    ap.add_argument('--window', type=int, default=300); ap.add_argument('--out', default='review_findings.txt')
    a = ap.parse_args()
    fmt = '%d%b%Y %H:%M:%S'
    t0, t1 = datetime.datetime.strptime(a.t0, fmt), datetime.datetime.strptime(a.t1, fmt)
    ls = lines(a.logs, t0, t1)
    client = anthropic.Anthropic(api_key=key())
    tin = tout = 0; n = 0; seen = []
    with open(a.out, 'w', encoding='utf-8') as out:
        w = t0
        while w < t1:
            we = w + datetime.timedelta(seconds=a.window)
            chunk = [s for t, s in ls if w <= t < we]
            if len(chunk) >= 20:
                r = client.messages.create(model=MODEL, max_tokens=16000, system=PROMPT,
                    messages=[{'role': 'user', 'content': ('Already reported:\n' + '\n'.join('- ' + x for x in seen[-30:]) + '\n\n' if seen else '') + 'His log from %s to %s:\n%s' % (w.strftime('%H:%M:%S'), we.strftime('%H:%M:%S'), '\n'.join(chunk))}],
                    output_config={'effort': 'medium', 'format': {'type': 'json_schema', 'schema': SCHEMA}})
                tin += r.usage.input_tokens; tout += r.usage.output_tokens
                if r.stop_reason != 'end_turn': print('window %s: stop %s' % (w.strftime('%H:%M'), r.stop_reason)); w = we; continue
                text = ''.join(b.text for b in r.content if b.type == 'text')
                fs = json.loads(text)['findings'] if text else []
                out.write('=== %s-%s (%d lines, %d findings)\n' % (w.strftime('%H:%M'), we.strftime('%H:%M'), len(chunk), len(fs)))
                for f in fs:
                    n += 1; seen.append(f['what'])
                    out.write('- %s\n  expert: %s\n' % (f['what'], f['expert']))
                    for l in f['lines']: out.write('    | %s\n' % l)
                out.flush()
            w = we
        hours = (t1 - t0).total_seconds() / 3600
        cost = tin / 1e6 * 0.10 + tout / 1e6 * 0.50
        summary = '%d findings; %d input / %d output tokens; $%.4f for %.2f h of play = $%.4f an hour' % (n, tin, tout, cost, hours, cost / hours)
        out.write(summary + '\n'); print(summary)


if __name__ == '__main__':
    main()
