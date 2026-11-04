# Water-tunnel integration: emergency air dig

This branch combines the independent `air-before-water-plugs` fix with the previously deployed water-tunnel/recovery union. It depends on water-tunnel because its row-sealing preflight otherwise blocks a permitted emergency roof dig.

## Proven cause and change

With the independent air fix applied, stage 578 passed without water-tunnel, then failed against the combined live source. The trace said `needs 14 water seals before reopening, not enough solid blocks on him`, dropping the air dig with four cobble and eventually zero air. This matches the small remaining scaffold supply in the 01:25 death kit.

While the existing surfacing state or watchdog air route is active, both the row-sealing preflight and post-break water plugging now yield to getting air. Ordinary tunnel mining retains its seal requirement. Existing reach, tool, ownership, protected shell, structure, and lava checks still execute before mining. No counters or direct world changes.

## Validation and limits

Stage 578 is the failing-before integration regression: a floating bot in a three-deep flooded blueprint corridor, stone pick, four cobble, an allowed headroom dig, and air 119. It must open the roof and reach breathable air with a 100+ reserve in ten seconds, retaining at least 18 health. Repeat ordinary surfacing stages 27/483 and normal sealed-tunnel stage 572 before deployment.

After the integration change, stages 578, 577, 27, 483, and 572 all passed sequentially in the isolated test copy. The Desktop WorkGoal comparison differed only in four comments' character encoding; all executable source still matched the known 5b20f9c base. Controls matched the base exactly. No live source was edited.

See `air-before-water-plugs.md` for the other proven interruptions, the vanilla mining-speed measurement, and the exploratory full-refill check's limit. This does not solve a missing pick, a protected roof, an inaccessible floor, or every flooded recovery route. Full live mine validation is still incomplete. Redefine only tested Controls then WorkGoal from this combined source; keep the already deployed ClaudeLife and MineBlueprint intact.
