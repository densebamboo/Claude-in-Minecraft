# The End fight: fight_dragon (bow and melee, the user's pick)

Branch `bp/dragon`. The user approved the End fight as the next major. The kill method is their pick: bow and melee on the perch (beds left for later). Caged crystals: also their pick, the sword hit from just under the cage's rim.

## What changed

- **`entity/life/DragonFight.java` (new):**
  - **The skill `fight_dragon`, in the Overworld:**
    - **The kit:**
      - a bow;
      - 32+ arrows;
      - 128+ blocks for pillars;
      - 8+ meals;
      - a sword;
      - a stone pickaxe, per the stone-picks-for-rock rule. Without one his pillar blocks came down by hand, about 10 s each.
    - What's short gets fetched with `obtain`. The armor worn is said (full diamond is best).
    - **To the portal:** back to the lit End portal (`light_end_portal`'s walk-back), up onto a frame beside it by a cell the path reaches, then in.
  - **The task `Task.Dragon`, ticked every tick in the End by WorkGoal:**
    - **Onto the island:** off the spawn platform to the nearest end stone toward the middle.
    - **Open crystals:** shot with the bow, aimed by real arrow ballistics. The simulation uses vanilla's speed, drag and gravity, takes the flattest arc that gets there, and leads a moving target.
      - Shooting spots are about 28 off.
      - Where a tower's rim hides the crystal, he picks a spot that needs a pillar of up to 16 blocks.
      - Bars block his line of sight (their thin panes let a sight line through but stop arrows).
    - **Caged crystals (the user's pick):**
      - Up a pillar beside the tower until his feet are 2 under the crystal. His whole body is then below the blast's centre and the rim covers his legs.
      - He breaks only the bars on the line from his eyes, eats to 18+, then one sword hit.
      - Full diamond takes the blast with a few hearts.
    - **Off pillars: a drop off the side with the water bucket** (the user: he clutches; pillars stay up).
      - His clutch water is scooped back before anything else.
    - **The dragon:**
      - **In flight:** arrows.
      - **Coming down or perched:** to its head and sword hits on each full charge. Arrows bounce off a perched dragon, and a crit's bonus never reaches its parts, so no jump.
      - He keeps 1.8-2.8 off its head and neck, out of their touch and in his reach.
      - Between perches he waits 8 off the fountain.
    - **Breath:**
      - **On the ground:** he runs to the nearest walkable cell out of the cloud.
      - **Up a pillar with the bucket:** a drop off its side.
      - **Nowhere to step:** 2 up over it (the cloud is a disc half a block thick).
      - **No headroom:** 2 down.
    - **Health:** under 10, he eats.
    - **Done** when the dragon is dead.
- **WorkGoal:**
  - The dispatch for `Task.Dragon`.
  - An End breath guard: the cloud on him during any other task except a Pillar puts the fight first.
  - The fight resumes if his queue gets emptied while it's on.
  - The dragon's hits (body, parts, fireball, breath) are left to the fight, not to self-defence. "Fighting back" had chased it round the island.
  - `bowAimed()`, so the fight's draws aren't cut as stray ones.
- **Leaps.drop:** with a water bucket, outside the Nether, a 6-60 drop onto solid ground counts as no damage. His fall reflex (`mlg`) places the water and his bucket reflex scoops it back.
  - **This applies everywhere, not only in the End:** he'll now take big drops with a bucket.
- **Controls:** a cell holding an End portal block isn't a lava drop. The portal is over its lava pool, and touching it sends him to the End first.
- **ClaudeLife.fillBucket** is public, so the fight can scoop first.
- **Hooked up:** `ClaudeLife` runSkill, `Skills`, `Persona`, the dragon roadmap's End row (fight_dragon, offered once the End portal is on file), and ClaudeChat's description.

## Stages

- **563, the fight, on the real test End:** the island, 10 crystals (2 caged), the dragon fight running. He starts on the spawn platform in full diamond with the kit.
  - The stage first clears earlier runs' leftovers (cobblestone pillars and bucket water within 80 of the middle), puts the crystals and cages back as vanilla sets them, and keeps one dragon.
  - **Green 5 of the last 5** (the last on the merged code): dragon dead in 294-520 s of game time, lowest health 14-18, no deaths.
- **564, into the End from the Overworld:** the 558 stronghold, the portal lit, the kit topped up, then `fight_dragon` takes him through. **Green.**
- **561:** a probe of the test End.
- Also green on the merged code: 557, 558, 562.
- **385-389 (drops) and 450-451 (jumps) fail, on main too:** "down in the gap". Not from this branch; in the backlog.

## Restart

Needs a restart: a new class, a new task type and new fields.

## What to watch live

- "[Claude dragon]" lines: each step says what and why.
- The kit trip before he goes in: 128 blocks, a stone pick and arrows may need fetching.
- His first fight on a real End, where earlier players' blocks and the terrain differ from the test. Watch the cages: "hit from under its rim", and the blast damage.
- His big drops with a bucket outside the End: the clutch now counts in every drop he plans.
