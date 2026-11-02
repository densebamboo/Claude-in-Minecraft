# Movement quality from the live log's [Claude where] lines: standing in one place too long, and zigzag/serpentine
# (lots of walking, little ground covered). One line per finding; usage: python pathwatch.py <latest.log>
import sys, time, re, os, collections, math
path = sys.argv[1]
f = open(path, encoding='utf-8', errors='replace'); f.seek(0, 2)
pts = collections.deque()  # (t, x, y, z, doing)
alerted = {}
WHERE = re.compile(r'^\[\S+ (\d+):(\d+):(\d+)\.\d+\].*\[Claude where\] \(live build\) at (?:([a-z_]+) )?(-?\d+), (-?\d+), (-?\d+).*?doing (\w+)')
IDLE_OK = ('Wait', 'Sleep', 'FurnaceTake', 'Fish', 'Dragon')
def say(k, msg, gap=300):
    now = time.time()
    if now - alerted.get(k, 0) > gap: alerted[k] = now; print(msg, flush=True)
while True:
    line = f.readline()
    if not line:
        time.sleep(0.5)
        try:
            if os.path.getsize(path) < f.tell(): f = open(path, encoding='utf-8', errors='replace'); pts.clear()
        except OSError: pass
        continue
    m = WHERE.search(line)
    if not m: continue
    h, mi, s, dim, x, y, z, doing = m.groups(); dim = dim or "overworld"
    t = int(h) * 3600 + int(mi) * 60 + int(s); x, y, z = int(x), int(y), int(z)
    if pts and (pts[-1][5] != dim or t < pts[-1][0]): pts.clear()
    pts.append((t, x, y, z, doing, dim))
    while pts and t - pts[0][0] > 90: pts.popleft()
    if len(pts) < 4 or t - pts[0][0] < 60: continue
    stamp = '%s:%s:%s' % (h, mi, s)
    # standing still: everything within 2 blocks for 60 s+, not a job that waits in place
    if all(abs(p[1] - x) <= 2 and abs(p[2] - y) <= 2 and abs(p[3] - z) <= 2 for p in pts) and not any(p[4] in IDLE_OK for p in pts):
        say('still%d,%d,%d' % (x // 4, y // 4, z // 4), 'STILL %s %ds at %s %d, %d, %d doing %s' % (stamp, t - pts[0][0], dim, x, y, z, ','.join(sorted({p[4] for p in pts}))))
    # zigzag: walked 30+ blocks in the window but ended under 35% of that from where it began
    walked = sum(math.dist(a[1:4], b[1:4]) for a, b in zip(pts, list(pts)[1:]))
    net = math.dist(pts[0][1:4], (x, y, z))
    if walked >= 30 and net < 0.35 * walked:
        say('zig%d,%d' % (x // 16, z // 16), 'ZIGZAG %s walked %d blocks, got %d from start, at %s %d, %d, %d doing %s' % (stamp, walked, net, dim, x, y, z, ','.join(sorted({p[4] for p in pts}))))
