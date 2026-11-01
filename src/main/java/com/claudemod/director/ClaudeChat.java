package com.claudemod.director;

import java.util.stream.Collectors;

import com.claudemod.ClaudeMod;
import com.claudemod.context.PlayerProfile;
import com.claudemod.entity.ClaudeEntity;
import com.claudemod.llm.ClaudeApi;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** What Claude can see when he talks, and posting his messages in chat as a player. */
public final class ClaudeChat {
    private static final int OBSERVE_EVERY_TICKS = 20 * 45;
    private static int nextTick = 0;

    private ClaudeChat() {}

    /** Every 45s: refresh his notes on the player (what they are up to). */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 5) bridge(server);
        if (server.getTickCount() < nextTick) return;
        nextTick = server.getTickCount() + OBSERVE_EVERY_TICKS;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof ClaudeEntity)) PlayerProfile.maybeObserve(player); // he doesn't study himself
        }
    }

    private static boolean bridgeAnnounced;

    /**
     * A dev bridge for testing him unattended: lines dropped into <game dir>/claude-chat.txt reach him exactly as if the player
     * had typed them in chat (and show in chat so they can see it too). The file is emptied once read.
     */
    private static void bridge(MinecraftServer server) {
        java.nio.file.Path f = server.getServerDirectory().resolve("claude-chat.txt");
        if (!bridgeAnnounced) { bridgeAnnounced = true; ClaudeMod.LOGGER.info("[Claude bridge] watching {}", f.toAbsolutePath()); }
        try {
            if (!java.nio.file.Files.exists(f) || java.nio.file.Files.size(f) == 0) return;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
            java.nio.file.Files.write(f, new byte[0]);
            ServerPlayer player = null;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) if (!(sp instanceof ClaudeEntity) && !sp.isSpectator()) { player = sp; break; }
            if (player == null) for (ServerPlayer sp : server.getPlayerList().getPlayers()) if (!(sp instanceof ClaudeEntity)) { player = sp; break; } // a spectating host still speaks (the overnight prompt was dropped while they watched)
            if (player == null) { ClaudeMod.LOGGER.info("[Claude bridge] no player in the world to speak for; dropped {} line(s)", lines.size()); return; }
            for (String line : lines) {
                String msg = line.trim();
                if (msg.isEmpty()) continue;
                if (msg.startsWith("/")) { /* the user (overnight 03:50): a way to hand him things from outside the game; a "/" line runs as a server command, logged */
                    ClaudeMod.LOGGER.info("[Claude bridge] command: {}", msg);
                    for (ServerPlayer sp0 : server.getPlayerList().getPlayers()) if (sp0 instanceof ClaudeEntity ce0) com.claudemod.entity.life.Watchdog.teleported(ce0); // (a /tp from outside is no unplanned move of his: World 15 21:26, logged as one)
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), msg.substring(1));
                    continue;
                }
                ClaudeMod.LOGGER.info("[Claude bridge] <{}> {}", player.getGameProfile().getName(), msg);
                player.sendSystemMessage(Component.literal("<" + player.getGameProfile().getName() + " (bridge)> " + msg));
                com.claudemod.llm.ChatListener.respond(player, msg, true);
            }
        } catch (java.io.IOException e) {
            ClaudeMod.LOGGER.warn("[Claude bridge] {}", e.toString());
        }
    }

    /** Everything Claude would notice right now, as a block for his prompts. */
    /** The truth block for a chat ask: the nether check joins it when the ask is about the nether or being ready. */
    public static String truthFor(ServerPlayer player, String ask) {
        String t = truthFor(player);
        var me = ClaudeEntity.activeFor(player).orElse(null);
        if (me != null && ask != null && NETHER_ASK.matcher(ask.toLowerCase()).find()) t += netherTalk(me) + "\n";
        if (me != null && ask != null && FALL_ASK.matcher(ask.toLowerCase()).find()) t += com.claudemod.entity.nav.Leaps.talk(me, player.level() == me.level() ? player.position() : null) + "\n"; // (the drops round him with their cost, worked out: "7 down is 4 HP")
        if (me != null) t += com.claudemod.entity.life.FortressFight.mobFacts(me, ask); // the dragon-path mobs near him or named: exact facts, never a guess (live ~17:00 "blazes despawn fast if they wander off")
        return t;
    }

    public static String truthFor(ServerPlayer player) {
        var found = ClaudeEntity.activeFor(player);
        ServerLevel level = player.serverLevel();
        StringBuilder sb = new StringBuilder();

        ClaudeEntity me = found.orElse(null);
        boolean sees = me != null && com.claudemod.entity.life.Whereabouts.seesNow(me, player);
        // where they are and what they look like, as far as he can see it (the user: no knowing their coords unless he sees or hears them)
        sb.append("WHERE THEY ARE (what you know of it; never claim more): ").append(me == null ? "you're not in the world" : com.claudemod.entity.life.Whereabouts.describe(me, player)).append(".\n");
        if (me != null && me.lessonOpen != null && !me.lessonOpen.isBlank()) sb.append("LESSON OPEN (\"").append(me.lessonOpen).append("\"): you stay by them between its steps; it ends only when they say so (\"lesson over\", or set the goal \"lesson over: <their next job>\" when they clearly move you on).\n");
        if (me != null) { String dk = ClaudeLife.deathKitLines(me); if (!dk.isEmpty()) sb.append("YOUR LAST DEATHS (newest first; what you had on you is exact, answer from this):\n").append(dk); }
        if (me != null) sb.append("YOUR FEET vs THEIR BLOCK (exact): ").append(com.claudemod.entity.life.ExactSpot.feetTalk(me, player)).append(".\n");
        if (me != null && com.claudemod.entity.life.FortressScout.onFortress(me)) sb.append("YOU ARE STANDING ON THE NETHER FORTRESS (nether bricks under your feet): never say you're short of it or still looking for it.\n");
        if (me != null) sb.append("YOUR BIOME (exact, what F3 shows you; never guess one from the ground): ").append(me.level().getBiome(me.blockPosition()).unwrapKey().map(k -> k.location().getPath().replace('_', ' ')).orElse("unknown")).append(".\n"); /* the user 00:04: "i dont think he understands biomes": "soul sand valley", then "warped forest", both wrong, guessed from the blocks */
        if (me != null) sb.append("LAVA (by ear, and the lava they pointed you to; never claim more): ").append(ClaudeLife.lavaTalk(me)).append(".\n");
        if (me != null) { // his far sight: what he has seen himself, by line of sight (FarSight)
            int reach0 = (int) com.claudemod.entity.life.FarSight.reach(me.serverLevel());
            sb.append("SEEN FAR (structures and landmarks you saw yourself, by line of sight only; your eyes reach ").append(reach0).append(" blocks here").append(me.level().dimension() == net.minecraft.world.level.Level.NETHER ? ", the Nether's fog" : "").append("; never claim more): ").append(com.claudemod.entity.life.FarSight.seenLine(me)).append(".\n");
            sb.append("PORTALS (every portal you used, lit or saw yourself; where each leads, walked through or by the /8 rule; never claim one you don't list): ").append(com.claudemod.entity.life.PortalMap.truthLine(me)).append(".\n");
            sb.append("YOUR FAR VIEW (what your eyes met on your last look): ").append(com.claudemod.entity.life.FarSight.viewLine(me)).append(".\n");
            sb.append("THEY'RE LOOKING AT: ").append(com.claudemod.entity.life.FarSight.lookLine(me, player)).append("\n");
        }
        sb.append("WHAT THEY ARE LOOKING AT: ");
        var hit = ClaudeLife.lookedAt(me, player);
        if (hit != null && com.claudemod.entity.life.FarSight.seeCell(me, level, hit.getBlockPos()) == null) { // their look meets a block he can't see from where he is: not named
            sb.append("something ").append((int) Math.sqrt(hit.getBlockPos().distToCenterSqr(player.getEyePosition()))).append(" blocks from them that you can't see from where you are (never name it)");
        } else if (hit != null) {
            var bs = level.getBlockState(hit.getBlockPos());
            sb.append(BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath()).append(", ")
              .append((int) Math.sqrt(hit.getBlockPos().distToCenterSqr(player.getEyePosition()))).append(" blocks from them");
            // the open space in front of that block (what a player means by "that cave"), not just the stone at the back of it
            String cave = ClaudeLife.caveFrom(level, hit.getBlockPos().relative(hit.getDirection()), found.orElse(null));
            if (cave != null) sb.append("; that's the inside of ").append(cave);
        } else {
            sb.append(sees ? "nothing close (sky or far away)" : "you can't tell (you'd have to be by them, watching)");
        }
        if (sees) sb.append(". Light where they stand: ").append(level.getMaxLocalRawBrightness(player.blockPosition()))
          .append("/15. They hold: ").append(BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath()).append(", armor ").append(player.getArmorValue() == 0 ? "none" : player.getArmorValue() < 10 ? "light" : "heavy");
        sb.append(". Their health, hunger and what's in their pockets you can't see: only what they tell you.\n");

        StringBuilder mobs = new StringBuilder();
        if (sees) for (var m : com.claudemod.entity.life.Perception.known(me, net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(24),
                m -> m.isAlive())) {
            if (mobs.length() > 200) break;
            mobs.append(mobs.length() == 0 ? "" : ", ").append(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath())
                .append(" ").append((int) m.distanceTo(player)).append(" blocks from them");
        }
        String caveNear = sees ? ClaudeLife.caveNear(level, player.blockPosition(), me) : null;
        sb.append("CAVE NEAR THEM: ").append(caveNear == null ? (sees ? "none in sight" : "can't see round them") : caveNear).append(".\n");
        sb.append("MOBS NEAR THEM: ").append(mobs.length() == 0 ? (sees ? "none you can see" : "can't see round them") : mobs).append(".\n");

        StringBuilder drops = new StringBuilder();
        if (sees) for (var it : com.claudemod.entity.life.Perception.known(me, net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(12))) {
            if (drops.length() > 200) break;
            drops.append(drops.length() == 0 ? "" : ", ").append(it.getItem().getCount()).append(" ")
                 .append(BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath())
                 .append(" at ").append(it.blockPosition().toShortString())
                 .append(" (").append((int) it.distanceTo(player)).append(" blocks from them)");
        }
        sb.append("ITEMS ON THE GROUND NEAR THEM: ").append(drops.length() == 0 ? "none" : drops).append(".\n");

        if (found.isEmpty()) {
            sb.append("WHERE YOUR BODY IS: not in the world right now.\n");
            return sb.toString();
        }
        ClaudeEntity c = found.get();
        if (!c.prefs.isEmpty()) sb.append("THEIR STANDING INSTRUCTIONS (keep following these): ").append(c.prefs).append('\n');
        if (!c.playerGoal.isBlank()) sb.append("WHAT THEY WANT THE TWO OF YOU TO DO: ").append(c.playerGoal).append('\n');
        String queued = c.skillQueue.isEmpty() ? "" : "; still to do: " + c.skillQueue.stream()
                .map(s -> s.skill() + (s.target().isBlank() ? "" : " " + s.target())).collect(Collectors.joining(", "));
        sb.append("WHAT YOUR BODY IS DOING RIGHT NOW: ").append(c.staying ? "staying put where they told you"
                : c.currentTask != null ? describe(c, c.currentTask) : c.tasks.isEmpty() ? "nothing right now" : "about to " + describe(c, c.tasks.peek()))
          .append(queued)
          .append(c.goal.isBlank() ? "" : "; goal: " + c.goal)
          .append(c.lastSkillLine.isBlank() ? "" : "; last step: " + c.lastSkillLine).append(".\n");
        sb.append("YOUR BODY'S SITUATION (trust this over anything else): ").append(c.isInWater() ? (c.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) ? "UNDERWATER" : "IN WATER, swimming/treading water") : "on land")
          .append(level.canSeeSky(c.blockPosition()) ? ", under open sky" : ", UNDERGROUND (no sky overhead)")
          .append(", at ").append(c.blockPosition().toShortString()).append(c.onGround() ? "" : ", not on solid ground").append(".\n"); // (he told them "nah not in water, just chopping acacia" from a flooded cave)
        { String chests = com.claudemod.entity.life.ChestLedger.describe(c, level, 8); // what's in his own chests as of his last look in each, from anywhere (the user: "does he know he have 7 diamonds", 190 off: he didn't)
            sb.append("YOUR CHESTS (what was in each when you last looked inside: you know that exactly; what anyone did since you didn't see; raw_copper/raw_iron count as that metal): ").append(chests.isEmpty() ? "none with anything in them that you've seen" : chests).append(".\n"); }
        { String er = com.claudemod.director.Errand.describe(c); if (er != null) sb.append("YOUR ERRAND FOR THEM (what you're handing over, keeping and making for them, exactly; say it like this if they ask): ").append(er).append('\n'); }
        if (!c.lastWhy.isBlank()) sb.append("WHY (your own reasoning for the current plan): ").append(c.lastWhy).append('\n');
        if (!c.stuckNote.isBlank()) sb.append("YOU ARE STUCK: ").append(c.stuckNote).append(". Be honest about it if they ask (don't say you're fine); say where you're stuck and ask for a hand if you need it.").append(System.lineSeparator());
        sb.append("If they ask what you're doing: say what you're doing right now and why, in a sentence or two, like a friend would (\"chopping this acacia, need sticks for torches before we go down\"), not just the task name.\n");
        net.minecraft.core.BlockPos them = com.claudemod.entity.life.Whereabouts.spot(c, player); double dist = them == null ? -1 : Math.sqrt(them.distToCenterSqr(c.position()));
        // ponytail: he's a player, F3 is free; give him his coords so he can answer "where are you"
        sb.append("WHERE YOUR BODY IS: your coords (F3) ").append(c.blockPosition().toShortString())
          .append(them == null ? ", and where they are you don't know" : ", " + (int) dist + " blocks from " + (sees ? "them" : "where you last knew them"))
          .append(them == null ? "" : dist < 8 && sees ? ", right next to them" : dist < 32 ? ", close by" : ", off on your own")
          .append(". Never say you are on your way if you are already there.\n");
        sb.append("YOUR MINE: ").append(c.mineFace == null ? "none in progress"
                : "you have one; its face is at " + c.mineFace.toShortString() + " (y=" + c.mineFace.getY() + "), "
                        + (int) Math.sqrt(c.mineFace.distSqr(c.blockPosition())) + " blocks from you" + (c.mineIsTunnel ? ", a strip mine" : ", a staircase going down"))
          .append(". Your tools wear out: say so if your pickaxe broke.\n");
        sb.append("YOUR INVENTORY: ").append(c.inventory.summary()).append(" (if they gave you something, it is here only if you picked it up).\n");
        sb.append("YOUR BODY: health ").append((int) Math.ceil(c.getHealth())).append("/20 (= ").append(Math.ceil(c.getHealth()) / 2.0).append(" hearts; say hearts only with this number), food ").append(c.getFoodData().getFoodLevel()).append("/20, armor ").append(c.getArmorValue())
          .append("; it is ").append(level.isDay() ? "day" : "night").append(" (day ").append(level.getDayTime() / 24000 + 1).append(").\n");
        sb.append(com.claudemod.entity.life.MindLog.recent()); // the user: he should have the same logging the debug has, so he has context
        sb.append(com.claudemod.entity.life.WatchBuild.describe(c)); // his learned builds by name and size; what he's watching or building
        sb.append(com.claudemod.entity.life.OwnEvents.describe(c));
        sb.append(Primitives.describe(c)); // the ore he saw with its spot, and his own patch sealing it (what dig_at / place_at need) // what he did himself, as facts (03:32 he left a diamond "no way to it" and told them he'd found no more)
        sb.append(com.claudemod.entity.life.Clocks.describe(c, level)); // the timers, exact (he told them drops "vanish after 3 min": it's 5)
        sb.append("YOUR PLACES (yours; you know where they are): ").append(places(c, level)).append(".\n");
        sb.append("YOUR TOTALS (on you + in your chests; use these numbers for \"how many X do you have\"): ").append(totals(c, level)).append(".\n");
        return sb.toString();
    }

    private static final java.util.List<net.minecraft.world.item.Item> TOTALLED = java.util.List.of(
            net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.EMERALD, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.RAW_IRON,
            net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.RAW_GOLD, net.minecraft.world.item.Items.COPPER_INGOT, net.minecraft.world.item.Items.RAW_COPPER,
            net.minecraft.world.item.Items.COAL, net.minecraft.world.item.Items.CHARCOAL, net.minecraft.world.item.Items.REDSTONE, net.minecraft.world.item.Items.LAPIS_LAZULI,
            net.minecraft.world.item.Items.OBSIDIAN, net.minecraft.world.item.Items.ENDER_PEARL, net.minecraft.world.item.Items.BLAZE_ROD, net.minecraft.world.item.Items.ENDER_EYE,
            net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.TORCH, net.minecraft.world.item.Items.ARROW);

    /** What matters, counted on him and across every chest of his: "how many diamonds do you have" in one number each. */
    static String totals(ClaudeEntity c, ServerLevel level) {
        java.util.Map<net.minecraft.world.item.Item, Integer> n = new java.util.LinkedHashMap<>();
        for (var it : TOTALLED) n.put(it, c.inventory.count(s -> s.is(it)));
        java.util.Set<net.minecraft.core.BlockPos> seen = new java.util.HashSet<>(); int logs = c.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS)), food = 0;
        for (var e : com.claudemod.entity.life.ChestLedger.totals(c, level, 1 << 20).entrySet()) { // every chest of his, as of his last look in it
            var it0 = e.getKey(); int k0 = e.getValue();
            n.computeIfPresent(it0, (k, v) -> v + k0);
            if (new net.minecraft.world.item.ItemStack(it0).is(net.minecraft.tags.ItemTags.LOGS)) logs += k0;
            if (it0.components().has(net.minecraft.core.component.DataComponents.FOOD)) food += k0;
        }
        StringBuilder sb = new StringBuilder();
        for (var e : n.entrySet()) if (e.getValue() > 0) sb.append(sb.length() == 0 ? "" : ", ").append(e.getValue()).append(' ').append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).getPath());
        if (logs > 0) sb.append(sb.length() == 0 ? "" : ", ").append(logs).append(" logs");
        int foodOn = c.inventory.count(s -> s.has(net.minecraft.core.component.DataComponents.FOOD)); if (foodOn + food > 0) sb.append(sb.length() == 0 ? "" : ", ").append(foodOn + food).append(" food items");
        return sb.length() == 0 ? "nothing of note" : sb.toString();
    }

    /** His own places as he knows them: house, farm, base nook, bed, mine entrance (coords, how far, how far along). */
    static String places(ClaudeEntity c, ServerLevel level) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.function.Function<net.minecraft.core.BlockPos, String> at = p -> p.toShortString() + " (" + (int) Math.sqrt(p.distSqr(c.blockPosition())) + " blocks from you)";
        if (c.house != null) out.add("house at " + at.apply(c.house.centre()) + ", door facing " + c.house.facing.getName() + ", " + (level.isLoaded(c.house.centre()) ? (c.house.complete(level) ? "finished" : "being built: " + c.house.stage(level)) : "out of sight"));
        if (c.farm != null) out.add("farm at " + at.apply(c.farm.centre()) + ", " + c.farm.plots + " plot(s), " + (level.isLoaded(c.farm.centre()) ? (c.farm.complete(level) ? "finished" : c.farm.stage(level)) : "out of sight"));
        for (var n0 : ClaudeLife.nooksByRoute(c, c.blockPosition())) { int d0 = ClaudeLife.routeDist(c, c.blockPosition(), n0.mouth()); out.add("base nook at " + n0.mouth().toShortString() + " (the y " + n0.origin.getY() + " level of your mine, " + d0 + " blocks from you by your tunnels" + (d0 <= 8 ? ", you're at it" : n0 == c.nook ? ", the nearest" : "") + "; chests, bed, furnaces), " + (level.isLoaded(n0.origin) ? n0.stage(level) : "out of sight")); }
        net.minecraft.core.BlockPos bed = c.house != null && level.isLoaded(c.house.bedHead()) && level.getBlockState(c.house.bedHead()).is(net.minecraft.tags.BlockTags.BEDS) ? c.house.bedHead()
                : c.nook != null && level.isLoaded(c.nook.bedFoot()) && level.getBlockState(c.nook.bedFoot()).is(net.minecraft.tags.BlockTags.BEDS) ? c.nook.bedFoot() : c.getRespawnPosition();
        out.add(bed == null ? "no bed of yours set" : "your bed at " + at.apply(bed));
        net.minecraft.core.BlockPos entrance = ClaudeLife.bpMode(c) && c.blueprint != null && !c.blueprint.isEmpty() ? c.blueprint.entrance : c.stairStart;
        if (entrance != null) out.add("your mine's entrance at " + at.apply(entrance));
        int stored = c.storageChests.size(); if (stored > 0) out.add(stored + " storage chest(s)");
        boolean inNether = level.dimension() == net.minecraft.world.level.Level.NETHER;
        if (inNether && !out.isEmpty()) out.replaceAll(x -> x + " [overworld: not here]");
        { var op = ClaudeLife.hisPortal.get(c.getUUID()); if (op != null) out.add("your overworld portal at " + (inNether ? op.toShortString() + " [overworld]" : at.apply(op))); }
        { var np = ClaudeLife.netherPortal.get(c.getUUID()); if (np != null) out.add("the nether portal you came out of at " + (inNether ? at.apply(np) + ", your way back to the overworld" : np.toShortString() + " [nether]")); }
        return out.isEmpty() ? "none yet" : String.join("; ", out);
    }

    /** One of his actions in plain words, for his own answers ("mining the stone at 3, 40, 7"). */
    public static String describe(ClaudeEntity c, com.claudemod.entity.life.Task t) {
        var level = c.level();
        java.util.function.Function<net.minecraft.core.BlockPos, String> block = p -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(p).getBlock()).getPath().replace('_', ' ');
        return switch (t) {
            case com.claudemod.entity.life.Task.GoTo g -> "walking to " + g.pos().toShortString() + " (" + (int) Math.sqrt(g.pos().distSqr(c.blockPosition())) + " blocks away)";
            case com.claudemod.entity.life.Task.Break b -> "mining the " + block.apply(b.pos()) + " at " + b.pos().toShortString();
            case com.claudemod.entity.life.Task.Place p -> "placing " + com.claudemod.entity.life.Crafting.name(p.item()) + " at " + p.pos().toShortString();
            case com.claudemod.entity.life.Task.Attack a -> {
                var e = level instanceof ServerLevel sl ? sl.getEntity(a.target()) : null;
                yield "fighting a " + (e == null ? "mob" : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
            }
            case com.claudemod.entity.life.Task.Pickup p -> "picking up the drops around him";
            case com.claudemod.entity.life.Task.FinishTree f -> "checking the tree is chopped all the way, no floating logs left";
            case com.claudemod.entity.life.Task.ChopTree ct -> "chopping the tree at " + ct.base().toShortString() + " down whole";
            case com.claudemod.entity.life.Task.SealStep f -> "sealing off a cave the mine broke into, so the mine stays closed";
            case com.claudemod.entity.life.Task.Replant f -> "putting a sapling back where the tree stood";
            case com.claudemod.entity.life.Task.Flee f -> "backing off from the mobs (hurt or outnumbered), then healing up";
            case com.claudemod.entity.life.Task.Pillar p -> p.steps() < 0 ? "taking his tower back down" : "towering up " + p.steps() + " blocks";
            case com.claudemod.entity.life.Task.Eat e -> "eating " + com.claudemod.entity.life.Crafting.name(e.food());
            case com.claudemod.entity.life.Task.Toss x -> "handing them " + x.count() + " " + com.claudemod.entity.life.Crafting.name(x.item());
            case com.claudemod.entity.life.Task.Smelt s -> "cooking " + com.claudemod.entity.life.Crafting.name(s.input()) + " in the furnace";
            case com.claudemod.entity.life.Task.FurnaceLoad f -> "loading the furnace with " + com.claudemod.entity.life.Crafting.name(f.input());
            case com.claudemod.entity.life.Task.FurnaceTake f -> "waiting on the furnace";
            case com.claudemod.entity.life.Task.MineVisibleOres m -> "checking the walls for ore";
            case com.claudemod.entity.life.Task.WallTorch w -> "putting up a torch";
            case com.claudemod.entity.life.Task.CheckForCave k -> "checking if he broke into a cave";
            case com.claudemod.entity.life.Task.Wait w -> "waiting a moment";
            case com.claudemod.entity.life.Task.Fish f -> "fishing";
            case com.claudemod.entity.life.Task.Chest ch -> "at the chest";
            case com.claudemod.entity.life.Task.Deposit d -> "storing stuff in the chest";
            case com.claudemod.entity.life.Task.Till tl -> "tilling the ground for crops";
            case com.claudemod.entity.life.Task.UseDoor u -> (u.open() ? "opening" : "closing") + " a door";
            case com.claudemod.entity.life.Task.Leap l -> (l.kind() == com.claudemod.entity.nav.Leaps.DROP ? "dropping " + l.down() + " down to " : "jumping across to ") + l.land().toShortString() + " (" + com.claudemod.entity.nav.Leaps.fmt(l.hp()) + " HP)";
        };
    }

    /** Posts a line in chat as <Claude>. Returns the line, or null if there was nothing to say. */
    public static String say(ServerPlayer player, String raw, String reason) {
        if (raw == null || player.hasDisconnected()) return null;
        String line = raw.replace('\n', ' ').trim();
        while (line.length() >= 2 && (line.startsWith("\"") && line.endsWith("\""))) line = line.substring(1, line.length() - 1).trim();
        if (line.isEmpty() || line.equals("...")) return null;
        if (line.length() > 240) line = line.substring(0, 240);
        if (line.toLowerCase().matches("(?s).*\\b(in (my |the )?bed|sleeping( now)?|asleep|in bed now)\\b.*")) { // he never claims to be in bed unless he is ("already in bed at the house", twice, while he stood outside it)
            boolean sleeping = false;
            for (ServerPlayer sp : player.server.getPlayerList().getPlayers()) if (sp instanceof com.claudemod.entity.ClaudeEntity c && c.isSleeping()) sleeping = true;
            if (!sleeping && !line.toLowerCase().matches("(?s).*\\b(go(ing)? to bed|to sleep|heading to|get(ting)? (in|into)|a bed|no bed|can't sleep|bed at)\\b.*")) {
                ClaudeMod.LOGGER.info("[Claude] dropped an untrue line (not in bed): \"{}\"", line);
                return null;
            }
        }
        { com.claudemod.entity.ClaudeEntity me0 = com.claudemod.entity.ClaudeEntity.activeFor(player).orElse(null); // he never claims to be on their block or where they stood unless his feet are on it (14:16 "ok im standing where you were", a block off; 14:17 "ok i'm on your block now", 2 off)
          if (me0 != null && (reason == null || !reason.startsWith("exact:")) && com.claudemod.entity.life.ExactSpot.CLAIM.matcher(line.toLowerCase()).find() && !com.claudemod.entity.life.ExactSpot.claimTrue(me0, player)) {
            String fix = com.claudemod.entity.life.ExactSpot.truthLine(me0, player);
            ClaudeMod.LOGGER.info("[Claude] an untrue line (not on their block): \"{}\" said as \"{}\" (ClaudeChat.say, ExactSpot.claimTrue)", line, fix);
            line = fix;
          } }
        if (!"greeting".equals(reason) && ClaudeApi.saidBefore(player, line)) { // a hello on joining always goes out
            ClaudeMod.LOGGER.info("[Claude] skipped a repeat: \"{}\"", line);
            return null;
        }
        player.sendSystemMessage(Component.literal("<Claude> " + line));
        ClaudeApi.remember(player, "you", line);
        ClaudeMod.LOGGER.info("[Claude -> {}] \"{}\"  <- {}", player.getGameProfile().getName(), line, reason);
        return line;
    }

    // ---------------------------------------------------------------- the reply's claims against what's really queued (live 15:50)
    private static final java.util.regex.Pattern RT_SPLIT = java.util.regex.Pattern.compile("[,;.!?:]+|\\s-+\\s|\\b(?:and|then|but|so|while)\\b");
    private static final java.util.regex.Pattern RT_NEG = java.util.regex.Pattern.compile("\\b(?:not|no|never|nothing|wont|cant|dont|didnt|isnt|instead|stop|stopped|done|finished|if|could|should|would|want|wanna|maybe|unless|was|were)\\b|n't\\b");
    private static final java.util.regex.Pattern RT_LATER = java.util.regex.Pattern.compile("\\b(?:going|gonna|will|i'?ll|heading|head|getting|get|step|stepping|once|when|after|before|let me|lemme|back to|into|soon)\\b");
    private static final java.util.regex.Pattern RT_WATER = java.util.regex.Pattern.compile("\\bin (?:the )?water\\b");
    private static final java.util.regex.Pattern RT_NEAR = java.util.regex.Pattern.compile("\\b(?:i'?m|i am)(?: right| now| already| still)? (?:next to|by|beside|with|behind) you\\b");
    private static final java.util.regex.Pattern RT_MINE = java.util.regex.Pattern.compile("\\b(?:mining|digging)\\b");
    private static final java.util.regex.Pattern RT_POUR = java.util.regex.Pattern.compile("\\bpouring\\b");
    private static final java.util.regex.Pattern RT_PLACE = java.util.regex.Pattern.compile("\\bplacing\\b");
    private static final java.util.regex.Pattern RT_COME = java.util.regex.Pattern.compile("\\b(?:coming|on my way)\\b");
    private static final java.util.regex.Pattern RT_FOLLOW = java.util.regex.Pattern.compile("\\bfollowing\\b");
    private static final java.util.regex.Pattern RT_GIVE = java.util.regex.Pattern.compile("\\b(?:here'?s|giving you|handing you|tossing you|tossed you|gave you|dropping you)\\b((?:\\s+[a-z0-9_']+){0,4})");
    private static final java.util.Set<String> RT_DIGS = java.util.Set.of("obtain", "dig_down", "strip_mine", "explore_cave", "start_mine", "make_obsidian", "dig_at", "carve", "collect", "gear_up", "village_iron", "buried_treasure");
    private static final java.util.Set<String> RT_POURS = java.util.Set.of("make_obsidian", "build_portal", "complete_portal", "place_at", "place");
    private static final java.util.Set<String> RT_PLACES = java.util.Set.of("place", "place_at", "build_my_place", "build_here", "build_blueprint", "build_portal", "complete_portal", "make_obsidian", "light_up", "light_mine", "shelter", "plant_saplings", "farm", "start_mine");
    private static final java.util.Set<String> RT_STOP = java.util.Set.of("the", "your", "some", "you", "them", "from", "what", "that", "this", "more", "all", "now", "back", "over", "here", "there", "pack");

    /**
     * The chat reply's line against what really got queued (after ChatListener.act: errands expanded, what's on him already
     * counted) and his state. The claims: where he is (in the water; next to them), what he's doing (mining, digging, pouring,
     * placing, coming, following), what he hands over (here's / giving you X). One the queue or his state doesn't back: the line
     * is rewritten from the queue, what he's really doing and plainly what he isn't, and logged. (15:50 "ok, i'm in the water now,
     * mining the obsidian from here" while the errand handed over 10 of the 11 on him.)
     */
    /** The line with every comma/"but"/sentence piece matching `drop` taken out. */
    public static String dropClauses(String line, String drop) {
        if (line == null) return null; StringBuilder out = new StringBuilder();
        for (String piece : line.split("(?<=[.,;!?])\\s+|\\s+(?=but\\b)")) if (!piece.toLowerCase().matches(drop)) out.append(out.length() > 0 ? " " : "").append(piece);
        return out.toString().replaceAll("^(but|and)\\s+", "").replaceAll("[,;]\\s*$", "").trim();
    }
    /** A report after a failed step never claims the job worked (00:21 "got my stuff back ..., but the recover itself didn't really pan out"). */
    public static String reportTruth(Object results, String text) {
        String r = String.valueOf(results), low = r.toLowerCase();
        boolean failed = low.contains("failed") || low.contains("-> failed") || low.contains("couldn't") || low.contains("didn't");
        if (!failed || text == null) return text;
        String claim = ".*\\b(got (it|them|my|your|the|everything|all)|done|all set|have it|got it back|back now|picked (it|them|everything) up|recovered)\\b.*";
        String kept = dropClauses(text, "(?!.*\\b(didn'?t|couldn'?t|can'?t|not|no|failed|wasn'?t)\\b)" + claim);
        if (!kept.equals(text)) com.claudemod.ClaudeMod.LOGGER.info("[Claude reply truth] report: \"{}\" said as \"{}\": a step failed ({}) (ClaudeChat.reportTruth)", text, kept, r.length() > 160 ? r.substring(0, 160) : r);
        if (kept.isBlank()) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("failed: ([^;\\]]{1,120})").matcher(r); return "that didn't work" + (m.find() ? ": " + m.group(1).trim() : ""); }
        return kept;
    }
    public static String replyTruth(ClaudeEntity g, ServerPlayer p, String text, com.google.gson.JsonObject reply) { return replyTruth(g, p, text, reply, ""); }
    public static String replyTruth(ClaudeEntity g, ServerPlayer p, String text, com.google.gson.JsonObject reply, String ask) {
        if (text == null || text.isBlank()) return text;
        java.util.List<String> gearFix = new java.util.ArrayList<>();
        try { text = gearTruth(g, text, ask, gearFix); } catch (RuntimeException ex) { ClaudeMod.LOGGER.warn("[Claude reply truth] couldn't check the gear in \"{}\" ({}) (ClaudeChat.gearTruth)", text, ex.toString()); }
        try {
            String low = text.toLowerCase().replace('\u2019', '\'');
            String action = reply != null && reply.has("action") ? reply.get("action").getAsString() : "none";
            java.util.List<com.claudemod.entity.life.Skills.Step> q = new java.util.ArrayList<>(g.skillQueue);
            java.util.Set<String> skills = new java.util.HashSet<>(); for (var s : q) skills.add(s.skill());
            com.claudemod.entity.life.Task cur = g.dropCurrent ? null : g.currentTask;
            java.util.List<com.claudemod.entity.life.Task> ts = new java.util.ArrayList<>(g.tasks); if (cur != null) ts.add(cur);
            boolean giving = skills.contains("give") || skills.contains("errand") || ts.stream().anyMatch(t -> t instanceof com.claudemod.entity.life.Task.Toss);
            java.util.List<String> gives = new java.util.ArrayList<>(); boolean makes = false; // the reply's gives (the errand took them in): what's on him for each, past what he keeps
            if (giving && reply != null && reply.has("steps")) for (var el : reply.getAsJsonArray("steps")) {
                var o = el.getAsJsonObject(); if (!o.has("skill") || !o.get("skill").getAsString().equals("give")) continue;
                net.minecraft.world.item.Item it = Errand.resolve(g, o.get("target").getAsString()); if (it == null) continue;
                int n = Math.max(1, o.has("count") ? o.get("count").getAsInt() : 1), have = Errand.have(g, it), spare = Math.max(0, have - Errand.keepNow(g, it));
                String nm = com.claudemod.entity.life.Crafting.name(it).replace('_', ' ');
                if (spare >= n) gives.add("giving you " + n + " " + nm + " from my pack (i already had " + have + ")");
                else { makes = true; gives.add(spare > 0 ? "giving you " + spare + " " + nm + " from my pack now, getting the other " + (n - spare) + " first" : "getting you " + n + " " + nm + " first, then bringing it over"); }
            }
            boolean planning = q.isEmpty() && ts.isEmpty() && g.nextPlanTick <= g.tickCount; // (an order with no steps: his own planner plans it now; what he'll do isn't known yet)
            double d = ClaudeLife.toThem(g, p);
            boolean digs = makes || skills.stream().anyMatch(RT_DIGS::contains) || ts.stream().anyMatch(t -> t instanceof com.claudemod.entity.life.Task.Break);
            boolean pours = skills.stream().anyMatch(RT_POURS::contains);
            boolean places = pours || skills.stream().anyMatch(RT_PLACES::contains) || ts.stream().anyMatch(t -> t instanceof com.claudemod.entity.life.Task.Place);
            boolean comes = action.equals("come") || giving || skills.contains("go_to") || skills.contains("follow") || skills.contains("return_drops") || ts.stream().anyMatch(t -> t instanceof com.claudemod.entity.life.Task.GoTo) || d <= 4;
            boolean follows = action.equals("follow") || skills.contains("follow") || "yes".equals(g.prefs.get("stay_close"));
            java.util.List<String> wrong = new java.util.ArrayList<>(), fix = new java.util.ArrayList<>();
            for (String cl : RT_SPLIT.split(low)) {
                String c0 = cl.trim(); if (c0.isEmpty()) continue;
                boolean neg = RT_NEG.matcher(c0).find(), later = RT_LATER.matcher(c0).find();
                if (!neg && !later && RT_WATER.matcher(c0).find() && !g.isInWater()) { wrong.add("\"" + c0 + "\": he's not in water (feet " + g.blockPosition().toShortString() + ")"); rtAdd(fix, "i'm not in the water"); }
                if (!neg && !later && RT_NEAR.matcher(c0).find() && d > 5) { wrong.add("\"" + c0 + "\": " + (d == Double.MAX_VALUE ? "he doesn't know where they are" : (int) d + " blocks from them")); rtAdd(fix, d == Double.MAX_VALUE ? "i'm not by you" : "i'm " + (int) d + " blocks from you"); }
                if (neg || planning) continue;
                if (RT_MINE.matcher(c0).find() && !digs) { wrong.add("\"" + c0 + "\": nothing queued mines" + (gives.isEmpty() ? "" : " (all of it is on him already)")); rtAdd(fix, gives.isEmpty() ? "not mining anything" : "not mining any"); }
                if (RT_POUR.matcher(c0).find() && !pours) { wrong.add("\"" + c0 + "\": nothing queued pours"); rtAdd(fix, "not pouring anything"); }
                if (RT_PLACE.matcher(c0).find() && !places) { wrong.add("\"" + c0 + "\": nothing queued places"); rtAdd(fix, "not placing anything"); }
                if (RT_COME.matcher(c0).find() && !comes) { wrong.add("\"" + c0 + "\": nothing queued takes him to them (" + (int) Math.min(d, 9999) + " off)"); rtAdd(fix, "staying where i am"); }
                if (RT_FOLLOW.matcher(c0).find() && !follows) { wrong.add("\"" + c0 + "\": not following them (no follow, not staying close)"); rtAdd(fix, "not following you"); }
                var gm = RT_GIVE.matcher(c0);
                if (gm.find()) {
                    net.minecraft.world.item.Item it = null;
                    for (String w : gm.group(1).trim().split("\\s+")) if (w.matches("[a-z_]{3,}") && !RT_STOP.contains(w)) { it = Errand.resolve(g, w); if (it != null) break; }
                    if (!giving) { wrong.add("\"" + c0 + "\": nothing queued hands anything over"); rtAdd(fix, "not handing anything over"); }
                    else if (it != null && Errand.have(g, it) == 0) { wrong.add("\"" + c0 + "\": no " + com.claudemod.entity.life.Crafting.name(it) + " on him"); rtAdd(fix, "i've no " + com.claudemod.entity.life.Crafting.name(it).replace('_', ' ') + " on me yet"); }
                }
            }
            rtMoves(g, p, low, q, skills, ts, action, planning, wrong, fix); // ("jumping", "heading up to X": the queue backs them or they're rewritten)
            if (wrong.isEmpty()) return text;
            java.util.List<String> parts = new java.util.ArrayList<>(gives);
            for (var s : q) if (!s.skill().equals("errand") && !s.skill().equals("give") && parts.size() < 4) parts.add(rtStep(s));
            if (parts.isEmpty() && cur != null) parts.add(describe(g, cur));
            if (parts.isEmpty() && follows) parts.add("sticking with you");
            for (String gf : gearFix) rtAdd(fix, gf); // (the gear said wrong, said right in the rebuilt line too)
            String fx = fix.size() <= 1 ? String.join("", fix) : String.join(", ", fix.subList(0, fix.size() - 1)) + ", and " + fix.get(fix.size() - 1);
            String line = (parts.isEmpty() ? "nothing queued right now" : String.join(", then ", parts)) + "; " + fx;
            ClaudeMod.LOGGER.info("[Claude reply truth] \"{}\" said as \"{}\": {} (queue {}, task {}, action {}) (ChatListener.handle, ClaudeChat.replyTruth)", text, line, String.join("; ", wrong), q, cur == null ? "-" : cur.getClass().getSimpleName(), action);
            return line;
        } catch (RuntimeException ex) {
            ClaudeMod.LOGGER.warn("[Claude reply truth] couldn't check \"{}\" ({}): said as it came (ClaudeChat.replyTruth)", text, ex.toString());
            return text;
        }
    }
    private static void rtAdd(java.util.List<String> fix, String s) { if (!fix.contains(s)) fix.add(s); }
    private static final java.util.regex.Pattern RT_JUMP = java.util.regex.Pattern.compile("\\b(?:jumping|leaping)\\b|\\b(?:dropping|hopping|jump|drop|hop) (?:down|off|over|across)\\b");
    private static final java.util.regex.Pattern RT_HEAD = java.util.regex.Pattern.compile("\\b(?:heading|going|walking|running|making my way|on my way)(?: (?:up|down|back|over|out|across))? (?:to|toward|towards|into) (?:the|that|this|your|my|you|them|it|those|a|an)\\b|\\bheading (?:up|down|over|back|out) (?:there|that way)\\b");
    private static final java.util.regex.Pattern RT_BREAK = java.util.regex.Pattern.compile("\\b(?:breaking|digging|mining|tunnell?ing|chopping|clearing) (?:the |that |this |those |these |them|it|through|out|in|up|down|blocks?|stone|dirt|rock|wall|way)");
    private static boolean rtBreaks(String sk) { for (String w : new String[] {"collect", "strip_mine", "dig", "start_mine", "carve", "make_obsidian", "harvest", "iron", "diamond", "stone", "explore_cave", "reshape_mine", "build_portal", "scoop_at", "blaze_rods"}) if (sk.contains(w)) return true; return false; }
    private static boolean rtWalks(String sk) { for (String w : new String[] {"go_to", "follow", "explore", "loot", "portal", "return", "recover", "retrieve", "village", "fortress", "scout", "drop_down", "sleep", "store", "treasure"}) if (sk.contains(w)) return true; return false; }
    /**
     * The move claims: "jumping / dropping down / jump off" is true only with a drop or jump queued or running, drop_down queued, or a
     * queued walk whose planned way takes one (Leaps.routeLeaps); "heading / going up to / over to the X" only with a walk queued.
     * (16:32 "jumping off the ledge now" with nothing queued that jumps; 17:02 "heading up to that fortress now" over a failed pick_up.)
     */
    static void rtMoves(ClaudeEntity g, ServerPlayer p, String low, java.util.List<com.claudemod.entity.life.Skills.Step> q, java.util.Set<String> skills, java.util.List<com.claudemod.entity.life.Task> ts, String action, boolean planning, java.util.List<String> wrong, java.util.List<String> fix) {
        boolean leapQ = ts.stream().anyMatch(t0 -> t0 instanceof com.claudemod.entity.life.Task.Leap) || skills.contains("drop_down");
        boolean walkQ = action.equals("come") || action.equals("follow") || skills.stream().anyMatch(ClaudeChat::rtWalks) || ts.stream().anyMatch(t0 -> t0 instanceof com.claudemod.entity.life.Task.GoTo gt && gt.pos().distSqr(g.blockPosition()) > 3 * 3);
        Boolean viaLeap = null;
        boolean breakQ = ts.stream().anyMatch(t0 -> t0 instanceof com.claudemod.entity.life.Task.Break || t0 instanceof com.claudemod.entity.life.Task.ChopTree || t0 instanceof com.claudemod.entity.life.Task.FinishTree || t0 instanceof com.claudemod.entity.life.Task.MineVisibleOres || t0 instanceof com.claudemod.entity.life.Task.SealStep || t0 instanceof com.claudemod.entity.life.Task.CheckForCave || t0 instanceof com.claudemod.entity.life.Task.Pillar p0 && p0.steps() < 0)
                || skills.stream().anyMatch(ClaudeChat::rtBreaks);
        for (String cl : RT_SPLIT.split(low)) {
            String c0 = cl.trim(); if (c0.isEmpty() || RT_NEG.matcher(c0).find()) continue;
            if (RT_JUMP.matcher(c0).find() && !leapQ) {
                if (viaLeap == null) viaLeap = walkQ && com.claudemod.entity.nav.Leaps.routeLeaps(g, p, ts) != null;
                if (!viaLeap) { String why = com.claudemod.entity.nav.Leaps.lastRefusal(g, 20 * 60); wrong.add("\"" + c0 + "\": nothing queued drops or jumps" + (why == null ? "" : " (last turned down: " + why + ")")); rtAdd(fix, why == null ? "not jumping (no drop or jump on my way)" : "not jumping: " + why); }
            }
            if (!planning && RT_BREAK.matcher(c0).find() && !RT_LATER.matcher(c0).find() && !breakQ) { String st0 = com.claudemod.entity.life.Watchdog.stuckLine(g); wrong.add("\"" + c0 + "\": nothing queued breaks a block (queue " + q + ")" + (st0 == null ? "" : ", " + st0)); rtAdd(fix, st0 == null ? "not breaking anything yet" : "not breaking anything yet (" + st0 + ")"); } // (live: "breaking the blocks now" while he only re-planned a walk)
            if (!planning && RT_HEAD.matcher(c0).find() && !walkQ && !leapQ) { wrong.add("\"" + c0 + "\": no walk queued (queue " + q + ")"); rtAdd(fix, "not heading anywhere yet"); }
        }
    }
    /** A line's move claims (a chat reply's, reportBack's "what happened" line): only "jumping" and "heading to X" checked against the queue; the rest as it came. */
    public static String moveTruth(ClaudeEntity g, ServerPlayer p, String text) { return moveTruth(g, p, text, "none"); }
    public static String moveTruth(ClaudeEntity g, ServerPlayer p, String text, String action) {
        if (text == null || text.isBlank()) return text;
        try {
            String low = text.toLowerCase().replace('\u2019', '\'');
            java.util.List<com.claudemod.entity.life.Skills.Step> q = new java.util.ArrayList<>(g.skillQueue);
            java.util.Set<String> skills = new java.util.HashSet<>(); for (var s : q) skills.add(s.skill());
            com.claudemod.entity.life.Task cur = g.dropCurrent ? null : g.currentTask;
            java.util.List<com.claudemod.entity.life.Task> ts = new java.util.ArrayList<>(g.tasks); if (cur != null) ts.add(cur);
            java.util.List<String> wrong = new java.util.ArrayList<>(), fix = new java.util.ArrayList<>();
            rtMoves(g, p, low, q, skills, ts, action == null ? "none" : action, false, wrong, fix);
            if (wrong.isEmpty()) return text;
            String now = !q.isEmpty() ? rtStep(q.get(0)) : cur != null ? describe(g, cur) : "nothing queued right now";
            String line = now + "; " + String.join(", ", fix);
            ClaudeMod.LOGGER.info("[Claude reply truth] move claims: \"{}\" said as \"{}\": {} (queue {}, task {}) (ClaudeChat.moveTruth)", text, line, String.join("; ", wrong), q, cur == null ? "-" : cur.getClass().getSimpleName());
            return line;
        } catch (RuntimeException ex) { ClaudeMod.LOGGER.warn("[Claude reply truth] couldn't check the report \"{}\" ({}) (ClaudeChat.moveTruth)", text, ex.toString()); return text; }
    }
    /** A queued step in plain words, for a rewritten line. */
    static String rtStep(com.claudemod.entity.life.Skills.Step s) {
        String sk = s.skill(), w = sk.replace('_', ' '), t = s.target() == null ? "" : s.target().replace("minecraft:", "").replace('_', ' ').trim();
        return switch (sk) {
            case "go_to" -> "heading to " + (t.isEmpty() ? "you" : t);
            case "follow" -> "following you";
            case "wait" -> "waiting";
            default -> w + (t.isEmpty() || w.endsWith(t) ? "" : " " + (s.count() > 1 ? s.count() + " " : "") + t);
        };
    }

    // ---------------------------------------------------------------- gear claims and the nether check (live 16:18)
    static final java.util.regex.Pattern FALL_ASK = java.util.regex.Pattern.compile("\\b(?:jump|jumping|drop|dropping|fall|falling|ledge|survive|fall damage|hurt you|come down|get down)\\b");
    static final java.util.regex.Pattern NETHER_ASK = java.util.regex.Pattern.compile("\\b(?:nether|portal|ready)\\b|everything you need|got everything|have everything");
    private static final java.util.regex.Pattern GT_WISH = java.util.regex.Pattern.compile("\\b(?:need|needs|make|making|craft|crafting|find|missing|short|lack|lacking|without|than|instead|upgrade|better|wish|hope|until|till|almost|mostly|nearly|partly|kinda|sort of)\\b");
    private static final java.util.regex.Pattern GT_ARMOR_SET = java.util.regex.Pattern.compile("\\b(full|all|in|wearing|decked out in|rocking) (leather|chain|chainmail|iron|gold|golden|diamond|netherite)\\b(?! (armor|armour|gear|set|helmet|helm|chestplate|chest|leggings|legs|pants|boots|swords?|picks?|pickaxes?|axes?|shovels?|hoes?|ingots?|ores?|blocks?|nuggets?|tools?|apples?|carrots?|horse))"); // ("full diamond on": the whole set, said by its tier)
    private static final java.util.regex.Pattern GT_ARMOR = java.util.regex.Pattern.compile("\\b(leather|chain|chainmail|iron|gold|golden|diamond|netherite) (armor|armour|gear|set|helmet|helm|chestplate|chest|leggings|legs|pants|boots)\\b");
    private static final java.util.regex.Pattern GT_TOOL = java.util.regex.Pattern.compile("\\b(?:(wooden|wood|stone|iron|gold|golden|diamond|netherite) )?(sword|pickaxe|pick|axe|shovel|hoe|bow|crossbow|shield|trident|flint and steel|flint n steel|flint)\\b");
    private static final java.util.regex.Pattern GT_HAVE = java.util.regex.Pattern.compile("\\b(?:i have|i've got|ive got|i got|i've|ive|got|have|carrying)\\s+((?:[a-z0-9_]+\\s*){1,4})");
    private static final java.util.regex.Pattern GT_READY = java.util.regex.Pattern.compile("\\b(?:ready|all set|good to go|(?:got|have) everything)\\b");
    private static final net.minecraft.world.entity.EquipmentSlot[] GT_SLOTS = {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};

    private static String gtTier(net.minecraft.world.item.Item it) { String n = com.claudemod.entity.life.Crafting.name(it); int i = n.indexOf('_'); return i < 0 ? n : n.substring(0, i); }
    private static String gtTierWord(String w) { return w == null ? null : w.equals("gold") ? "golden" : w.equals("wood") ? "wooden" : w.equals("chain") ? "chainmail" : w; }
    private static String gtNice(net.minecraft.world.item.Item it) { return com.claudemod.entity.life.Crafting.name(it).replace("golden_", "gold_").replace('_', ' '); }
    /** What he wears, in words: "iron armor" (all four one tier), else piece by piece; "no armor on". */
    static String wornWords(ClaudeEntity g) {
        java.util.List<String> parts = new java.util.ArrayList<>(); java.util.Set<String> tiers = new java.util.HashSet<>(); int n = 0;
        for (var sl : GT_SLOTS) { var s = g.getItemBySlot(sl); if (s.isEmpty()) continue; n++; tiers.add(gtTier(s.getItem())); parts.add(gtNice(s.getItem())); }
        if (n == 0) return "no armor on";
        if (n == 4 && tiers.size() == 1) return tiers.iterator().next().replace("golden", "gold") + " armor";
        return String.join(", ", parts);
    }
    private static boolean gtClaim(String clause) { return !RT_NEG.matcher(clause).find() && !RT_LATER.matcher(clause).find() && !GT_WISH.matcher(clause).find(); }
    /** The clause of the line round [a, b): between the split marks either side. */
    private static String gtClause(String low, int a, int b) {
        int s = 0, e = low.length(); var m = RT_SPLIT.matcher(low);
        while (m.find()) { if (m.end() <= a) s = m.end(); else if (m.start() >= b) { e = m.start(); break; } }
        return low.substring(s, e);
    }

    // possession, not a need: a sentence that's a need, want, list, plan or skip names things he hasn't got on purpose (17:27-17:29)
    private static final java.util.regex.Pattern GT_NEED = java.util.regex.Pattern.compile("\\b(?:need|needs|needed|want|wants|wanted|next|getting|gonna get|going to get|to get|on my list|my list|skipping|skip|for the nether|still need|missing|first|then|after that|plan|planning|short on|out of|lack|lacking|could use|looking for|grab|grabbing|bring|bringing|craft|crafting|make|making)\\b");
    private static final java.util.regex.Pattern GT_OWN = java.util.regex.Pattern.compile("\\b(?:i have|i've got|ive got|i got|i've|ive|i'm wearing|im wearing|wearing|on me|here's|heres|carrying|in my pack|i'm in|im in|i'm rocking|have|got)\\b");
    private static final java.util.regex.Pattern GT_PLACE = java.util.regex.Pattern.compile("\\b(?:in|into|inside) (?:my |the |a |his |our )?(?:chests?|storage|box|barrels?|furnaces?|base|house|home|nook|stash|ender chest)\\b|\\bat (?:home|base|the house|my base|the nook)\\b|\\bback at\\b");
    private static final java.util.regex.Pattern ASK_OWN = java.util.regex.Pattern.compile("\\b(?:do|did) you (?:have|got)\\b|\\bhave you got\\b|\\byou got\\b|got everything|have everything|everything you need|\\bwhat (?:do|have) you (?:have|got)\\b|\\bwhat(?:'s| is) on you\\b|\\bwhat are you (?:wearing|carrying)\\b|\\bready\\b|\\binventory\\b");
    private static final java.util.regex.Pattern ASK_NEED = java.util.regex.Pattern.compile("\\bwhat (?:else )?(?:do|would|will|did) you (?:still )?need\\b|\\bwhat are you missing\\b|\\bwhat(?:'s| is) missing\\b|\\bwhat (?:should|do) (?:we|i|you) (?:get|bring)\\b|\\bwhat(?:'s| is) next\\b");
    private static final java.util.regex.Pattern GT_FILLER = java.util.regex.Pattern.compile("(?:\\s|[,.;:!?]|\\b(?:yeah|yep|yes|ya|yup|ok|okay|sure|mostly|got it|alright|right|so|well)\\b)*");
    private static final String GT_PUNCT = ",;.!?:", GT_STOP = ".!?;";
    /** The sentence round a: between . ! ? ; either side. */
    private static String gtSentence(String low, int a) { int s = a, e = a; while (s > 0 && GT_STOP.indexOf(low.charAt(s - 1)) < 0) s--; while (e < low.length() && GT_STOP.indexOf(low.charAt(e)) < 0) e++; return low.substring(s, e); }
    /** The comma/sentence piece round a: what a false claim drops. */
    private static int[] gtSeg(String low, int a) { int s = a, e = a; while (s > 0 && GT_PUNCT.indexOf(low.charAt(s - 1)) < 0) s--; while (e < low.length() && GT_PUNCT.indexOf(low.charAt(e)) < 0) e++; return new int[] {s, e}; }
    /** A present-tense possession claim at [a, b): not negated, wished for, later, about a chest or home; its sentence no need/list/plan; possession said in the clause, or asked about. */
    private static boolean gtOwns(String low, int a, int b, boolean ownAsk, boolean selfMarked) {
        String cl = gtClause(low, a, b);
        if (!gtClaim(cl) || GT_NEED.matcher(gtSentence(low, a)).find() || GT_PLACE.matcher(cl).find()) return false;
        return selfMarked || ownAsk || GT_OWN.matcher(cl).find();
    }
    /** His worn armor's one tier ("iron"), when all four slots wear it; else null. */
    private static String gtOneTier(ClaudeEntity g) {
        String t = null;
        for (var sl : GT_SLOTS) { var s = g.getItemBySlot(sl); if (s.isEmpty()) return null; String x = gtTier(s.getItem()); if (t != null && !t.equals(x)) return null; t = x; }
        return t == null ? null : t.replace("golden", "gold");
    }

    /**
     * What he says he has or wears, against his pack and armor slots: armor tier (a set: 3 of 4 slots, worn or carried; a piece:
     * that slot or carried; "full diamond" too), tools and weapons with their tier (pack, hands), "have / got X", and being ready
     * when the ask is about the nether (the nether check's blockers). Only present-tense possession counts (gtOwns): a need, want,
     * list, plan or skip names things he hasn't got. A wrong tier with one true tier is swapped as the whole phrase ("iron armor
     * (not diamond)"); any other false claim drops its comma/sentence piece and a short true note goes after the line ("(no shield
     * on me)"); with only "yeah" left, the note alone. The fixes go into fixOut too (a rebuilt line says them).
     */
    static String gearTruth(ClaudeEntity g, String text, String ask, java.util.List<String> fixOut) {
        String low = text.toLowerCase().replace('\u2019', '\'');
        if (low.length() != text.length()) return text;
        String askLow = ask == null ? "" : ask.toLowerCase().replace('\u2019', '\'');
        boolean ownAsk = ASK_OWN.matcher(askLow).find() && !ASK_NEED.matcher(askLow).find();
        record Fix(int a, int b, boolean drop, String with, String why) {} // drop: the piece goes, "with" is the note after the line; else "with" replaces [a, b)
        java.util.List<Fix> fixes = new java.util.ArrayList<>();
        var inv = g.getInventory();
        String one = gtOneTier(g);
        var m = GT_ARMOR.matcher(low);
        while (m.find()) {
            if (!gtOwns(low, m.start(), m.end(), ownAsk, false)) continue;
            String tier = gtTierWord(m.group(1)), piece = m.group(2);
            net.minecraft.world.entity.EquipmentSlot slot = switch (piece) { case "helmet", "helm" -> GT_SLOTS[0]; case "chestplate", "chest" -> GT_SLOTS[1]; case "leggings", "legs", "pants" -> GT_SLOTS[2]; case "boots" -> GT_SLOTS[3]; default -> null; };
            int ok = 0;
            for (var sl : GT_SLOTS) {
                if (slot != null && sl != slot) continue;
                var w = g.getItemBySlot(sl);
                if (!w.isEmpty() && gtTier(w.getItem()).equals(tier) || inv.hasAnyMatching(s -> s.getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getEquipmentSlot() == sl && gtTier(s.getItem()).equals(tier))) ok++;
            }
            if (slot == null ? ok >= 3 : ok >= 1) continue;
            String tw = m.group(1).replace("golden", "gold"), why = "\"" + m.group() + "\": wearing " + wornWords(g);
            if (slot == null && one != null) fixes.add(new Fix(m.start(), m.end(), false, one + " " + piece + " (not " + tw + ")", why));
            else if (slot != null && !g.getItemBySlot(slot).isEmpty()) fixes.add(new Fix(m.start(), m.end(), false, gtNice(g.getItemBySlot(slot).getItem()) + " (not " + tw + ")", why));
            else fixes.add(new Fix(m.start(), m.end(), true, slot != null ? "no " + piece + " on me" : wornWords(g).equals("no armor on") ? "no armor on me" : "i'm wearing " + wornWords(g), why));
        }
        m = GT_ARMOR_SET.matcher(low);
        while (m.find()) { // "full diamond", "all iron", "in netherite": a whole-set claim (its own words say possession)
            final int ms0 = m.start(); if (fixes.stream().anyMatch(f -> f.a() <= ms0 && ms0 < f.b())) continue;
            if (!gtOwns(low, m.start(), m.end(), ownAsk, true)) continue;
            String tier = gtTierWord(m.group(2)); int ok = 0;
            for (var sl : GT_SLOTS) {
                var w = g.getItemBySlot(sl);
                if (!w.isEmpty() && gtTier(w.getItem()).equals(tier) || inv.hasAnyMatching(s -> s.getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getEquipmentSlot() == sl && gtTier(s.getItem()).equals(tier))) ok++;
            }
            if (ok >= 3) continue;
            String tw = m.group(2).replace("golden", "gold"), why = "\"" + m.group() + "\": wearing " + wornWords(g);
            if (one != null) fixes.add(new Fix(m.start(2), m.end(2), false, one + " (not " + tw + ")", why));
            else fixes.add(new Fix(m.start(), m.end(), true, wornWords(g).equals("no armor on") ? "no armor on me" : "i'm wearing " + wornWords(g), why));
        }
        m = GT_TOOL.matcher(low);
        while (m.find()) {
            final int ms = m.start(); if (fixes.stream().anyMatch(f -> f.a() <= ms && ms < f.b())) continue;
            if (!gtOwns(low, m.start(), m.end(), ownAsk, false)) continue;
            String tier = gtTierWord(m.group(1)), kind = m.group(2);
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> cls = switch (kind) {
                case "sword" -> s -> s.getItem() instanceof net.minecraft.world.item.SwordItem;
                case "pickaxe", "pick" -> s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem;
                case "axe" -> s -> s.getItem() instanceof net.minecraft.world.item.AxeItem;
                case "shovel" -> s -> s.getItem() instanceof net.minecraft.world.item.ShovelItem;
                case "hoe" -> s -> s.getItem() instanceof net.minecraft.world.item.HoeItem;
                case "bow" -> s -> s.is(net.minecraft.world.item.Items.BOW);
                case "crossbow" -> s -> s.is(net.minecraft.world.item.Items.CROSSBOW);
                case "shield" -> s -> s.is(net.minecraft.world.item.Items.SHIELD);
                case "trident" -> s -> s.is(net.minecraft.world.item.Items.TRIDENT);
                case "flint" -> s -> s.is(net.minecraft.world.item.Items.FLINT) || s.is(net.minecraft.world.item.Items.FLINT_AND_STEEL);
                default -> s -> s.is(net.minecraft.world.item.Items.FLINT_AND_STEEL);
            };
            if (inv.hasAnyMatching(s -> cls.test(s) && (tier == null || gtTier(s.getItem()).equals(tier)))) continue;
            net.minecraft.world.item.Item other = null;
            for (int i = 0; i < inv.getContainerSize() && other == null; i++) { var s = inv.getItem(i); if (!s.isEmpty() && cls.test(s)) other = s.getItem(); }
            String why = "\"" + m.group() + "\": " + (other != null ? "he has " + com.claudemod.entity.life.Crafting.name(other) : "none on him");
            if (other != null && tier != null) fixes.add(new Fix(m.start(), m.end(), false, gtNice(other) + " (not " + m.group(1).replace("golden", "gold") + ")", why));
            else fixes.add(new Fix(m.start(), m.end(), true, "no " + kind + " on me", why));
        }
        m = GT_HAVE.matcher(low);
        while (m.find()) {
            String cl = gtClause(low, m.start(), m.end());
            if (!gtOwns(low, m.start(), m.end(), ownAsk, true) || GT_ARMOR.matcher(cl).find() || GT_ARMOR_SET.matcher(cl).find() || GT_TOOL.matcher(cl).find()) continue;
            String[] w = m.group(1).trim().split("\\s+"); int n = -1; net.minecraft.world.item.Item it = null;
            for (int i = 0; i < w.length && it == null; i++) {
                if (w[i].matches("\\d+")) { n = Integer.parseInt(w[i]); continue; }
                if (!w[i].matches("[a-z_]{3,}") || RT_STOP.contains(w[i])) continue;
                if (i + 1 < w.length && w[i + 1].matches("[a-z_]{3,}")) it = Errand.resolve(g, w[i] + "_" + w[i + 1]);
                if (it == null) it = Errand.resolve(g, w[i]);
            }
            if (it == null) continue;
            final net.minecraft.world.item.Item it0 = it;
            int on = 0; for (int i = 0; i < inv.getContainerSize(); i++) { var s = inv.getItem(i); if (s.is(it0)) on += s.getCount(); }
            if (on > 0 && (n < 0 || on >= n)) continue;
            String nm = gtNice(it);
            fixes.add(new Fix(m.start(), m.end(), true, on == 0 ? "no " + nm + " on me" : "only " + on + " " + nm + " on me", "\"" + m.group().trim() + "\": " + on + " " + com.claudemod.entity.life.Crafting.name(it) + " on him"));
        }
        if (NETHER_ASK.matcher(askLow).find()) {
            var blockers = netherCheck(g).get(0);
            m = GT_READY.matcher(low);
            while (m.find() && !blockers.isEmpty()) {
                String cl = gtClause(low, m.start(), m.end());
                if (!gtClaim(cl) || GT_NEED.matcher(gtSentence(low, m.start())).find()) continue;
                java.util.List<String> b = new java.util.ArrayList<>(); for (String x : blockers) b.add(x.replaceFirst(" \\(.*", ""));
                fixes.add(new Fix(m.start(), m.end(), true, "not ready yet: " + String.join(", ", b), "\"" + m.group() + "\": the nether check's blockers " + blockers));
            }
        }
        if (fixes.isEmpty()) return text;
        // the edits: each dropped piece (with the comma before it, or after it at a sentence start), the swaps outside them
        java.util.TreeMap<Integer, Integer> cut = new java.util.TreeMap<>(); java.util.List<String> notes = new java.util.ArrayList<>(), why = new java.util.ArrayList<>();
        for (Fix f : fixes) if (f.drop()) {
            int[] sg = gtSeg(low, f.a()); int s = sg[0], e = sg[1];
            if (s > 0 && ",;:".indexOf(low.charAt(s - 1)) >= 0) s--; else if (e < low.length() && low.charAt(e) == ',') e++;
            cut.merge(s, e, Math::max); rtAdd(notes, f.with()); why.add(f.why());
        }
        java.util.List<int[]> edits = new java.util.ArrayList<>(); java.util.List<String> withs = new java.util.ArrayList<>();
        for (var c : cut.entrySet()) { edits.add(new int[] {c.getKey(), c.getValue()}); withs.add(""); }
        for (Fix f : fixes) if (!f.drop() && cut.entrySet().stream().noneMatch(c -> c.getKey() <= f.a() && f.a() < c.getValue())) { edits.add(new int[] {f.a(), f.b()}); withs.add(f.with()); why.add(f.why()); fixOut.add(f.with()); }
        Integer[] order = new Integer[edits.size()]; for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Integer.compare(edits.get(y)[0], edits.get(x)[0]));
        StringBuilder sb = new StringBuilder(text); int last = Integer.MAX_VALUE;
        for (int i : order) { int[] e = edits.get(i); if (e[1] > last) continue; sb.replace(e[0], e[1], withs.get(i)); last = e[0]; }
        String rest = sb.toString().replaceAll("\\s+", " ").replaceAll(" ([,.;:!?])", "$1").replaceAll("([,;:])\\s*([,;:.!?])", "$2").replaceAll("([.!?])\\s*[,;:]+", "$1").replaceAll("^[\\s,;:.!?]+", "").trim();
        String line = notes.isEmpty() ? rest : GT_FILLER.matcher(rest).matches() ? String.join("; ", notes) : rest + " (" + String.join("; ", notes) + ")";
        fixOut.addAll(notes);
        ClaudeMod.LOGGER.info("[Claude reply truth] gear: \"{}\" said as \"{}\": {} (ask \"{}\", {}) (ClaudeChat.gearTruth)", text, line, String.join("; ", why), ask, ownAsk ? "a possession ask" : "not a possession ask");
        return line;
    }

    /** An expert's nether check of what's on him: [blockers, wanted, have, optional]. */
    static java.util.List<java.util.List<String>> netherCheck(ClaudeEntity c) {
        java.util.List<String> must = new java.util.ArrayList<>(), want = new java.util.ArrayList<>(), have = new java.util.ArrayList<>(), opt = new java.util.ArrayList<>();
        var inv = c.getInventory();
        int food = Kit.food(c);
        if (food == 0) must.add("no food (none on you: no healing, no sprinting there)"); else if (food < 16) { want.add("16+ food (" + food + " now)"); have.add(food + " food"); } else have.add(food + " food");
        int worn = 0, ironUp = 0; boolean goldOn = false;
        for (var sl : GT_SLOTS) { var s = c.getItemBySlot(sl); if (s.isEmpty()) continue; worn++; String t = gtTier(s.getItem()); if (t.equals("iron") || t.equals("diamond") || t.equals("netherite")) ironUp++; if (t.equals("golden")) goldOn = true; }
        if (worn == 0) must.add("no armor on"); else { have.add(wornWords(c)); if (ironUp + (goldOn ? 1 : 0) < 4) want.add("iron or better on every slot"); }
        boolean goldCarried = inv.hasAnyMatching(s -> s.getItem() instanceof net.minecraft.world.item.ArmorItem && gtTier(s.getItem()).equals("golden"));
        int ingots = c.inventory.count(s -> s.is(net.minecraft.world.item.Items.GOLD_INGOT));
        if (goldOn) have.add("a gold piece on (piglins leave you be)");
        else want.add(goldCarried ? "your gold piece put on (piglins attack without one)" : "a gold armor piece for the piglins (4 gold ingots make boots; " + ingots + " on you)");
        net.minecraft.world.item.Item weapon = null, pick = null;
        for (int i = 0; i < inv.getContainerSize(); i++) { var s = inv.getItem(i); if (s.isEmpty()) continue;
            if (weapon == null && (s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem)) weapon = s.getItem();
            if (pick == null && s.getItem() instanceof net.minecraft.world.item.PickaxeItem) pick = s.getItem(); }
        if (weapon == null) must.add("no sword or axe"); else have.add(gtNice(weapon));
        if (pick == null) must.add("no pickaxe (netherrack walls you in)"); else have.add(gtNice(pick));
        if (inv.hasAnyMatching(s -> s.is(net.minecraft.world.item.Items.FLINT_AND_STEEL))) have.add("flint and steel"); else must.add("no flint and steel (to light the portal again: a ghast's fireball puts it out)");
        int blocks = c.inventory.count(com.claudemod.entity.life.ClaudeInventory::isBuildingBlock);
        if (blocks == 0) must.add("no blocks to bridge or pillar with"); else if (blocks < 64) { want.add("64 blocks to bridge and pillar (" + blocks + " now)"); have.add(blocks + " blocks"); } else have.add(blocks + " blocks to bridge and pillar");
        int arrows = c.inventory.count(s -> s.is(net.minecraft.world.item.Items.ARROW));
        if (inv.hasAnyMatching(s -> s.is(net.minecraft.world.item.Items.BOW))) have.add("a bow and " + arrows + " arrows"); else want.add("a bow and arrows (wanted, not needed: ghasts across a gap, blazes from cover)");
        if (inv.hasAnyMatching(s -> s.is(net.minecraft.world.item.Items.SHIELD))) have.add("a shield"); else want.add("a shield");
        boolean empty = inv.hasAnyMatching(s -> s.is(net.minecraft.world.item.Items.BUCKET)), water = inv.hasAnyMatching(s -> s.is(net.minecraft.world.item.Items.WATER_BUCKET));
        if (empty) have.add("an empty bucket (fine for lava)");
        if (water) have.add("a water bucket (useless in the nether: water boils off there; emptied, it's a bucket for lava)");
        if (!empty && !water) opt.add("an empty bucket for lava");
        boolean fireRes = inv.hasAnyMatching(s -> { var pc = s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS); return pc != null && (pc.is(net.minecraft.world.item.alchemy.Potions.FIRE_RESISTANCE) || pc.is(net.minecraft.world.item.alchemy.Potions.LONG_FIRE_RESISTANCE)); });
        if (fireRes) have.add("fire resistance"); else opt.add("fire resistance (none)");
        try {
            var kit = new java.util.LinkedHashMap<>(Kit.missing(c, true));
            kit.keySet().removeIf(it -> it == net.minecraft.world.item.Items.BOW || it == net.minecraft.world.item.Items.ARROW || new net.minecraft.world.item.ItemStack(it).has(net.minecraft.core.component.DataComponents.FOOD) || new net.minecraft.world.item.ItemStack(it).is(net.minecraft.tags.ItemTags.BEDS));
            if (!kit.isEmpty()) want.add("the rest of your cave kit: " + Kit.describe(kit));
        } catch (RuntimeException ex) { ClaudeMod.LOGGER.warn("[Claude nether check] kit: {} (ClaudeChat.netherCheck)", ex.toString()); }
        return java.util.List.of(must, want, have, opt);
    }

    /** The nether check as a truth-block line. */
    static String netherTalk(ClaudeEntity c) {
        var k = netherCheck(c);
        return "NETHER READINESS (an expert's check of what's on you right now; answer \"ready?\" from this: blockers first, never say you're ready or have something that isn't listed under HAVE, and offer to get what's missing, e.g. \"not yet: no food, no gold. want me to get them?\"): "
            + (k.get(0).isEmpty() ? "READY: no blockers" : "NOT READY. BLOCKERS: " + String.join("; ", k.get(0)))
            + ". WANTED: " + (k.get(1).isEmpty() ? "nothing" : String.join("; ", k.get(1)))
            + ". HAVE: " + (k.get(2).isEmpty() ? "nothing for it" : String.join("; ", k.get(2)))
            + ". OPTIONAL: " + (k.get(3).isEmpty() ? "-" : String.join("; ", k.get(3))) + ".";
    }
}
