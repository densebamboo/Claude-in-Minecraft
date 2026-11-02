package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The skill {@code fight_dragon} and the End fight it starts (the user: bow and melee, the expert's way). In the Overworld: the kit
 * checked, back to the lit End portal, in. In the End ({@link Task.Dragon}, ticked every tick): off the spawn platform onto the
 * island, every End crystal shot (the caged ones: up a pillar beside their tower, the bars broken, down, then shot from the
 * ground), then the dragon: arrows while it flies, sword hits on its head while it perches on the fountain, out of its breath.
 */
public final class DragonFight {
    static final class St { final Set<UUID> opened = new HashSet<>(); String dim; UUID target; String doing = ""; int said; BlockPos vantage, base, cageBase; int pillarUp, vantageUp, sinceShot; boolean waterTried; }
    private static Map<UUID, St> sts; // (static: fields added live start null)
    static St st(ClaudeEntity g) { if (sts == null) sts = new HashMap<>(); return sts.computeIfAbsent(g.getUUID(), k -> new St()); }
    /** The fight back in his queue, once (an old copy dropped): at the front, or after what's queued. */
    public static void again(ClaudeEntity g, boolean front) { g.tasks.removeIf(t -> t instanceof Task.Dragon); if (front) g.tasks.addFirst(new Task.Dragon(20 * 60 * 30)); else g.tasks.add(new Task.Dragon(20 * 60 * 30)); }
    /** His End fight is on (started, and he's in the End). */
    public static boolean active(ClaudeEntity g) { return sts != null && sts.containsKey(g.getUUID()) && g.level().dimension() == Level.END; }
    public static void reset(ClaudeEntity g) { if (sts != null) sts.remove(g.getUUID()); }
    /** The fight is on (started, in the End, the dragon alive) and his queue is empty: pick it back up. */
    public static boolean resume(ClaudeEntity g) { if (sts == null || !sts.containsKey(g.getUUID()) || g.level().dimension() != Level.END) return false; var lv = (ServerLevel) g.level(); if (lv.getDragons().stream().noneMatch(EnderDragon::isAlive)) return false; if (g.tickCount % 100 == 0) ClaudeMod.LOGGER.info("[Claude dragon] the fight's still on and his queue was emptied: back to it (DragonFight.resume)"); return true; }

    static int count(ClaudeEntity g, java.util.function.Predicate<ItemStack> p) { return g.inventory.count(p); }
    static boolean block(ItemStack s) { return s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.END_STONE) || s.is(Items.NETHERRACK) || s.is(Items.DIRT) || s.is(Items.STONE); }

    // ------------------------------------------------------------------ the skill (Overworld): kit, the portal, in
    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        Skills.Step again = new Skills.Step("fight_dragon", st.target(), st.count(), st.where());
        if (level.dimension() == Level.END) { reset(g); again(g, false); return "in the End: the crystals first, then the dragon"; }
        if (level.dimension() != Level.OVERWORLD) return "failed: the End portal is in the Overworld (I'm in the " + level.dimension().location().getPath() + ")";
        // the kit (the expert's): a bow and arrows for the crystals and the dragon in flight, blocks to pillar, food, a sword
        int arrows = count(g, s -> s.is(Items.ARROW)), blocks = count(g, DragonFight::block), food = com.claudemod.director.Kit.food(g);
        boolean bow = count(g, s -> s.is(Items.BOW)) > 0, blade = count(g, s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem) > 0;
        List<String> miss = new ArrayList<>();
        if (!bow) miss.add("a bow"); if (arrows < 32) miss.add((32 - arrows) + " more arrows"); if (blocks < 128) miss.add((128 - blocks) + " more blocks to pillar"); if (food < 8) miss.add("food (" + food + " meals, 8 wanted)"); if (!blade) miss.add("a sword"); boolean stonePick = count(g, x -> x.is(Items.STONE_PICKAXE)) > 0; if (!stonePick) miss.add("a stone pickaxe (his pillars come down block by block: stone picks for rock)");
        if (!miss.isEmpty()) {
            ClaudeMod.LOGGER.info("[Claude dragon] the kit is short: {} (DragonFight.skill)", String.join(", ", miss));
            g.skillQueue.addFirst(again);
            if (!bow) { g.skillQueue.addFirst(new Skills.Step("obtain", "bow", 1)); return "packing for the dragon: a bow first"; }
            if (arrows < 32) { g.skillQueue.addFirst(new Skills.Step("obtain", "arrow", 64 - arrows)); return "packing for the dragon: arrows (" + arrows + " on me)"; }
            if (blocks < 128) { g.skillQueue.addFirst(new Skills.Step("obtain", "cobblestone", 128 - blocks)); return "packing for the dragon: blocks to pillar (" + blocks + " on me)"; }
            if (!stonePick) { g.skillQueue.addFirst(new Skills.Step("obtain", "stone_pickaxe", 1)); return "packing for the dragon: a stone pickaxe for the pillars"; }
            g.skillQueue.pollFirst(); return "failed: short for the dragon: " + String.join(", ", miss);
        }
        String armor = armorLine(g);
        // the portal: back to it (light_end_portal walks there; it lights it first if it isn't)
        BlockPos lit = PortalMap.endPortal(g);
        BlockPos at = lit == null ? null : nearestPortalBlock(level, g.blockPosition(), 8);
        if (at == null) {
            g.skillQueue.addFirst(again);
            String r = StrongholdRoom.skill(g, player, level, new Skills.Step("light_end_portal", "", 1));
            if (r.startsWith("failed")) { g.skillQueue.remove(again); return r; }
            if (!r.startsWith("done")) return r; // (its own step goes in front of this one: the dragon after it)
            g.skillQueue.remove(again); at = nearestPortalBlock(level, g.blockPosition(), 8);
            if (at == null) return "failed: at the End portal and no portal block in reach (" + r + ")";
        }
        reset(g); again(g, false);
        ClaudeMod.LOGGER.info("[Claude dragon] into the End portal at {}: {} arrows, {} blocks, {} meals, {} (DragonFight.skill)", at.toShortString(), arrows, blocks, food, armor);
        return "into the End: " + arrows + " arrows, " + blocks + " blocks, " + food + " meals, " + armor;
    }
    static String armorLine(ClaudeEntity g) {
        int dia = 0, iron = 0; for (var sl : net.minecraft.world.entity.EquipmentSlot.values()) if (sl.isArmor()) { var s = g.getItemBySlot(sl); String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); if (n.startsWith("diamond_") || n.startsWith("netherite_")) dia++; else if (n.startsWith("iron_")) iron++; }
        return dia == 4 ? "full diamond" : dia + " diamond, " + iron + " iron piece(s) on";
    }
    static BlockPos nearestPortalBlock(ServerLevel level, BlockPos c, int r) {
        BlockPos best = null; for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -4, -r), c.offset(r, 3, r))) if (level.getBlockState(q).is(Blocks.END_PORTAL) && (best == null || q.distSqr(c) < best.distSqr(c))) best = q.immutable();
        return best;
    }

    // ------------------------------------------------------------------ the fight (ticked by WorkGoal)
    static void say(ClaudeEntity g, St s, String doing, String detail) { if (!doing.equals(s.doing) || g.tickCount - s.said > 200) { s.doing = doing; s.said = g.tickCount; ClaudeMod.LOGGER.info("[Claude dragon] {}: {} (DragonFight)", doing, detail); } }
    static boolean sees(ClaudeEntity g, Vec3 from, Vec3 to) {
        if (g.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g)).getType() != HitResult.Type.MISS) return false;
        Vec3 d = to.subtract(from); int n = (int) Math.ceil(d.length() * 4); BlockPos last = null; // (iron bars: the thin panes let a sight line through their gaps, an arrow hits them; a cage blocks the shot)
        for (int k = 1; k < n; k++) { BlockPos q = BlockPos.containing(from.add(d.scale((double) k / n))); if (q.equals(last)) continue; last = q; if (g.level().getBlockState(q).is(Blocks.IRON_BARS)) return false; }
        return true;
    }

    /** One tick of the fight; true: it's over (the dragon dead, or nothing more he can do from here). */
    private static com.claudemod.entity.goals.WorkGoal wg; // (the goal ticking this fight: told of each draw)
    public static boolean tick(ClaudeEntity g, com.claudemod.entity.goals.WorkGoal goal, int ticks) { wg = goal;
        ServerLevel level = (ServerLevel) g.level(); St s = st(g);
        if (!level.dimension().location().getPath().equals(s.dim)) { String was = s.dim; s.dim = level.dimension().location().getPath(); s.vantage = null; s.base = null; s.cageBase = null; if (was != null) ClaudeMod.LOGGER.info("[Claude dragon] in the {} now: spots and pillars from the {} forgotten (DragonFight)", s.dim, was); } // (live: the Overworld frame spot carried into the End)
        if (level.dimension() != Level.END) { // at the portal: straight into it
            BlockPos p = nearestPortalBlock(level, g.blockPosition(), 6);
            if (p == null || ticks > 400) { int portal = 0, frames = 0, eyed = 0; for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-12, -6, -12), g.blockPosition().offset(12, 6, 12))) { var bs = level.getBlockState(q); if (bs.is(Blocks.END_PORTAL)) portal++; if (bs.is(Blocks.END_PORTAL_FRAME)) { frames++; if (bs.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)) eyed++; } }
                ClaudeMod.LOGGER.info("[Claude dragon] no End portal block within 6 of {} after {} ticks (within 12: {} portal blocks, {} frames, {} with eyes; in it now {}) (DragonFight.tick)", g.blockPosition().toShortString(), ticks, portal, frames, eyed, level.getBlockState(g.blockPosition()).getBlock().getName().getString()); return true; }
            { BlockPos under = BlockPos.containing(g.getX(), g.getY() - 0.2, g.getZ()); // on a frame by the portal already: straight in
              if (level.getBlockState(under).is(Blocks.END_PORTAL) || level.getBlockState(BlockPos.containing(g.getX(), g.getY() + 0.1, g.getZ())).is(Blocks.END_PORTAL)) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(under.getX() + 0.5, under.getY(), under.getZ() + 0.5, 1.0); return false; } // (over it already, half on the frame: on, not back)
              if (level.getBlockState(under).is(Blocks.END_PORTAL_FRAME)) for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(under.relative(d)).is(Blocks.END_PORTAL)) { BlockPos in = under.relative(d);
                  if (ticks % 10 == 0) ClaudeMod.LOGGER.info("[Claude dragon] stepping in: at {} on ground {} v {} (DragonFight)", String.format("%.2f %.2f %.2f", g.getX(), g.getY(), g.getZ()), g.onGround(), String.format("%.2f %.2f %.2f", g.getDeltaMovement().x, g.getDeltaMovement().y, g.getDeltaMovement().z));
                  float yaw = (float) (Math.toDegrees(Math.atan2(in.getZ() + 0.5 - g.getZ(), in.getX() + 0.5 - g.getX())) - 90.0); g.setYRot(yaw); g.yHeadRot = yaw; g.yBodyRot = yaw; g.getLookControl().setLookAt(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
                  g.getNavigation().stop(); g.getMoveControl().setWantedPosition(in.getX() + 0.5, in.getY(), in.getZ() + 0.5, 1.0); if (g.onGround() && ticks % 10 == 5) g.getJumpControl().jump(); /* (a hop off the frame, forward: over its lip into the portal) */ say(g, s, "into the portal", "off the frame at " + under.toShortString() + " into the End portal at " + in.toShortString()); return false; } }
            // up on the platform beside a frame (by its stairs: a cell the path reaches), onto that frame, then across into the portal
            if (s.base == null || !level.getBlockState(s.base).is(Blocks.END_PORTAL_FRAME) || ticks % 100 == 1) { s.base = null; s.vantage = null; double bd = Double.MAX_VALUE;
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, -1, -4), p.offset(4, 1, 4))) { if (!level.getBlockState(q).is(Blocks.END_PORTAL_FRAME)) continue; BlockPos up = q.above(); if (!level.getBlockState(up).getCollisionShape(level, up).isEmpty() || !level.getBlockState(up.above()).getCollisionShape(level, up.above()).isEmpty()) continue;
                    boolean byPortal = false; for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(q.relative(d)).is(Blocks.END_PORTAL)) byPortal = true; if (!byPortal) continue;
                    { var tp = g.getNavigation().createPath(up, 0); double dd = up.distSqr(g.blockPosition()); if (tp != null && tp.canReach() && dd < bd) { bd = dd; s.base = q.immutable(); s.vantage = up.immutable(); } } // (the frame's own top, if the path gets there)
                    for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = q.relative(d); if (level.getBlockState(n).is(Blocks.END_PORTAL_FRAME) || level.getBlockState(n).is(Blocks.END_PORTAL)) continue;
                        if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty() || level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty()) continue;
                        double dd = n.distSqr(g.blockPosition()); if (dd >= bd) continue; var path = g.getNavigation().createPath(n, 0); if (ticks % 100 == 1) ClaudeMod.LOGGER.info("[Claude dragon] beside the frame {}: {} over {}, path {} (DragonFight)", q.toShortString(), n.toShortString(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(n.below()).getBlock()).getPath(), path == null ? "none" : path.canReach() ? "reaches" : "short"); if (path == null || !path.canReach()) continue; bd = dd; s.base = q.immutable(); s.vantage = n.immutable(); } } }
            if (s.base == null) { say(g, s, "no way onto the portal", "no frame by the portal with a platform cell beside it that I can walk to"); return ticks > 400; }
            BlockPos frame = s.base, side = s.vantage; BlockPos into = p; for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(frame.relative(d)).is(Blocks.END_PORTAL)) into = frame.relative(d);
            boolean onFrame = level.getBlockState(BlockPos.containing(g.getX(), g.getY() - 0.2, g.getZ())).is(Blocks.END_PORTAL_FRAME);
            boolean atSide = Math.abs(g.getX() - (side.getX() + 0.5)) < 0.8 && Math.abs(g.getZ() - (side.getZ() + 0.5)) < 0.8 && Math.abs(g.getY() - side.getY()) < 0.6;
            if (!onFrame && !atSide) { if (g.getNavigation().isDone() || ticks % 40 == 1) g.getNavigation().moveTo(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, 1.0); say(g, s, "to the portal", "up to " + side.toShortString() + " beside the frame at " + frame.toShortString()); return false; }
            if (!onFrame) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(frame.getX() + 0.5, frame.getY() + 1, frame.getZ() + 0.5, 0.8); if (g.onGround()) g.getJumpControl().jump(); say(g, s, "onto the frame", "up onto the frame at " + frame.toShortString()); return false; }
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(into.getX() + 0.5, into.getY(), into.getZ() + 0.5, 1.0);
            say(g, s, "into the portal", "off the frame into the End portal at " + into.toShortString());
            return false;
        }
        var fight = level.getDragonFight();
        List<? extends EnderDragon> dragons = level.getDragons();
        EnderDragon dragon = dragons.stream().filter(EnderDragon::isAlive).findFirst().orElse(null);
        if (dragon == null && (fight == null || fight.hasPreviouslyKilledDragon())) { if (g.isUsingItem()) g.stopUsingItem(); ClaudeMod.LOGGER.info("[Claude dragon] the dragon is dead (DragonFight.tick)"); return true; }
        // breath: out of its cloud
        for (AreaEffectCloud c : level.getEntitiesOfClass(AreaEffectCloud.class, g.getBoundingBox().inflate(5), a -> a.getParticle().getType() == ParticleTypes.DRAGON_BREATH)) {
            if (c.position().distanceTo(g.position()) > c.getRadius() + 2.0) continue; // (not on him or about to be: no need to run)
            BlockPos to = outOf(level, g, c);
            if (g.isUsingItem()) g.stopUsingItem(); g.setSprinting(true);
            if (to != null) g.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.3);
            else if (g.onGround() && (upHisPillar(g, s, 4) || onColumn(level, g) || openDrop(level, g))) { // up high with an open side: off it (the clutch with water; else the safest drop the planner allows, else down his blocks), never up or down in place // up his pillar with the water bucket: off its side, out of the cloud in one move, the clutch landing him
                if (s.base == null) s.base = s.cageBase != null ? s.cageBase : columnFoot(level, g); s.cageBase = null; if (down(g, s, "the dragon's breath on him up his pillar")) return true; }
            else if (g.onGround() && onColumn(level, g)) { // up a column, no water to land in: two down his own blocks, never higher (up again stacks it)
                BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); again(g, true); g.tasks.addFirst(new Task.Break(f.below(2).immutable())); g.tasks.addFirst(new Task.Break(f.below().immutable()));
                say(g, s, "down out of the breath", "up a column at " + f.toShortString() + " with no water: two blocks down, under the cloud"); return true; }
            else if (g.onGround() && !headRoom(level, g)) { // nowhere to step and no room overhead, or up his own pillar already (up again stacks it): down two (his pillar's blocks), under the disc
                BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); BlockPos b1 = f.below(), b2 = f.below(2);
                if (level.getBlockState(b1).getDestroySpeed(level, b1) >= 0 && level.getBlockState(b1).getDestroySpeed(level, b1) < 10 && level.getBlockState(b2).getDestroySpeed(level, b2) >= 0 && level.getBlockState(b2).getDestroySpeed(level, b2) < 10 && !level.getBlockState(b2.below()).getCollisionShape(level, b2.below()).isEmpty()) {
                    again(g, true); g.tasks.addFirst(new Task.Break(b2.immutable())); g.tasks.addFirst(new Task.Break(b1.immutable())); if (s.cageBase != null && s.cageBase.getY() > b2.getY()) s.cageBase = null;
                    say(g, s, "down out of the breath", "nowhere to step and no room overhead at " + f.toShortString() + ": two blocks down, under the cloud"); return true; } }
            else if (count(g, DragonFight::block) > 0 && g.onGround() && !onColumn(level, g)) { // nowhere to step (a pocket): up two blocks, over its cloud (it's a disc half a block thick)
                again(g, true); if (s.base == null && s.cageBase == null) s.base = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); g.tasks.addFirst(new Task.Pillar(2)); /* (the escape's own pillar: noted, so he comes back down it) */
                say(g, s, "up out of the breath", "nowhere to step at " + g.blockPosition().toShortString() + ": two blocks up, over the cloud"); return true; }
            say(g, s, "out of the breath", "a dragon's breath cloud " + String.format("%.1f", c.distanceTo(g)) + " off, radius " + String.format("%.1f", c.getRadius()));
            return false;
        }
        // his clutch water first: an empty bucket and water in reach (the bow drawn at once left it behind, and the next fall had none)
        if (g.onGround() || g.isInWater()) { boolean empty = count(g, x -> x.is(Items.BUCKET)) > 0; /* (any empty bucket: a full one beside it is no reason to leave his water, live 03:48) */
            if (empty) { boolean near = false; for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-3, -2, -3), g.blockPosition().offset(3, 1, 3))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) && level.getFluidState(q).isSource()) { near = true; break; }
                if (near) { if (g.isUsingItem()) g.stopUsingItem(); g.getNavigation().stop(); com.claudemod.director.ClaudeLife.fillBucket(g, level); say(g, s, "my water back", "the clutch's water scooped up before anything else"); return false; }
                if (g.tickCount % 20 == 0 && g.level().dimension() == Level.END) { BlockPos w = null; // (the End has no water of its own: any source near is his clutch's, left behind by a breath escape)
                    for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-16, -24, -16), g.blockPosition().offset(16, 8, 16))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) && level.getFluidState(q).isSource() && (w == null || q.distSqr(g.blockPosition()) < w.distSqr(g.blockPosition()))) w = q.immutable();
                    if (w != null && !breathOn(g)) { again(g, true); g.tasks.removeIf(t -> t instanceof Task.GoTo || t instanceof Task.Pillar || t instanceof Task.Leap); g.tasks.addFirst(new Task.GoTo(w, 2.0)); say(g, s, "to my water", "an empty bucket and my clutch's water at " + w.toShortString() + ", " + (int) Math.sqrt(w.distSqr(g.blockPosition())) + " off"); return true; } } } }
        // hurt: eat (a meal on hand) before anything else
        if (g.getHealth() <= 10 && g.onGround() && g.getFoodData().getFoodLevel() < 20) { var food = com.claudemod.director.ClaudeLife.foodToEat(g, g.getHealth() <= 6);
            if (food != null) { if (g.isUsingItem()) g.stopUsingItem(); again(g, true); g.tasks.addFirst(new Task.Eat(food)); say(g, s, "eating", "health " + (int) g.getHealth()); return true; } }
        // off the spawn platform: onto the main island (end stone, toward its middle)
        double fromMid = Math.sqrt(g.getX() * g.getX() + g.getZ() * g.getZ());
        BlockPos under = BlockPos.containing(g.getX(), g.getY() - 0.5, g.getZ());
        boolean island = level.getBlockState(under).is(Blocks.END_STONE) || block(new ItemStack(level.getBlockState(under).getBlock().asItem())) && fromMid < 130 || fromMid < 90 && !level.getBlockState(under).getCollisionShape(level, under).isEmpty(); // (on anything solid over the main island: a tower's obsidian, a chest: live 03:51, up by a cage read as off the island, 10 refused walks a second) // (on end stone, or his own blocks on it)
        if (!island && g.onGround() && fromMid > 30) {
            if (!g.onGround()) return false;
            BlockPos land = islandEdge(level, g);
            if (land == null) { say(g, s, "no island", "no end stone toward the middle from " + g.blockPosition().toShortString()); return false; }
            again(g, true); g.tasks.addFirst(new Task.GoTo(land, 1.0));
            say(g, s, "onto the island", "from " + g.blockPosition().toShortString() + " to end stone at " + land.toShortString() + ", " + (int) Math.sqrt(land.distSqr(g.blockPosition())) + " off");
            return true;
        }
        // the crystals
        List<EndCrystal> crystals = level.getEntitiesOfClass(EndCrystal.class, new AABB(-80, 0, -80, 80, 200, 80), EndCrystal::isAlive);
        crystals.removeIf(c -> c.getY() < 60); // (the ones the respawn places on the fountain: not these)
        if (!crystals.isEmpty()) return crystals(g, level, s, crystals, ticks);
        // the dragon: off any pillar of his first (it's fought from the ground, by the fountain)
        if (dragon != null && g.onGround()) { BlockPos fe = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); BlockPos pb = s.cageBase != null ? s.cageBase : s.base;
            if (pb != null && fe.getY() - pb.getY() > 2 && Math.abs(fe.getX() - pb.getX()) <= 1 && Math.abs(fe.getZ() - pb.getZ()) <= 1) { s.base = pb; s.cageBase = null; return down(g, s, "the crystals all gone: the dragon's fought from the ground"); }
            if (pb != null) { s.base = null; s.cageBase = null; } }
        if (dragon != null) return dragon(g, level, s, dragon, ticks);
        return false;
    }

    /** The hitter is the dragon (its body, a part of it, its fireball or breath) and his End fight is on. */
    public static boolean dragonsHit(ClaudeEntity g, UUID who) { if (sts == null || !sts.containsKey(g.getUUID()) || g.level().dimension() != Level.END) return false; var e = ((ServerLevel) g.level()).getEntity(who); return e instanceof EnderDragon || e instanceof net.minecraft.world.entity.boss.EnderDragonPart || e instanceof net.minecraft.world.entity.projectile.DragonFireball || e instanceof AreaEffectCloud; }
    /** In the End, a breath cloud on him (within its radius and a block). */
    public static boolean breathOn(ClaudeEntity g) { if (g.level().dimension() != Level.END) return false; for (AreaEffectCloud c : g.level().getEntitiesOfClass(AreaEffectCloud.class, g.getBoundingBox().inflate(8), a -> a.getParticle().getType() == ParticleTypes.DRAGON_BREATH)) if (c.position().distanceTo(g.position()) <= c.getRadius() + 2.0) return true; return false; } // (a block wider than the cloud: he steers off it before he's in it, live 03:45 walked into two)
    static boolean upHisPillar(ClaudeEntity g, St s, int by) { BlockPos fe = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); for (BlockPos pb : new BlockPos[] {s.cageBase, s.base}) if (pb != null && fe.getY() - pb.getY() >= by && Math.abs(fe.getX() - pb.getX()) <= 1 && Math.abs(fe.getZ() - pb.getZ()) <= 1) return true; return false; }
    /** An open side to step off with 4+ under it (a pillar beside a tower, a ledge). */
    static boolean openDrop(ServerLevel level, ClaudeEntity g) { BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos sd = f.relative(d); if (!level.getBlockState(sd).getCollisionShape(level, sd).isEmpty() || !level.getBlockState(sd.above()).getCollisionShape(level, sd.above()).isEmpty()) continue; int k = 0; BlockPos q = sd.below(); while (k < 6 && level.getBlockState(q).getCollisionShape(level, q).isEmpty()) { q = q.below(); k++; } if (k >= 4) return true; } return false; }
    /** Standing on a one-block column (nothing beside the block under him): a pillar top, his or not. */
    static boolean onColumn(ServerLevel level, ClaudeEntity g) { BlockPos u = BlockPos.containing(g.getX(), g.getY() - 0.2, g.getZ()); if (level.getBlockState(u).getCollisionShape(level, u).isEmpty()) return false; for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = u.relative(d); if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty()) return false; } return true; }
    /** Where his column stands: the first cell over solid ground that isn't his cobblestone, down from his feet. */
    static BlockPos columnFoot(ServerLevel level, ClaudeEntity g) { BlockPos q = BlockPos.containing(g.getX(), g.getY() - 0.2, g.getZ()); while (q.getY() > level.getMinBuildHeight() + 1 && level.getBlockState(q).is(Blocks.COBBLESTONE)) q = q.below(); return q.above(); }
    static boolean headRoom(ServerLevel level, ClaudeEntity g) { BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); for (int k = 2; k <= 3; k++) if (!level.getBlockState(f.above(k)).getCollisionShape(level, f.above(k)).isEmpty()) return false; return true; }
    /** The nearest cell he can walk to, out of the cloud by a block and a half (searched over the ground round him, never "straight away" into rock or void). */
    static BlockPos outOf(ServerLevel level, ClaudeEntity g, AreaEffectCloud c) {
        BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); ArrayDeque<BlockPos> q = new ArrayDeque<>(); Set<Long> seen = new HashSet<>(); q.add(f); seen.add(f.asLong());
        while (!q.isEmpty()) { BlockPos p = q.poll();
            if (Vec3.atCenterOf(p).distanceTo(c.position()) > c.getRadius() + 1.5) return p;
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = p.relative(d).offset(0, dy, 0);
                if (n.distSqr(f) > 12 * 12 || !seen.add(n.asLong())) continue;
                if (level.getBlockState(n).getCollisionShape(level, n).isEmpty() && level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty() && !level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty()) q.add(n); } }
        return null;
    }

    /** The nearest end stone top toward the island's middle from where he stands (the walker bridges the gap). */
    static BlockPos islandEdge(ServerLevel level, ClaudeEntity g) {
        Vec3 d = new Vec3(-g.getX(), 0, -g.getZ()).normalize();
        for (int k = 1; k < 140; k++) { int x = (int) Math.floor(g.getX() + d.x * k), z = (int) Math.floor(g.getZ() + d.z * k);
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
            if (top.getY() > level.getMinBuildHeight() + 1 && level.getBlockState(top.below()).is(Blocks.END_STONE)) return top.offset((int) Math.round(d.x * 3), 0, (int) Math.round(d.z * 3)).atY(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, top.offset((int) Math.round(d.x * 3), 0, (int) Math.round(d.z * 3))).getY()); }
        return null;
    }

    /** A point of the crystal he sees from {@code eye} (its top shows over a tower's rim first), or null. */
    static Vec3 seenPoint(ClaudeEntity g, Vec3 eye, EndCrystal c) {
        AABB bb = c.getBoundingBox(); Vec3 m = bb.getCenter();
        for (Vec3 v : new Vec3[] {new Vec3(m.x, bb.maxY - 0.25, m.z), m}) if (sees(g, eye, v)) return v;
        return null;
    }
    /** Down his pillar to where it stands (each block under him broken, a step at a time), then on. */
    static boolean down(ClaudeEntity g, St s, String why) {
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); int n = feet.getY() - s.base.getY();
        ServerLevel lv = (ServerLevel) g.level();
        for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos side = feet.relative(d); // off its side, the water bucket under him on the way (the user: he clutches; the pillar stays)
            if (!lv.getBlockState(side).getCollisionShape(lv, side).isEmpty() || !lv.getBlockState(side.above()).getCollisionShape(lv, side.above()).isEmpty()) continue;
            var fall = com.claudemod.entity.nav.Leaps.drop(g, lv, feet, side, 0);
            if (!fall.ok()) continue;
            again(g, true); g.tasks.addFirst(new Task.Leap(feet, fall.land(), com.claudemod.entity.nav.Leaps.DROP, fall.down(), fall.hp())); s.base = null; // (the drop first, the fight straight after it: its first act is scooping the clutch water)
            say(g, s, "off my pillar", fall.down() + " down off its side at " + feet.toShortString() + " (" + fall.onto() + "), " + why); return true; }
        again(g, true); for (int k = n; k >= 1; k--) g.tasks.addFirst(new Task.Break(feet.below(k))); s.base = null; // (no water to land in: down his pillar block by block, first)
        say(g, s, "down my pillar", n + " blocks down from " + feet.toShortString() + ", " + why);
        return true;
    }

    static boolean caged(ServerLevel level, EndCrystal c) { for (BlockPos q : BlockPos.betweenClosed(c.blockPosition().offset(-2, -1, -2), c.blockPosition().offset(2, 3, 2))) if (level.getBlockState(q).is(Blocks.IRON_BARS)) return true; return false; }

    static boolean crystals(ClaudeEntity g, ServerLevel level, St s, List<EndCrystal> crystals, int ticks) {
        Vec3 eye = g.getEyePosition();
        EndCrystal shot = null;
        Vec3 shotAt = null;
        for (EndCrystal c : crystals) { if (c.distanceTo(g) > 72 || c.distanceTo(g) < 12) continue; Vec3 v = seenPoint(g, eye, c); if (v == null) continue; if (shot == null || c.distanceTo(g) < shot.distanceTo(g)) { shot = c; shotAt = v; } } // (12+ off: its blast reaches 12)
        if (shot != null && g.onGround()) {
            s.vantage = null;
            if (shoot(g, shotAt, Vec3.ZERO)) ClaudeMod.LOGGER.info("[Claude dragon] an arrow at the crystal at {}, {} off, {} left (DragonFight)", shot.blockPosition().toShortString(), (int) shot.distanceTo(g), crystals.size());
            say(g, s, "shooting the crystals", crystals.size() + " left; this one at " + shot.blockPosition().toShortString() + ", " + (int) shot.distanceTo(g) + " off");
            return false;
        }
        if (g.isUsingItem() && g.getUseItem().is(Items.BOW)) g.stopUsingItem();
        if (!g.onGround()) return false;
        EndCrystal c = crystals.stream().min(Comparator.comparingDouble((EndCrystal x) -> caged(level, x) ? 1 : 0).thenComparingDouble(x -> x.distanceToSqr(g))).orElse(null); // (the open ones first: a cage means a pillar under the dragon's fire)
        if (caged(level, c) && !s.opened.contains(c.getUUID())) { BlockPos fe = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
            if (s.cageBase != null && upHisPillar(g, s, 3) && Math.abs(s.cageBase.getX() - c.getBlockX()) + Math.abs(s.cageBase.getZ() - c.getBlockZ()) > 8) { s.base = s.cageBase; s.cageBase = null; } // (up the last cage's pillar: off it before the next tower)
            if (s.base != null && fe.getY() - s.base.getY() > 2) return down(g, s, "to the caged crystal at " + c.blockPosition().toShortString()); return cage(g, level, s, c); } // (off his shooting pillar first: a cage is climbed from its tower's foot) // (an opened cage: shot like the rest, from a spot in line with its gap)
        // none in sight: a spot on the island with a clear line to it, from the ground or a few blocks up a pillar there (a tall tower's rim hides its crystal from close by), the cheapest walk and climb
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (s.vantage == null || ticks % 600 == 0 || s.vantage.distSqr(g.blockPosition()) > 200 * 200) { // (the Overworld's frame spot carried into the End: live 03:43, "a clear shot from -859, 22, 1687")
            s.vantage = null; double bd = Double.MAX_VALUE;
            for (int r = 16; r <= 64; r += 6) for (int a = 0; a < 24; a++) { double ang = a * Math.PI / 12; int x = (int) Math.floor(c.getX() + Math.cos(ang) * r), z = (int) Math.floor(c.getZ() + Math.sin(ang) * r);
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z)); if (!level.getBlockState(top.below()).is(Blocks.END_STONE)) continue;
                for (int k = 0; k <= (s.opened.contains(c.getUUID()) ? 28 : 16); k += 2) { Vec3 e = new Vec3(top.getX() + 0.5, top.getY() + k + 1.62, top.getZ() + 0.5); if (e.distanceTo(c.position()) > 68 || seenPoint(g, e, c) == null) continue; /* (in bow range from there: the shot itself wants 72 or less) */
                    double cost = Math.sqrt(top.distSqr(feet)) + Math.abs(r - 28) * 1.2 + k * 4; /* (about 28 off: near enough to land the arrows, out of the blast) */ if (cost < bd) { bd = cost; s.vantage = top.immutable(); s.vantageUp = k; } break; } }
        }
        if (s.vantage == null) { say(g, s, "no clear shot", "the crystal at " + c.blockPosition().toShortString() + ": no spot on the island 16-64 off with a clear line to it, even 16 up"); return false; }
        if (s.base == null && s.cageBase != null && upHisPillar(g, s, 3)) { s.base = s.cageBase; s.cageBase = null; }
        int up = feet.getY() - (s.base == null ? feet.getY() : s.base.getY());
        boolean atVantage = Math.abs(feet.getX() - s.vantage.getX()) <= 1 && Math.abs(feet.getZ() - s.vantage.getZ()) <= 1;
        if (!atVantage && up > 2 && s.base != null) return down(g, s, "to a clear shot elsewhere");
        g.tasks.removeIf(t -> t instanceof Task.GoTo || t instanceof Task.Pillar || t instanceof Task.Leap); // (an old shot spot's walk and leap left queued: live 03:45, at the new spot, then 62 back to the old one and pillared there)
        if (!atVantage) { s.base = null; again(g, true); g.tasks.addFirst(new Task.GoTo(s.vantage, 0.8)); say(g, s, "to a clear shot", "the crystal at " + c.blockPosition().toShortString() + " from " + s.vantage.toShortString() + (s.vantageUp > 0 ? ", " + s.vantageUp + " up a pillar" : "")); return true; }
        if (s.base == null) s.base = feet.immutable();
        int need = s.vantageUp - (feet.getY() - s.base.getY());
        if (need > 0) { g.tasks.addFirst(new Task.Pillar(need)); again(g, false); say(g, s, "pillaring for a clear shot", need + " up at " + feet.toShortString() + " to see the crystal at " + c.blockPosition().toShortString()); return true; }
        s.vantage = null; return false; // (there and still no line: look again)
    }

    /**
     * A caged crystal (the user's pick): up a pillar beside its tower till his feet are two under the crystal (his whole body below
     * the blast's centre, the tower's rim between it and his legs), the bars on the line from his eyes broken, then one sword hit
     * from there. Full diamond and full health take the blast (a few hearts); if it throws him off, his water-bucket clutch lands him.
     */
    static boolean cage(ClaudeEntity g, ServerLevel level, St s, EndCrystal c) {
        BlockPos cb = c.blockPosition();
        BlockPos fe = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        int want = cb.getY() - 2; // his feet's height for the hit
        boolean onPillar = s.cageBase != null && Math.abs(fe.getX() - s.cageBase.getX()) <= 1 && Math.abs(fe.getZ() - s.cageBase.getZ()) <= 1 && fe.getY() > s.cageBase.getY();
        if (!onPillar && g.onGround() && fe.getY() >= cb.getY() - 4 && fe.getY() <= cb.getY() + 1 && Math.abs(fe.getX() - cb.getX()) <= 4 && Math.abs(fe.getZ() - cb.getZ()) <= 4) { s.cageBase = columnFoot(level, g); onPillar = true; } // (up by the cage already, his pillar's record lost: worked from where he stands, never back down to its foot; live 04:0x at -39,81,-1)
        if (onPillar && fe.getY() > want + 1) { for (int k = 1; k <= fe.getY() - want; k++) g.tasks.add(new Task.Break(fe.below(k))); again(g, false); say(g, s, "down to the hitting height", (fe.getY() - want) + " down to just under the cage at " + cb.toShortString()); return true; } // (above it: back down his pillar, not off it)
        if (onPillar && fe.getY() >= want) {
            Vec3 eye = g.getEyePosition(); AABB bb = c.getBoundingBox();
            Vec3 near = new Vec3(Math.max(bb.minX, Math.min(eye.x, bb.maxX)), Math.max(bb.minY, Math.min(eye.y, bb.maxY)), Math.max(bb.minZ, Math.min(eye.z, bb.maxZ)));
            double reach = eye.distanceTo(near);
            List<BlockPos> inWay = new ArrayList<>(); Vec3 d = near.subtract(eye); int n = (int) Math.ceil(d.length() * 6);
            for (int k = 1; k < n; k++) { BlockPos q = BlockPos.containing(eye.add(d.scale((double) k / n))); if (level.getBlockState(q).is(Blocks.IRON_BARS) && !inWay.contains(q)) inWay.add(q.immutable()); }
            if (!inWay.isEmpty()) { for (BlockPos b : inWay) g.tasks.add(new Task.Break(b)); again(g, false); say(g, s, "opening the cage", inWay.size() + " bar(s) between him and the crystal at " + cb.toShortString()); return true; }
            if (reach > 3.0) { say(g, s, "out of reach of the crystal", String.format("%.1f", reach) + " from his eyes to the crystal at " + cb.toShortString() + " (3 is his reach)"); s.cageBase = null; return false; }
            if (g.getHealth() < 18 && g.getFoodData().getFoodLevel() < 20) { var food = com.claudemod.director.ClaudeLife.foodToEat(g, false); if (food != null) { again(g, true); g.tasks.addFirst(new Task.Eat(food)); say(g, s, "eating before the blast", "health " + (int) g.getHealth()); return true; } }
            equipBlade(g); g.getLookControl().setLookAt(near.x, near.y, near.z);
            if (g.getAttackStrengthScale(0.5f) < 0.95f) return false;
            g.attack(c); g.swing(InteractionHand.MAIN_HAND);
            ClaudeMod.LOGGER.info("[Claude dragon] the caged crystal at {} hit from under its rim ({} off): {} (DragonFight.cage)", cb.toShortString(), String.format("%.1f", reach), c.isAlive() ? "still there" : "gone");
            return false;
        }
        if (onPillar) { int more = want - fe.getY(); g.tasks.add(new Task.Pillar(more)); again(g, false); say(g, s, "pillaring up", "on up " + more + " to just under the cage at " + cb.toShortString()); return true; }
        if (s.cageBase != null && fe.getY() > s.cageBase.getY() + 2) s.cageBase = null;
        // to the foot of its tower, then up
        BlockPos foot = null; double bd = Double.MAX_VALUE;
        for (int r = 2; r <= 8 && foot == null; r++) for (int a = 0; a < 16; a++) { double ang = a * Math.PI / 8; BlockPos col = new BlockPos((int) Math.floor(cb.getX() + 0.5 + Math.cos(ang) * r), 0, (int) Math.floor(cb.getZ() + 0.5 + Math.sin(ang) * r));
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, col); while (!level.getFluidState(top.below()).isEmpty() && top.getY() > level.getMinBuildHeight() + 2) top = top.below(); if (top.getY() > cb.getY() - 6 || !level.getBlockState(top.below()).is(Blocks.END_STONE)) continue;
            boolean beside = false; for (Direction dd : Direction.Plane.HORIZONTAL) if (level.getBlockState(top.relative(dd).above()).is(Blocks.OBSIDIAN)) beside = true; if (!beside) continue;
            double dd = top.distSqr(g.blockPosition()); if (dd < bd) { bd = dd; foot = top.immutable(); } }
        if (foot == null) { say(g, s, "no foot to the tower", "the caged crystal at " + cb.toShortString() + ": no end stone beside its tower"); return false; }
        if (foot.distSqr(fe) > 2) { again(g, true); g.tasks.addFirst(new Task.GoTo(foot, 0.4)); say(g, s, "to the caged crystal's tower", "its foot at " + foot.toShortString()); return true; }
        if (g.getHealth() < 16 && g.getFoodData().getFoodLevel() < 20) { var food = com.claudemod.director.ClaudeLife.foodToEat(g, false); if (food != null) { again(g, true); g.tasks.addFirst(new Task.Eat(food)); say(g, s, "eating before the climb", "health " + (int) g.getHealth()); return true; } }
        if (g.isUsingItem()) g.stopUsingItem();
        s.cageBase = foot;
        int up = want - foot.getY();
        g.tasks.add(new Task.Pillar(up)); again(g, false);
        say(g, s, "pillaring up", up + " blocks beside the tower to just under the cage at " + cb.toShortString());
        return true;
    }

    static boolean dragon(ClaudeEntity g, ServerLevel level, St s, EnderDragon d, int ticks) {
        var phase = d.getPhaseManager().getCurrentPhase();
        var ph = phase.getPhase();
        boolean landing = ph == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.LANDING; // (its approach is flight: arrows, not a wait by the fountain; live 03:59 the approach lasted 45 s+ and he stood with his sword out)
        Vec3 head = d.head.getBoundingBox().getCenter();
        BlockPos mid = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
        if (phase.isSitting() || landing) { // on the fountain (or coming down onto it): its head with the sword, crits between (arrows bounce off a perched dragon)
            if (g.isUsingItem()) g.stopUsingItem();
            equipBlade(g);
            Vec3 at = phase.isSitting() ? head : new Vec3(mid.getX() + 0.5, mid.getY() + 1, mid.getZ() + 0.5);
            net.minecraft.world.entity.Entity hit = null; double best = 3.0 * 3.0;
            if (phase.isSitting()) for (var part : d.getSubEntities()) { double dd = part.getBoundingBox().distanceToSqr(g.getEyePosition()); if (part == d.head ? dd <= best : dd < best - 1.0 && d.head.getBoundingBox().distanceToSqr(g.getEyePosition()) > 9.0) { if (part == d.head || hit == null) { hit = part; best = dd; } } }
            g.getLookControl().setLookAt(at.x, at.y, at.z);
            if (hit == null) { // to it: beside the head, out of the fountain's bedrock
                Vec3 off = g.position().subtract(at).multiply(1, 0, 1); if (off.lengthSqr() < 0.01) off = new Vec3(1, 0, 0);
                Vec3 to = at.add(off.normalize().scale(phase.isSitting() ? 3.0 : 6.0)); /* (3 off its head's middle: out of its touch, the head in his reach) */
                double far = Math.sqrt(g.position().distanceToSqr(to.x, g.getY(), to.z));
                if (far > 1.0) { if (far > 12) g.getNavigation().moveTo(to.x, g.getY(), to.z, 1.3); else { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(to.x, g.getY(), to.z, 1.3); } g.setSprinting(true); }
                say(g, s, phase.isSitting() ? "to its head" : "to the fountain", (phase.isSitting() ? "the dragon perched, its head " : "the dragon coming down, the fountain ") + String.format("%.1f", Math.sqrt(g.position().distanceToSqr(at))) + " off");
                return false;
            }
            g.getNavigation().stop(); g.setSprinting(false);
            double hd = Math.sqrt(d.head.getBoundingBox().distanceToSqr(g.getEyePosition())), nk = Math.sqrt(d.getSubEntities()[1].getBoundingBox().distanceToSqr(g.getEyePosition()));
            if (Math.min(hd, nk) < 1.8) { Vec3 back = g.position().subtract(head).multiply(1, 0, 1); if (back.lengthSqr() < 0.01) back = new Vec3(1, 0, 0); Vec3 to = g.position().add(back.normalize().scale(1.2)); g.getMoveControl().setWantedPosition(to.x, g.getY(), to.z, 1.0); } // (its head and neck hurt what's within a block of them: kept 1.8+ off, in his reach of 3)
            boolean charged = g.getAttackStrengthScale(0.5f) >= 0.95f;
            g.setSprinting(false); // (a sprinting hit is never a crit)
            if (charged) { // (no jump for a crit: a crit's bonus never reaches the dragon, its parts aren't living things; the swing on the charge is the fastest damage)
                float hp = d.getHealth(); g.attack(hit); g.swing(InteractionHand.MAIN_HAND);
                ClaudeMod.LOGGER.info("[Claude dragon] a {}hit on its {}: {} -> {} (DragonFight)", !g.onGround() && g.fallDistance > 0 ? "crit " : "", hit == d.head ? "head" : "body", hp, d.getHealth());
            }
            say(g, s, "hitting it", "the dragon perched, health " + (int) d.getHealth());
            return false;
        }
        // flying: arrows when it's in range and in sight; else waiting near the fountain (it comes down there), out of its path
        double dist = d.distanceTo(g);
        if (dist <= 56 && sees(g, g.getEyePosition(), head) && g.onGround()) {
            if (shoot(g, head, d.getDeltaMovement())) ClaudeMod.LOGGER.info("[Claude dragon] an arrow at the dragon, {} off, health {} (DragonFight)", (int) dist, (int) d.getHealth());
            say(g, s, "shooting the dragon", "flying, " + (int) dist + " off, health " + (int) d.getHealth());
            return false;
        }
        if (g.isUsingItem() && g.getUseItem().is(Items.BOW)) g.stopUsingItem();
        double fromMid = Math.sqrt((g.getX() - mid.getX()) * (g.getX() - mid.getX()) + (g.getZ() - mid.getZ()) * (g.getZ() - mid.getZ()));
        if ((fromMid > 12 || fromMid < 4) && g.onGround()) { Vec3 dir = new Vec3(g.getX() - mid.getX(), 0, g.getZ() - mid.getZ()); if (dir.lengthSqr() < 0.01) dir = new Vec3(1, 0, 0); Vec3 to = dir.normalize().scale(8).add(mid.getX(), 0, mid.getZ()); BlockPos t = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(to.x, 0, to.z)); again(g, true); g.tasks.addFirst(new Task.GoTo(t, 1.5)); say(g, s, "near the fountain", "waiting 8 off it for the dragon to come down"); return true; }
        say(g, s, "waiting", "the dragon " + (int) dist + " off, out of a clear shot");
        return false;
    }

    static void equipBlade(ClaudeEntity g) {
        ItemStack best = ItemStack.EMPTY; float bd = 0;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack x = g.inventory.items.getItem(i); if (!(x.getItem() instanceof net.minecraft.world.item.SwordItem)) continue; float dmg = x.is(Items.NETHERITE_SWORD) ? 8 : x.is(Items.DIAMOND_SWORD) ? 7 : x.is(Items.IRON_SWORD) ? 6 : 5; if (dmg > bd) { bd = dmg; best = x; } }
        if (!best.isEmpty() && g.getMainHandItem() != best) g.hold(best);
    }

    // ------------------------------------------------------------------ the bow, aimed like an expert: the arrow's own fall worked out
    /** One tick of a shot at {@code target} (moving at {@code vel} a tick): drawn, aimed with the drop and the lead, loosed at full draw. True on the loose. */
    static boolean shoot(ClaudeEntity g, Vec3 target, Vec3 vel) {
        if (count(g, s -> s.is(Items.ARROW)) == 0 || count(g, s -> s.is(Items.BOW)) == 0) return false;
        g.getNavigation().stop(); g.setSprinting(false);
        if (wg != null) wg.bowAimed();
        boolean drawing = g.isUsingItem() && g.getUseItem().is(Items.BOW);
        if (!drawing) { g.hold(g.inventory.find(x -> x.is(Items.BOW))); g.startUsingItem(InteractionHand.MAIN_HAND); return false; }
        Vec3 from = g.getEyePosition().add(0, -0.1, 0);
        float[] yp = aim(from, target, vel);
        if (g.tickCount % 20 == 0) ClaudeMod.LOGGER.info("[Claude dragon] drawing: {} ticks, aim {} at {} (DragonFight.shoot)", g.getTicksUsingItem(), yp == null ? "none" : (int) yp[0] + "/" + (int) yp[1], BlockPos.containing(target).toShortString());
        if (yp == null) { g.stopUsingItem(); return false; }
        g.setYRot(yp[0]); g.setXRot(yp[1]); g.yHeadRot = yp[0]; g.yBodyRot = yp[0];
        if (g.getTicksUsingItem() >= 20) { g.releaseUsingItem(); g.swing(InteractionHand.MAIN_HAND); return true; }
        return false;
    }
    /** Yaw and pitch (degrees, Minecraft's: pitch up is negative) for a full-draw arrow from {@code from} to hit {@code target} moving at {@code vel}; null: out of its range. */
    public static float[] aim(Vec3 from, Vec3 target, Vec3 vel) {
        Vec3 t = target;
        for (int it = 0; it < 3; it++) { // the lead: where it'll be when the arrow gets there
            double dx = t.x - from.x, dz = t.z - from.z, h = Math.sqrt(dx * dx + dz * dz), dy = t.y - from.y;
            double lo = Double.NaN, hi = Double.NaN, ok = Double.NaN; int ticksAt = 0;
            for (double a = -80; a <= 85; a += 1) { double[] r = fly(Math.toRadians(a), h); if (!Double.isNaN(r[0]) && r[0] >= dy) { hi = a; lo = a - 1; break; } } // (the flattest arc that gets there: scanned up, then narrowed)
            if (Double.isNaN(hi)) return null;
            for (int i = 0; i < 20; i++) { double mid = (lo + hi) / 2; double[] r = fly(Math.toRadians(mid), h); if (Double.isNaN(r[0]) || r[0] < dy) lo = mid; else hi = mid; }
            ok = hi; ticksAt = (int) fly(Math.toRadians(ok), h)[1];
            if (it == 2 || vel.lengthSqr() < 1e-6) { float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0); return new float[] {yaw, (float) -ok}; }
            t = target.add(vel.scale(ticksAt));
        }
        return null;
    }
    /** The arrow's height at horizontal distance h, launched at {@code pitch} (radians, up positive) at 3 blocks a tick, and the ticks it took; NaN: it never gets that far. */
    static double[] fly(double pitch, double h) {
        double vx = 3.0 * Math.cos(pitch), vy = 3.0 * Math.sin(pitch), x = 0, y = 0;
        for (int t = 1; t <= 300; t++) { double nx = x + vx, ny = y + vy; if (nx >= h) { double f = (h - x) / (nx - x); return new double[] {y + (ny - y) * f, t}; } x = nx; y = ny; vx *= 0.99; vy = vy * 0.99 - 0.05; if (vx < 1e-3) break; }
        return new double[] {Double.NaN, 0};
    }
}
