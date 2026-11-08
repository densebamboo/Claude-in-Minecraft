"""Combat tuning report across fights (CombatLog's world/claude/combat.jsonl).

Indirect reinforcement: each fight is scored (outcome, HP lost, time over the ideal), every waste the review named is totted
up, and the HP lost after each decision is charged to the weights that priced it. A pick followed by a heavy loss was priced
too cheap: its weights are listed with the HP charged to them, so the next tuning pass starts from the dearest mistakes.

    python tools/combat_report.py <combat.jsonl> [--last N]
"""
import json, re, sys
from collections import defaultdict

def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__); return
    last = int(args[args.index('--last') + 1]) if '--last' in args else 0
    fights = [json.loads(l) for a in args if not a.startswith('--') and not a.isdigit() for l in open(a, encoding='utf-8') if l.strip()]  # (several files: their fights together)
    if last: fights = fights[-last:]
    if not fights:
        print('no fights'); return
    blame_min = 4.0
    waste = defaultdict(float); counts = defaultdict(int)
    blame = defaultdict(lambda: [0.0, 0, None, set()])  # weight -> [HP charged, decisions, value, picks]
    print(f'{len(fights)} fights\n')
    for f in fights:
        lost = f['hp0'] - f['hp1'] if not f['outcome'].startswith('lost') else f['hp0']
        foes = ', '.join(o['type'] + (' x' if o['killed'] else '') for o in f['foes'])
        print(f"#{f['n']} {f['outcome']}: {f['secs']:.1f} s, {-lost:+.1f} HP, {f['weapon']}/{f['armor']} armor vs {foes}")
        for r in f.get('review', []): print('    ' + r)
        counts[f['outcome'].split(':')[0]] += 1
        waste['charged hit unused (s)'] += f.get('readyIdleS', 0)
        waste['half-charged swings'] += f.get('weakSwings', 0)
        waste['swings that missed'] += max(0, f['swings'] - f['landed'])
        waste['HP lost running'] += f.get('runHp', 0)
        waste['HP lost to blasts'] += f.get('blastHp', 0)
        waste['shots taken with a shield'] += f.get('shotThroughShield', 0)
        for r in f.get('review', []):
            m = re.match(r'([\d.]+) s over the ideal', r)
            if m: waste['seconds over the ideal'] += float(m.group(1))
        for d in f.get('decisions', []):
            if d['lost'] < blame_min: continue
            pick = d['key'].split(':', 1)[1]
            for k, v in d['weights'].items():
                if (pick == 'fight') != k.startswith('fight.'): continue  # the pick's own price, not its rival's
                b = blame[k]; b[0] += d['lost']; b[1] += 1; b[2] = v; b[3].add(d['key'])
    print('\noutcomes: ' + ', '.join(f'{k} {v}' for k, v in counts.items()))
    print('waste across them:')
    for k, v in sorted(waste.items(), key=lambda kv: -kv[1]):
        if v: print(f'    {k}: {v:.1f}')
    print('weights charged with the HP lost after the picks they priced (those picks were too cheap):')
    for k, (hp, n, v, picks) in sorted(blame.items(), key=lambda kv: -kv[1][0])[:15]:
        print(f'    {k} = {v}: {hp:.1f} HP over {n} decisions ({", ".join(sorted(picks))})')
    if not blame: print('    none (no pick lost more than %.0f HP)' % blame_min)
    calibrate(fights)

def calibrate(fights):
    """Measured melee hit rate per foe type in won fights against what the fight price assumes (armed: fight.mob_hits_per_s x
    fight.knockback_hits_factor). Exposure: each of n foes killed in turn is on him about (n+1)/2 of the fight."""
    by = defaultdict(lambda: [0, 0.0, 0])  # type -> hits, foe-seconds, fights
    model = {}
    for f in fights:
        if not f['outcome'].startswith('won'): continue
        n = len(f['foes']); secs = f.get('attackS') or f['secs']
        for o in f['foes']:
            b = by[o['type']]; b[0] += o['hitsOnHim']; b[1] += secs * (n + 1) / 2 / n; b[2] += 1
        for d in f.get('decisions', []):
            w = d.get('weights', {})
            if 'fight.mob_hits_per_s' in w and 'fight.knockback_hits_factor' in w: model['armed'] = w['fight.mob_hits_per_s'] * w['fight.knockback_hits_factor']; model['raw'] = w['fight.mob_hits_per_s']
    print('measured hits on him per foe-second (won fights) vs the price\'s %s:' % (f"{model['armed']:.2f} armed" if model else 'rate (no decision read it)'))
    for t, (h, s, n) in sorted(by.items(), key=lambda kv: -kv[1][2]):
        if n < 3 or s <= 0: continue
        r = h / s
        hint = f"; knockback_hits_factor ~{r / model['raw']:.2f} would fit it" if model and abs(r - model['armed']) > 0.08 and t in ('zombie', 'husk', 'zombie_villager', 'drowned') else ''
        print(f'    {t}: {r:.2f} over {n} foes{hint}')

if __name__ == '__main__':
    main()
