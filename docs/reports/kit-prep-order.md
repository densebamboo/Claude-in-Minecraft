# Keep kit ingredients ahead of crafts

## Cause

After the Nov 4 02:43:52 skeleton death, the bare-handed bot repeatedly tried its iron order. At 02:44:47 its kit plan needed food, a stone sword, and charcoal; `craft oak_planks` ran before the missing logs, failed, and cleared the prerequisite queue. The same `kit first` result then repeated rapidly and hit the existing spinning detector.

`gearFirst` moved every outdoor collection/cooking step to the end at night, without preserving Procure's ingredient dependencies. Stage 583 reproduced charcoal being scheduled before its missing logs. Separately, `fillerStep` treated any in-mine strip-mine order as short background work, even with an empty pack; it expanded into a station/crafting plan while the planner was thinking. Stage 582 reproduced that queued kit chain.

## Change

Keep Procure's existing step order at night. Background mining now requires an already-ready basic kit and a pick, in both the ordinary and clock-wait filler paths. Primary plans still prepare the kit normally. No new retry/give-up counters, food waivers, night-work exceptions, free items, sensing changes, or ownership/hazard changes.

## Tests

- 582 failed before with a kit crafting chain in the background queue. Afterward a bare pack queues none; providing the real kit starts actual mine work within two seconds.
- 583 failed before with charcoal before logs. Afterward the logs precede fuel crafting; with logs already carried there is no outdoor log-collection step. Checks finish in one decision at tick 10.
- Existing 185 (work while thinking) and 569 (counted ore requests) pass separately.
- Build/test only through `tools/test_in_copy.sh` into the isolated test copy, one server at a time.

## Limits

Background mining is deliberately more selective than a primary mining plan. Keeping Procure order can defer independent indoor preparation behind a clock-blocked gathering step; it preserves ingredients rather than trying another night-time reordering policy. Live validation is pending and the food/navigation/combat problems are not all resolved. The `eat cod` failures inspected in this window had no food in the pack, with a successful eat logged between them; the eating handler was unchanged. Full gate was not rerun.
