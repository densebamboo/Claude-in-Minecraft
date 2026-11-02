# Routes plan on what he's seen (parked, not on main)

Branch `bp/see-known`. The user, via Day to Day: routes see through walls; the no-sensing-through-walls rule.

## What it does

- **`Known`:** what he has seen, as 4x4x4 cubes (a long per 16-cube section). Marked by far-sight rays (each cell a ray steps through or stops in; an all-air section crossed whole) and the cubes within 5 of him each second.
- **`NetherRoute`:** a cell he doesn't know reads as netherrack (planned as rock to dig); the cells within 2 of the walk's end read as they are. A plan that runs through unseen land is kept to its first 24 blocks, then planned again with what he's seen by then. Climbs are 8 a step at most, with pillar points 4 and 8 up. Under the target with a long climb left is dear (climb early). Each plan's nodes are logged (x,y,z, ? unseen, s so far).
- **`FortressEnds`:** an end counts only once seen.
- **`Route`:** a look all round before a Nether route (once per 10 s).

## Where it stands

- Green: 535 (96-111 s, was 49), 536 (33-68 s), 537, 541, 545 (with the corridor marked as seen on his way in: a fortress he heads for is one he's seen).
- **538 (World 15's basalt pocket to the near fortress): 3 of 5.** It passes every time on main. The failing runs break in execution: a 5-block leg the walker gives up on, a lost climb partway up. Routes planned through unseen rock run into real caves and lava pockets, and each kind needs its own handling.

## To finish it

- Prove each 538 failure from the node log (the "?" nodes show what was assumed).
- Probably: re-plan at once (with a look round) when a leg meets something the plan assumed was rock (open air, lava), not only when the leg fails after 20 s.
