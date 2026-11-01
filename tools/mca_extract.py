"""Minimal Anvil reader: block states of a box out of .mca region files (1.21 chunk format)."""
import struct, zlib, gzip, io, os, sys

def nbt(buf):
    f = io.BytesIO(buf)
    def rd(n): return f.read(n)
    def tag(t):
        if t == 1: return struct.unpack('>b', rd(1))[0]
        if t == 2: return struct.unpack('>h', rd(2))[0]
        if t == 3: return struct.unpack('>i', rd(4))[0]
        if t == 4: return struct.unpack('>q', rd(8))[0]
        if t == 5: return struct.unpack('>f', rd(4))[0]
        if t == 6: return struct.unpack('>d', rd(8))[0]
        if t == 7: n = struct.unpack('>i', rd(4))[0]; return rd(n)
        if t == 8: n = struct.unpack('>H', rd(2))[0]; return rd(n).decode('utf-8', 'replace')
        if t == 9:
            et = rd(1)[0]; n = struct.unpack('>i', rd(4))[0]; return [tag(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                et = rd(1)[0]
                if et == 0: return d
                nl = struct.unpack('>H', rd(2))[0]; name = rd(nl).decode('utf-8', 'replace'); d[name] = tag(et)
        if t == 11: n = struct.unpack('>i', rd(4))[0]; return list(struct.unpack('>%di' % n, rd(4 * n)))
        if t == 12: n = struct.unpack('>i', rd(4))[0]; return list(struct.unpack('>%dq' % n, rd(8 * n)))
        raise ValueError(t)
    t = rd(1)[0]; nl = struct.unpack('>H', rd(2))[0]; rd(nl)
    return tag(t)

def chunk(path, cx, cz):
    with open(path, 'rb') as f:
        i = (cx & 31) + (cz & 31) * 32
        f.seek(i * 4); e = f.read(4); off = (e[0] << 16 | e[1] << 8 | e[2]) * 4096
        if off == 0: return None
        f.seek(off); ln, comp = struct.unpack('>iB', f.read(5)); data = f.read(ln - 1)
        raw = zlib.decompress(data) if comp == 2 else gzip.decompress(data) if comp == 1 else data
        return nbt(raw)

def state_str(p):
    s = p['Name']
    if 'Properties' in p: s += '[' + ','.join('%s=%s' % kv for kv in sorted(p['Properties'].items())) + ']'
    return s

def sections(c):
    out = {}
    for s in c.get('sections', []):
        bs = s.get('block_states')
        if not bs: continue
        pal = [state_str(p) for p in bs['palette']]
        data = bs.get('data')
        if data is None: out[s['Y']] = (pal, None); continue
        bits = max(4, (len(pal) - 1).bit_length()); per = 64 // bits; mask = (1 << bits) - 1
        idx = []
        for v in data:
            v &= (1 << 64) - 1
            for k in range(per):
                if len(idx) < 4096: idx.append((v >> (k * bits)) & mask)
        out[s['Y']] = (pal, idx)
    return out

def dump(regdir, x0, x1, y0, y1, z0, z1, outpath):
    """Box (inclusive) -> gz file: x0 y0 z0 x1 y1 z1 / palette lines / then u16 indices in x, z, y order."""
    pal = {}; order = []; arr = []
    cache = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            cx, cz = x >> 4, z >> 4
            if (cx, cz) not in cache:
                path = os.path.join(regdir, 'r.%d.%d.mca' % (cx >> 5, cz >> 5))
                c = chunk(path, cx, cz) if os.path.exists(path) else None
                cache[(cx, cz)] = sections(c) if c else {}
            secs = cache[(cx, cz)]
            for y in range(y0, y1 + 1):
                sec = secs.get(y >> 4)
                if sec is None: st = 'minecraft:air' if secs else 'minecraft:bedrock'
                else:
                    p, idx = sec
                    st = p[0] if idx is None else p[idx[((y & 15) * 16 + (z & 15)) * 16 + (x & 15)]]
                if st not in pal: pal[st] = len(order); order.append(st)
                arr.append(pal[st])
    with gzip.open(outpath, 'wb') as f:
        f.write(('%d %d %d %d %d %d\n' % (x0, y0, z0, x1, y1, z1)).encode())
        f.write(('%d\n' % len(order)).encode())
        for s in order: f.write((s + '\n').encode())
        f.write(struct.pack('>%dH' % len(arr), *arr))
    return order

if __name__ == '__main__':
    a = sys.argv
    o = dump(a[1], *map(int, a[2:8]), a[8])
    print(len(o), 'states:', ', '.join(o[:40]))
