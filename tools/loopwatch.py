# Flags mini-loops: the same Claude log line (time stripped) 4+ times within 20 s; one alert per line per 2 min.
import sys, time, re, collections
path = sys.argv[1]
seen = collections.defaultdict(collections.deque); alerted = {}
f = open(path, encoding='utf-8', errors='replace'); f.seek(0, 2)
while True:
    line = f.readline()
    if not line:
        time.sleep(0.3)
        try:
            import os
            if os.path.getsize(path) < f.tell() or os.stat(path).st_ino != os.fstat(f.fileno()).st_ino: f = open(path, encoding='utf-8', errors='replace')  # a new log: shorter, or a different file under the same name by its file id (sizes differ for one file on Windows while it's written: that reopened and replayed old deaths)
        except OSError: pass
        continue
    m = re.search(r'doing (\w+)', line) if '[Claude where] (live' in line else None
    if m: doing = m.group(1); continue
    m = re.search(r'task age (\d+)t', line)
    if m:
        age = int(m.group(1)); d = globals().get('doing', '')
        if age >= 600 and d not in ('Wait', 'FurnaceTake', 'Sleep', 'nothing') and time.time() - alerted.get('age' + d, 0) > 120:
            alerted['age' + d] = time.time(); print('SLOW: %s running %d s' % (d, age // 20), flush=True)
        continue
    # (before the [Claude filter: client chat and death lines carry no "[Claude" tag, so they were dropped unseen)
    if 'CHAT]' in line and re.search(r'CHAT\] Claude (was|drowned|died|fell|burned|starved|blew|hit|suffocated|tried|went|froze|experienced)', line): print('DEATH ' + line.strip()[-200:], flush=True); continue
    if ('MinecraftServer/]: [Not Secure] <' in line or 'ChatComponent/]: [CHAT] <' in line) and '<Claude>' not in line: print('CHAT ' + line.strip()[line.find('<'):][:300], flush=True); continue
    if '[Claude' not in line or '[Claude where]' in line or '[Claude trace]' in line or 'den check' in line: continue
    if '[Claude -> ' in line: print('REPLY ' + line.strip()[line.find('[Claude -> '):][:300], flush=True); continue
    if 'its next round when he passes' in line or '[Claude mine] not patching' in line or 'nothing to pick up round' in line or 'waiting to land first' in line or 'ExactSpot.lessonHold' in line or 'their lesson ("")' in line or ('WorkGoal.walkTo' in line and 'TempLedger.leaving' in line) or ('dropping' in line and 'ClaudeLife.java:52' in line) or '[Claude ore] skipping' in line or '[Claude ore] saw' in line or '[Claude plan] rules:' in line: continue
    if '[Claude owner]' in line: print('OWNER ' + line.strip()[line.find('[Claude owner]'):][:400], flush=True); continue
    if '[Claude conflict]' in line and ('not walking back to it' in line or 'nothing to pick up' in line or 'waiting to land' in line or 'its next round when he passes' in line or 'his mine as he knows it' in line): continue
    if '[Claude conflict]' in line: print('CONFLICT ' + line.strip()[line.find('[Claude conflict]'):][:400], flush=True); continue
    if '[Claude litter]' in line:
        import re as _re, os as _os
        _m = _re.search(r'at (-?\d+, -?\d+, -?\d+) standing', line); _k = _m.group(1) if _m else line
        _f = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), 'litter_seen.txt')
        _seen = set(open(_f).read().split('|')) if _os.path.exists(_f) else set()
        if _k not in _seen: open(_f, 'a').write(_k + '|'); print('LITTER ' + line.strip()[-200:], flush=True)
        continue
    if 'Exception' in line or 'NoClassDef' in line: print('CRASH? ' + line.strip()[-200:], flush=True); continue
    key = re.sub(r'^\[[^\]]*\] ', '', line.strip())
    key = re.sub(r'\d+s ago', 'Ns ago', key)
    now = time.time()
    bare = re.sub(r'-?\d+', 'N', key)
    if not any(w in bare for w in ('blueprint:', 'torch at', 'structure near', 'slow walk')):
        qb = seen['~' + bare]; qb.append(now)
        while qb and now - qb[0] > 30: qb.popleft()
        if len(qb) >= 6 and now - alerted.get('~' + bare, 0) > 120:
            alerted['~' + bare] = now; print('REPEAT x%d in 30s: %s' % (len(qb), key[-220:]), flush=True)
    q = seen[key]; q.append(now)
    while q and now - q[0] > 20: q.popleft()
    if len(q) >= 4 and now - alerted.get(key, 0) > 120:
        alerted[key] = now
        print('LOOP x%d: %s' % (len(q), key[-220:]), flush=True)
