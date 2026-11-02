# Planned walks walked cell by cell (parked, not on main)

Branch `bp/planned-cells`. The root fix for the walker-vs-planner disagreement: the vanilla path between ActionPath's waypoints made its own nodes (a step 2 up; a node over open air, World 15 17:37, into a 43-deep shaft; a walk past lava the plan turned down).

## What it does

- ActionPath.tasks gives every walk cell its own waypoint, marked as planned.
- WorkGoal.walkTo steers straight at a planned waypoint next to him (2.2 across, 3.5 up or down), jumping a step up, and never asks the vanilla path. A cell with nothing under it now is left to the vanilla path.
- Controls: on magma he sneaks (vanilla: magma doesn't burn a sneaking player).

## Why it's parked

538 (World 15's pocket to the fortress): 364 and 374 s (152 s on main), and a death in a 3-deep magma pit at -42 76 79 on 1 run of 3 (in the air on magma: jumping at the step out over and over, so the sneak, which needs the ground, never held). Straight steering has no jump-out of a pit, no corner handling and no smooth turns; the vanilla walker's path does those well. Day to Day's guards on main (6b06410, the lip and lava checks) cover the live cases meanwhile.

## To finish it

- A step-up and pit-climb in the body for straight steering (time the jump; pillar out when the step is 2+).
- Keep the vanilla walker for flat stretches; steer straight only at cells next to a drop, lava or a step.
- Re-prove 538 at main's pace.
