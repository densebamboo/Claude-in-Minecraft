# The stronghold's other half: from its bricks to a lit end portal

Branch `bp/stronghold-room`. The user, via Day to Day: navigate the portal room, fill the 12 frames, deal with the silverfish spawner. Starts where `find_stronghold` stops: beside the stronghold's bricks.

## What changed

- **`entity/life/StrongholdRoom.java` (new), the skill `light_end_portal`:**
  - **In:** one hole in the wall beside him (feet and head), or through the roof under him when the floor is in reach (1-5 down, no fluid). He steps into the hole.
  - **The map is what he has seen:**
    - Each call he looks round (33x13x33). A cell he could stand in, over a stronghold floor (its bricks, planks, stairs and slabs, the frames), goes on his map only if the line from his eyes to it is clear.
    - He walks to the nearest edge of the map: a cell he can reach with an unseen stronghold cell beside it.
    - Never his own pocket, never caves past the bricks.
  - **Doors:**
    - Wooden doors he walks through, to the cell past them.
    - Iron doors: he presses their own button (the nearest to the door, on his side) and walks through in the same tick. If that try fails, or there's no button on his side (prison cells), he breaks the door. It's one cheap block, as a player would.
    - A shut iron door is a wall on his map. He crosses one to new ground at once. He crosses back to ground he has seen only when nothing unseen is left on his side and something unseen is behind it, so he never shuttles.
  - **By ear (the user's idea):** the edges toward where he heard the portal room come first:
    - its lava pool popping (Perception's lava hearing, 16 blocks);
    - silverfish he hears more than 8 off that weren't by him (out of a block he broke, or called from the walls by a fight). In a stronghold the only other source is the portal room's spawner.
  - **In the portal room** (frames seen, or its spawner; a stronghold has only that one spawner):
    1. He breaks the silverfish spawner first.
    2. Then, frame by frame, the nearest empty one he can reach and see: an eye from his hand onto its top (vanilla `EnderEyeItem`).
    3. From the floor under the platform the frames are hidden, so he goes up level with them.
    4. When all 12 hold an eye: "done: the end portal is lit at X". He doesn't go in.
    - With fewer eyes than empty frames he fails and says how many he needs.
  - **Loop guards, each logged:**
    - a walk that ends where it began twice goes on the failed list;
    - stood in a doorway: out of it, away from where he came;
    - his own cell is never written off;
    - with no way on but ways written off, he retries them (3 times).
    - The final "no way on" logs every rejected edge and every iron door's state.
- **`ActionPath.door()`:** an iron door counts as a door while it's open. Before, the walk through an open iron door was planned as a wall.
- **`WorkGoal.doBreak`:** a spawner is no longer kept as "stations and storage" (it has a block entity, so it was refused like a chest).
- **Hooked up:** `ClaudeLife` runSkill, the `Skills` list, the planner's skill list (`Persona`), and the dragon roadmap's stronghold row (find_stronghold, then light_end_portal).
- **Backlog:** Day to Day's tabled items (Nether travel to the stronghold, boats and sprint-swim, the sea-floor dig, two chat-accuracy bugs, re-lighting dark branches, shield at a creeper).

## Stages

The flat test world generates no stronghold. The stage makes a **real vanilla one** the way `/place structure` does: its maze, doors, buttons, prison cells, spawner and frames as the game builds them. It's set in solid stone, and he starts in a pocket against the start piece's wall, as find_stronghold leaves him. He has 12 eyes, an iron pickaxe, sword and part of an iron set. Monsters other than silverfish are cleared.

- **558** (111 pieces, the portal room near the start): **green 4 of 4**, the last one on the merge with main. Portal lit in 80-82 s, 10-11 eyes put in, lowest health 19.4.
- **559** (another stronghold, 232 pieces): **open.** 1 green of the last 4 (474 s). The rest timed out at 600 s still exploring, with 2,500-3,250 cells seen. The portal room is beside the start, but his nearest-edge walk goes deep the other way first. The lava cue only reaches 16 blocks, and the silverfish cue had just gone in when the merge was called. That's the next piece.
- **557** (find_stronghold): green on the merge (3 throws, 137 s).

## Restart

Needs a restart: a new class (StrongholdRoom) and a new skill.

## What to watch live

- "[Claude stronghold]" lines: the hole in, doors ("pressed the button", "breaking it"), "the portal room by ear", the spawner, each eye.
- He starts at the bricks at -864,34,1678. In a real stronghold the walls have caves and water behind them, and the corridors have infested stone and more silverfish.
- He has 10 eyes. If the room has fewer than 2 frames already filled, he'll stop with "N empty frames and I have 10 eyes".
- A long exploration (559's problem). Watch for the time to the room and for "no way on" lines.

## Second merge (559)

- **Hearing as the guide:** silverfish he hears more than 8 off, other than ones seen by him, now steer the edges he picks, along with heard lava. 559 went from 1 in 4 green to **3 of 3** (31-115 s when he hears the room early; 482-551 s when he hears it late).
- **Finding the bricks himself** (live 2026-11-02: three steps up his own staircase, "no wall beside me"). Bricks he knows of (find_stronghold's, or his seen signs) or sees now: down beside them. With none known or in sight: down the open steps round him, then look again. Holes (cap 3) and tries (cap 10) are counted apart. 558 and 559 now start him 3 steps up his own staircase.
- **The last frame out of reach:** he retries the written-off spots and looks round the room again before giving up.
- **Still open:** the slow 559 runs (8-9 min) explore far before he hears the room. Hearing carries 16 blocks for lava. And the bricks he found aren't saved across a restart (his seen signs aren't saved); walking down his steps covers the live case.
- **Green on the final code:** 557 (131 s), 558 (87 s), 559 3/3.
