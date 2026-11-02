# Unattended watch: no agent on the line. Every flag loopwatch/pathwatch raise goes to findings.txt
# (deduped: the same thing at new coords is one line, counted). A "bug" in chat, a death or a crash also saves the
# last 600 log lines to incidents/<time>-<kind>.log, for whoever triages later.
# usage: bash tools/selfwatch.sh <instance dir>     (output in <instance>/claude-watch/)
I="${1:-C:/Users/lipid/curseforge/minecraft/Instances/Claude in Minecraft}"
LOG="$I/logs/latest.log"; OUT="$I/claude-watch"; T="$(dirname "$0")"
mkdir -p "$OUT/incidents"; FEED="$OUT/feed.txt"; : >> "$FEED"
[ -f "$OUT/pids.txt" ] && kill $(cat "$OUT/pids.txt") 2>/dev/null; : > "$OUT/pids.txt"
python -u "$T/loopwatch.py" "$LOG" >> "$FEED" & echo $! >> "$OUT/pids.txt"
python -u "$T/pathwatch.py" "$LOG" >> "$FEED" & echo $! >> "$OUT/pids.txt"
echo $$ >> "$OUT/pids.txt"
# incidents: the user's "bug", deaths, crashes -> a log snapshot
( tail -F -n0 "$LOG" 2>/dev/null | grep --line-buffered -aiE '<Fat_Herobrine> .*\bbug\b|died|was slain|was killed|fell |burned|drowned|Exception|Crash' | while read -r l; do
    k=$(echo "$l" | grep -qi bug && echo bug || (echo "$l" | grep -qiE 'Exception|Crash' && echo crash || echo death))
    f="$OUT/incidents/$(date +%m%d-%H%M%S)-$k.log"; tail -n 600 "$LOG" > "$f"; echo "$(date +%T) INCIDENT $k -> $(basename "$f"): ${l:0:200}" >> "$OUT/findings.txt"
  done ) & echo $! >> "$OUT/pids.txt"
# findings: new feed lines, deduped by shape, every 2 min; a silent log is a finding too
pos=$(wc -l < "$FEED")
while true; do
  sleep 120
  now=$(wc -l < "$FEED")
  if [ "$now" -gt "$pos" ]; then
    tail -n +$((pos + 1)) "$FEED" | head -n $((now - pos)) | sed -E 's/^[0-9:]{8} //' | awk -v t="$(date +%T)" '{k=$0; gsub(/-?[0-9]+/,"N",k); k=substr(k,1,110); c[k]++; if (!(k in l)) { l[k]=$0; o[++n]=k } } END { for (i=1;i<=n;i++) print t " x" c[o[i]] " " substr(l[o[i]],1,260) }' >> "$OUT/findings.txt"
    pos=$now
  fi
  n=$(stat -c %Y "$LOG" 2>/dev/null || echo 0); [ $(( $(date +%s) - n )) -gt 180 ] && echo "$(date +%T) GAME DOWN? log silent 3+ min" >> "$OUT/findings.txt"
done
