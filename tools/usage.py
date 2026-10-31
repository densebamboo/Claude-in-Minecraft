import json, sys, collections
# Effective usage per turn: input + 0.1*cache_read + 2*cache_write + 5*output.
# Each assistant turn is charged to what woke it (watcher alert, user message, scheduled checkpoint,
# Big Picture message) and, for its own share of work, to the tools it called.
path = sys.argv[1]
rows = [json.loads(l) for l in open(path, encoding='utf-8', errors='replace') if l.strip()]
trigger = 'other'
seen = set()
base = collections.Counter(); work = collections.Counter(); turns = collections.Counter()
def tgroup(n):
    if n.startswith('mcp__claude-in-chrome__'): return 'Chrome browser'
    if n.startswith('mcp__terminal__'): return 'terminal tabs (game start/stop)'
    if n.startswith('mcp__ccd_'): return 'app task list / wrap-up'
    if n.startswith('mcp__'): return 'other connectors'
    if n in ('WebSearch', 'WebFetch'): return 'web research'
    if n in ('Read', 'Edit', 'Write', 'Grep', 'Glob'): return 'file reading/editing'
    if n in ('Bash', 'PowerShell'): return 'shell commands (logs, builds, tests)'
    if n in ('SendMessage',): return 'messages to Big Picture'
    if n in ('Monitor', 'TaskStop'): return 'log watcher setup'
    return 'other tools'
for r in rows:
    if r.get('type') == 'user':
        m = r.get('message', {}); c = m.get('content')
        txt = c if isinstance(c, str) else ' '.join(x.get('text', '') for x in c if isinstance(x, dict) and x.get('type') == 'text') if isinstance(c, list) else ''
        if isinstance(c, list) and all(isinstance(x, dict) and x.get('type') == 'tool_result' for x in c): continue
        if 'task-notification' in txt and 'Monitor event' in txt: trigger = 'watcher alerts'
        elif 'task-notification' in txt: trigger = 'background job finished'
        elif 'cross-session-message' in txt: trigger = 'Big Picture messages'
        elif "Check Claude's day-one run" in txt: trigger = 'scheduled checkpoints'
        elif txt.strip(): trigger = 'your messages'
        continue
    if r.get('type') != 'assistant': continue
    m = r.get('message', {}); mid = m.get('id')
    u = m.get('usage') or {}
    if not u or mid in seen: continue
    seen.add(mid)
    eff = u.get('input_tokens', 0) + 0.1 * u.get('cache_read_input_tokens', 0) + 2 * u.get('cache_creation_input_tokens', 0) + 5 * u.get('output_tokens', 0)
    turns[trigger] += 1
    tools = [x.get('name', '') for x in m.get('content', []) if isinstance(x, dict) and x.get('type') == 'tool_use']
    # re-reading the conversation (cache) is the base cost of being woken; output/new tokens are the work
    reread = 0.1 * u.get('cache_read_input_tokens', 0)
    base[trigger] += reread
    rest = eff - reread
    if tools:
        for t in tools: work[tgroup(t)] += rest / len(tools)
    else: work['writing replies'] += rest
tot = sum(base.values()) + sum(work.values())
print('TOTAL effective tokens', int(tot))
print('-- re-reading the conversation, by what woke me --')
for k, v in base.most_common(): print(f'{k:40s} {v/tot*100:5.1f}%  turns={turns[k]}')
print('-- the work itself, by tool --')
for k, v in work.most_common(): print(f'{k:40s} {v/tot*100:5.1f}%')
