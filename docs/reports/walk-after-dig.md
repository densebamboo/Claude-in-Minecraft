# A walk into a cell still solid waits for that cell's dig (Day to Day item 45)

Branch `bp/walk-after-dig`, stage 497.

## Cause

- **Live, 04:43 World 15:** a GoTo into the rock under him was running while the Breaks that open that cell sat behind it in the queue. The walker stalled 32 times in a minute ("next node none").
- **Root cause:** nothing made a walk wait for the dig of its own target cell.

## What changed

- **At a walk's first tick:** if its target cell or the cell above it is solid and a Break of either is queued, those Breaks move in front and the walk goes back behind them. It's logged: `[Claude walk] the walk to X waits for its own cell's dig`.
- **Stage 497:** a walk into a solid cell, its dig queued behind it.
  - With the change, the dig goes first and he walks in.
  - Without it, the walk was dropped at once and he never went in.

## Gate

All 20 groups are green, including 470-497.

## Restart

**Needs a game restart.**

## Not done

- **Item 45's watchdog part** (count a GoTo ending at once 3 times in 30 s) is not done. Instant walk refusals were deliberately left out after they tripped follow walks in the day stage (180-183). A safe version needs the follow rule's walks told apart first.
- **Item 46** (log which drops the pickup sweep skipped, by name) is not done.
