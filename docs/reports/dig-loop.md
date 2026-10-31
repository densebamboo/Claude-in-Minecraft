# The dig loop at his new mine (Day to Day items 31 and 32, World 15 03:18-03:22)

Branch `bp/dig-loop`, stage 495.

## Cause (proven in the code and reproduced in stage 495)

- **The "in the way" path never asked whether he can break the block in the way.** When a block stands between his eye and a dig, `WorkGoal.doBreak` queues that block's Break, then the dig again. The exit for that path is tagged `"doBreak line 1556"`, a fixed string and not a line number. That's why the tag didn't match the walker's line 1556 in the running build.
- **So copper ore in front of stone looped.** The copper needs a stone pick and he had a wooden one, so its Break exits with "just tried to re-tool". The stone dig then finds the copper still in the way and queues both again: 145 stone lines and 126 copper lines in 3 minutes.
- **The mine plan fed the loop.** `bpReconcile` had no pick filter at all; bpIntegrity's only knew the iron and diamond tiers. So the mine plan kept queuing ore past his pick.

## What changed

- **doBreak:** ore in the way that his tools can't break is no longer queued. The dig is put on record as refused (Attempts BREAK) and the reason is logged ("needs a better pick").
  - Stone in the way without a pick still goes the old way: a pick made on the spot. The sunk-tree stage needs that.
- **bpReconcile** drops ore digs past his pick (`ClaudeLife.pastHisPick`) and logs how many were left for later.
- **The watchdog counts dig and place repeats** (item 32), but only true no-progress repeats. All of these must hold:
  - no block broken and no item got since the last end;
  - not nearer the cell;
  - his feet in the same cell;
  - the task wasn't put back by its own exit with a means in front of it (a step out, a walk to a stand spot, the block in front).
  Those designed retries tripped the gate's sunk-tree and nook-move stages before.

## Gate

All 20 groups are green, including 470-495.

## Restart

**Needs a game restart.**

## Not in this branch

- Item 30 (the table cycle) and items 38/42 (one `allowedToBreak` check for every planner).
- Items 33/34 (a place with no reach check), 35, 36 (the shuttle), 37, 40 and 41.
