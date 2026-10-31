package com.claudemod.entity.nav;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.Task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drops and jumps, as a player's body does them and an AI's mind works them out (the user, 2026-10-30 16:42 and 16:48: "he
 * should be able to calculate fall damage and assess risk", "he should get better at jumping across platforms and parkour,
 * and use it in his pathing").
 *
 * <p>The fall: a tick-by-tick run of vanilla's own motion (y += vy, then vy = (vy - 0.08) * 0.98), the fall distance counted
 * the way Entity.checkFallDamage counts it (only downward moves; reset in water, a cobweb, powder snow, on a ladder, vine or
 * scaffolding), the landing block's own fallOn (hay and honey 0.2, a bed half the distance, slime none), then
 * LivingEntity.calculateFallDamage (ceil((distance - safe_fall_distance) * multiplier * fall_damage_multiplier)), Resistance,
 * and Feather Falling / Protection through EnchantmentHelper.getDamageProtection and CombatRules.getDamageAfterMagicAbsorb,
 * the very calls the hit goes through. Taken when what's left keeps a margin: 6 HP, 4 more with a hostile about, 2 more with
 * no food on him. Never onto lava, fire, magma, a cactus or dripstone, and never onto a landing he can't see from the lip.
 *
 * <p>The jump: vanilla's player motion (ground: v = (v + speed * 0.98) * 0.546; the jump tick +0.42 up and, sprinting, +0.2
 * along his facing; air: v = (v + flying_speed * 0.98) * 0.91, flying speed 0.02, sprinting 0.026). Run from the middle of
 * the lip cell, a sprint-jump lands 3.4 blocks on, 2.6 one up, 4.0 one down; a walk-jump 1.9, 1.3 one up. With his box half
 * a 0.3 past each edge, that's a walk-jump over 1 (flat, up one, down), a sprint-jump over 2 (up one), 3 (flat, down) and 4
 * (down). He takes off on the last tick his box still stands on the lip and steers in the air toward the landing's middle.
 */
public final class Leaps {
    private Leaps() {}

    public static final int DROP = 0, WALK_JUMP = 1, SPRINT_JUMP = 2;
    public static String kindName(int k) { return k == DROP ? "drop" : k == WALK_JUMP ? "jump" : "sprint-jump"; }

    /** A fall worked out: where he ends up (his feet), how far, what it costs, what took it, and why not (null: fine). */
    public record Fall(BlockPos land, int down, float hp, String onto, String why, double extraSec) {
        public boolean ok() { return why == null; }
    }

    // ------------------------------------------------------------------------------------------------ fall damage

    /** What a fall of {@code dist} (vanilla's fall distance) onto {@code on} (the block under his feet; null: none, e.g. water) costs him, in HP. */
    public static float damage(ClaudeEntity g, ServerLevel level, float dist, BlockState on) {
        float mult = 1f;
        if (on != null) {
            if (on.is(Blocks.HAY_BLOCK) || on.is(Blocks.HONEY_BLOCK)) mult = 0.2f; // HayBlock / HoneyBlock.fallOn
            else if (on.is(BlockTags.BEDS)) dist *= 0.5f; // BedBlock.fallOn: half the distance
            else if (on.is(Blocks.SLIME_BLOCK) && !g.isShiftKeyDown()) return 0; // SlimeBlock.fallOn: none unless sneaking
        }
        double safe = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE);
        double fm = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FALL_DAMAGE_MULTIPLIER);
        int raw = Mth.ceil((dist - safe) * mult * fm - 1e-3); // LivingEntity.calculateFallDamage (less a hair: the tick sum of a 7-block fall is 7.0000005 here, 7 in the game's own float sum)
        if (raw <= 0) return 0;
        float d = raw;
        var res = g.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
        if (res != null) d = Math.max(d * (25 - (res.getAmplifier() + 1) * 5) / 25f, 0f); // LivingEntity.getDamageAfterMagicAbsorb
        if (d <= 0) return 0;
        var src = level.damageSources().fall();
        float prot = net.minecraft.world.item.enchantment.EnchantmentHelper.getDamageProtection(level, g, src); // Feather Falling 3/level, Protection 1/level
        if (prot > 0) d = net.minecraft.world.damagesource.CombatRules.getDamageAfterMagicAbsorb(d, prot);
        return d;
    }

    /** His health with absorption. */
    public static float health(ClaudeEntity g) { return g.getHealth() + g.getAbsorptionAmount(); }

    /** The HP he keeps after a drop: 6, +4 with a hostile about (seen, or within 8), +2 with no food on him. */
    public static float margin(ClaudeEntity g) { return 6 + (hostileAbout(g) ? 4 : 0) + (foodOnHim(g) ? 0 : 2); }
    public static String marginWhy(ClaudeEntity g) { return "keep " + (int) margin(g) + (hostileAbout(g) ? ", a hostile about" : "") + (foodOnHim(g) ? "" : ", no food on me"); }
    static boolean foodOnHim(ClaudeEntity g) { return g.inventory.count(s -> s.has(net.minecraft.core.component.DataComponents.FOOD)) > 0; }
    static boolean hostileAbout(ClaudeEntity g) {
        for (var m : g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(16), x -> x.isAlive())) {
            if (m instanceof net.minecraft.world.entity.NeutralMob nm && !nm.isAngry()) continue; // (a zombified piglin minding its own business)
            if (m.distanceTo(g) < 8 || g.hasLineOfSight(m)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ the fall, tick by tick

    static boolean solid(ServerLevel l, BlockPos p) { return !l.getBlockState(p).getCollisionShape(l, p).isEmpty() && !l.getBlockState(p).is(BlockTags.CLIMBABLE) && !l.getBlockState(p).is(Blocks.POWDER_SNOW); }
    static boolean water(ServerLevel l, BlockPos p) { return l.getFluidState(p).is(FluidTags.WATER); }
    static boolean lava(ServerLevel l, BlockPos p) { return l.getFluidState(p).is(FluidTags.LAVA); }

    /**
     * Walking (vy0 0) or jumping (vy0 0.42) off into the column whose top cell is {@code col}, his feet at {@code y0}: where he
     * lands and what it costs. Up to 64 down; past that, "no bottom in sight".
     */
    public static Fall fall(ClaudeEntity g, ServerLevel l, BlockPos col, double y0, double vy0) {
        double y = y0, vy = vy0; float fd = 0; double extra = 0; int x = col.getX(), z = col.getZ();
        int floorY = Math.max(l.getMinBuildHeight(), Mth.floor(y0) - 64);
        for (int t = 0; t < 400; t++) {
            BlockPos at = new BlockPos(x, Mth.floor(y + 1e-6), z);
            BlockState s = l.getBlockState(at);
            if (s.is(BlockTags.CLIMBABLE) && !s.is(Blocks.SCAFFOLDING)) { fd = 0; vy = Math.max(vy, -0.15); } // LivingEntity.handleOnClimbable: in a ladder or vine the fall is forgotten, he slides (scaffolding's top catches a faller not sneaking: an ordinary landing)
            double ny = y + vy;
            // the cells this tick's move passes through, top down: what stops him
            for (int cy = Mth.floor(y + 1e-6); cy >= Mth.floor(ny); cy--) {
                BlockPos c = new BlockPos(x, cy, z);
                if (!l.isLoaded(c)) return new Fall(c, (int) Math.round(y0 - cy), 0, "?", "the landing isn't loaded", 0);
                BlockState cs = l.getBlockState(c);
                if (lava(l, c)) return new Fall(c, (int) Math.round(y0 - cy), 99, "lava", "lava at " + c.toShortString(), 0);
                if (cs.is(BlockTags.FIRE)) return new Fall(c, (int) Math.round(y0 - cy), 99, "fire", "fire at " + c.toShortString(), 0);
                if (water(l, c) && cy + 0.9 > ny) { // Entity.checkFallDamage: in water the fall is forgotten (one block is enough)
                    return new Fall(c, (int) Math.round(y0 - cy), 0, "water", null, extra);
                }
                if (cs.is(Blocks.COBWEB)) { fd = 0; extra += 12; y = cy; vy = 0; ny = cy - 0.01; break; } // Entity.makeStuckInBlock: the fall is forgotten, then the slow sink through it
                if (cs.is(Blocks.POWDER_SNOW)) return new Fall(c, (int) Math.round(y0 - cy), 0, "powder snow", null, extra); // (fallDistance > 2.5: its falling shape catches him, fallOn does no damage)
                var sh = cs.getCollisionShape(l, c);
                boolean scaff = cs.is(Blocks.SCAFFOLDING); // (its collision box is his own: from above, not sneaking, a full top)
                if (!sh.isEmpty() && !cs.is(BlockTags.CLIMBABLE) || scaff) {
                    double top = cy + (scaff ? 1.0 : sh.max(Direction.Axis.Y));
                    if (top <= y + 1e-6 && top >= ny - 1e-6) {
                        fd += (float) Math.max(0, y - top);
                        BlockPos feet = top >= cy + 1 - 1e-6 ? c.above() : c;
                        BlockState on = cs;
                        String onto = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(on.getBlock()).getPath().replace('_', ' ');
                        if (on.is(Blocks.MAGMA_BLOCK) || on.is(BlockTags.CAMPFIRES) || on.is(Blocks.CACTUS) || on.is(Blocks.POINTED_DRIPSTONE) || on.is(Blocks.SWEET_BERRY_BUSH))
                            return new Fall(feet, (int) Math.round(y0 - feet.getY()), 99, onto, onto + " to land on at " + c.toShortString(), extra);
                        if (lava(l, feet) || l.getBlockState(feet).is(BlockTags.FIRE)) return new Fall(feet, (int) Math.round(y0 - feet.getY()), 99, "lava", "lava where he'd land, " + feet.toShortString(), extra);
                        return new Fall(feet, (int) Math.round(y0 - feet.getY()), damage(g, l, fd, on), onto, null, extra);
                    }
                }
            }
            if (ny < y) fd += (float) (y - ny);
            y = ny; vy = (vy - 0.08) * 0.98;
            if (y < floorY) return new Fall(new BlockPos(x, floorY, z), (int) Math.round(y0 - floorY), 99, "?", "no bottom within 64 blocks", extra);
        }
        return new Fall(col, 0, 99, "?", "no bottom", extra);
    }

    /** The drop off the lip {@code lip} (his feet cell) into {@code col} (the open cell beside it, at his feet's level): the fall, with the margin and the look at the landing. */
    public static Fall drop(ClaudeEntity g, ServerLevel l, BlockPos lip, BlockPos col, float hurtSoFar) {
        Fall f = fall(g, l, col, col.getY(), 0);
        if (!f.ok()) return f;
        if (l.getBlockState(f.land()).is(Blocks.POWDER_SNOW)) return new Fall(f.land(), f.down(), f.hp(), f.onto(), "powder snow where he'd land (no damage, but it freezes him and holds him in)", f.extraSec());
        float left = health(g) - hurtSoFar - f.hp();
        if (left < margin(g)) return new Fall(f.land(), f.down(), f.hp(), f.onto(), (hurtSoFar > 0 ? (int) (health(g) - hurtSoFar) + " HP left after the drops before it, " : "") + (int) Math.max(0, left) + " after: under the " + (int) margin(g) + " I keep", f.extraSec());
        if (!seesOver(l, lip, col, f.land())) return new Fall(f.land(), f.down(), f.hp(), f.onto(), "he can't see the landing from the lip", f.extraSec());
        return f;
    }

    /** From the lip, leaning over its edge (his centre 0.3 past it, still standing on it): the landing's top in plain view. */
    public static boolean seesOver(ServerLevel l, BlockPos lip, BlockPos col, BlockPos land) {
        Vec3 eye = Vec3.atBottomCenterOf(lip).add((col.getX() - lip.getX()) * 0.78, 1.62, (col.getZ() - lip.getZ()) * 0.78);
        return com.claudemod.entity.life.Perception.clear(l, eye, new Vec3(land.getX() + 0.5, land.getY() + 0.05, land.getZ() + 0.5));
    }

    /** "7 down is 4 HP" / "20 down into water is 0 HP". */
    public static String cost(Fall f) { if (f.hp() >= 99) return f.down() + " down onto " + f.onto(); return f.down() + " down" + (f.onto().equals("water") || f.onto().equals("powder snow") || f.onto().contains("hay") || f.onto().contains("slime") || f.onto().contains("bed") || f.onto().contains("honey") ? " into " + f.onto() : "") + " is " + fmt(f.hp()) + " HP"; }
    public static String fmt(float hp) { return hp == Math.round(hp) ? String.valueOf((int) hp) : String.format("%.1f", hp); }
    /** The cost line he says: "7 down is 4 HP, I'm at 20: jumping". */
    public static String sayLine(ClaudeEntity g, Fall f) { return cost(f) + ", I'm at " + fmt(health(g)) + (f.ok() ? ": jumping" : ": not jumping (" + f.why() + ")"); }

    // ------------------------------------------------------------------------------------------------ the jump

    /** Ground speed per tick added (attribute * 0.98), air speed, by sprint. */
    static double groundAcc(boolean sprint) { return (sprint ? 0.13 : 0.1) * 0.98; }
    static double airAcc(boolean sprint) { return (sprint ? 0.026 : 0.02) * 0.98; }

    /** Horizontal distance his centre covers from the jump tick until he comes down through {@code dy} (relative to take-off), run up for {@code run} ticks from rest. */
    public static double reach(boolean sprint, int run, int dy) {
        double v = 0;
        for (int i = 0; i < run; i++) { v += groundAcc(sprint); v *= 0.546; }
        double x = 0, y = 0, vy = 0.42;
        if (sprint) v += 0.2;
        v += groundAcc(sprint); x += v; y += vy; v *= 0.546; vy = (vy - 0.08) * 0.98;
        for (int t = 0; t < 60; t++) { v += airAcc(sprint); double ny = y + vy; x += v; v *= 0.91; boolean down = vy < 0; vy = (vy - 0.08) * 0.98; if (down && ny <= dy && y > dy) return x; y = ny; }
        return 0;
    }

    /** The jump for a gap of {@code gap} cells to a landing {@code dy} up/down, or -1: none a player makes reliably. */
    public static int jumpKind(int gap, int dy, boolean canSprint) {
        if (gap == 1 && dy <= 1 && dy >= -3) return WALK_JUMP;
        if (!canSprint) return -1;
        if (gap == 2 && dy <= 1 && dy >= -3) return SPRINT_JUMP;
        if (gap == 3 && dy <= 0 && dy >= -3) return SPRINT_JUMP;
        if (gap == 4 && dy <= -1 && dy >= -3) return SPRINT_JUMP;
        return -1;
    }
    /** The chance he misses it (the run, the take-off tick and the air steering are exact; what's left is a mob's hit, a lag spike). */
    public static double missRisk(int gap, int dy) {
        if (gap == 1) return 0.02;
        if (gap == 2) return dy > 0 ? 0.08 : 0.04;
        if (gap == 3) return dy == 0 ? 0.08 : 0.05;
        return 0.10;
    }

    // ------------------------------------------------------------------------------------------------ the body doing it

    /** What his hands do this tick while a leap runs (Controls.apply reads it in place of the walk). */
    public record Ctl(Vec3 walk, double scale, boolean sprint, boolean jump, int tick) {}
    private static final Map<UUID, Ctl> CTL = new HashMap<>();
    public static Ctl control(Player p) { Ctl c = CTL.get(p.getUUID()); return c != null && c.tick == p.tickCount ? c : null; }
    static void hands(ClaudeEntity g, Vec3 walk, double scale, boolean sprint, boolean jump) { CTL.put(g.getUUID(), new Ctl(walk, scale, sprint, jump, g.tickCount)); }

    private static final class St { Task.Leap task; int phase, t0, ground, takeoff = -1; float hp0; String plan = ""; }
    private static final Map<UUID, St> RUN = new HashMap<>();
    public static boolean running(ClaudeEntity g, Task.Leap t) { St s = RUN.get(g.getUUID()); return s != null && s.task == t && s.phase >= 1; }

    /** The last drop or jump he turned down, and when (the chat says it with the number). */
    private static final Map<UUID, Object[]> REFUSED = new HashMap<>();
    public static void refusedNote(ClaudeEntity g, String why) { REFUSED.put(g.getUUID(), new Object[] {why, g.tickCount}); }
    public static String lastRefusal(ClaudeEntity g, int within) { Object[] o = REFUSED.get(g.getUUID()); return o != null && g.tickCount - (int) o[1] <= within ? (String) o[0] : null; }
    public static void reset(ClaudeEntity g) { RUN.remove(g.getUUID()); REFUSED.remove(g.getUUID()); CTL.remove(g.getUUID()); }

    static Direction dirOf(Task.Leap t) { return Direction.getNearest(t.land().getX() - t.from().getX(), 0, t.land().getZ() - t.from().getZ()); }
    static int gapOf(Task.Leap t) { Direction d = dirOf(t); return Math.abs((t.land().getX() - t.from().getX()) * d.getStepX() + (t.land().getZ() - t.from().getZ()) * d.getStepZ()) - 1; }

    /** One tick of the leap: null while it runs, else how it ended (logged). His feet must be on the lip cell (WorkGoal walks him there first). */
    public static String tick(ClaudeEntity g, ServerLevel l, Task.Leap t) {
        St s = RUN.get(g.getUUID());
        Direction d = dirOf(t); Vec3 dir = new Vec3(d.getStepX(), 0, d.getStepZ());
        Vec3 c0 = Vec3.atBottomCenterOf(t.from());
        double along = (g.getX() - c0.x) * dir.x + (g.getZ() - c0.z) * dir.z, lat = (g.getX() - c0.x) * dir.z - (g.getZ() - c0.z) * dir.x;
        Vec3 side = new Vec3(dir.z, 0, -dir.x);
        if (s == null || s.task != t) {
            s = new St(); s.task = t; s.t0 = g.tickCount; s.hp0 = health(g); RUN.put(g.getUUID(), s);
            BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
            String why = null;
            if (!feet.equals(t.from())) why = "not on the lip " + t.from().toShortString() + " (feet " + feet.toShortString() + ")";
            else if (t.kind() == DROP) {
                Fall f = drop(g, l, t.from(), t.from().relative(d), 0);
                if (!f.ok()) why = f.why();
                else s.plan = cost(f) + ", I'm at " + fmt(health(g)) + " (" + marginWhy(g) + ")";
            } else {
                int gap = gapOf(t), dy = t.land().getY() - t.from().getY();
                if (jumpKind(gap, dy, g.getFoodData().getFoodLevel() > 6) < 0) why = "no jump over " + gap + " to " + (dy > 0 ? "+" : "") + dy + " now (food " + g.getFoodData().getFoodLevel() + ": no sprint)";
                else if (!solid(l, t.land().below()) && !water(l, t.land()) && !l.getBlockState(t.land()).is(BlockTags.CLIMBABLE)) why = "nothing to land on at " + t.land().toShortString() + " now";
                else if (!com.claudemod.entity.life.Perception.clear(l, g.getEyePosition(), Vec3.atBottomCenterOf(t.land()).add(0, 0.05, 0))) why = "he can't see the landing " + t.land().toShortString();
                else s.plan = kindName(t.kind()) + " over " + gap + (dy == 0 ? "" : dy > 0 ? " up 1" : " down " + -dy) + ", " + fmt(t.hp()) + " HP, I'm at " + fmt(health(g));
            }
            if (why != null) {
                RUN.remove(g.getUUID());
                refusedNote(g, kindName(t.kind()) + " at " + t.from().toShortString() + ": " + why);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] {} from {} to {}: not taken, {} (Leaps.tick)", kindName(t.kind()), t.from().toShortString(), t.land().toShortString(), why);
                com.claudemod.entity.life.MindLog.refused(g, t.from(), kindName(t.kind()) + " [" + why + "]");
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, t.land(), g.tickCount);
                return "refused: " + why;
            }
            g.setShiftKeyDown(false); // (a crouch won't go off an edge; slime only saves a fall not sneaking)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] {} from {} to {}: {} (Leaps.tick)", kindName(t.kind()), t.from().toShortString(), t.land().toShortString(), s.plan);
        }
        int age = g.tickCount - s.t0;
        g.setYRot(d.toYRot()); g.setYHeadRot(d.toYRot()); g.yBodyRot = d.toYRot(); g.setXRot(t.kind() == DROP ? 60 : 20); // facing straight along the row, eyes on the landing
        boolean sprint = t.kind() == SPRINT_JUMP;
        if (s.phase == 0) { // lined up: on the lip, at the back of it for a run, still for a moment (vanilla's 10-tick jump delay is past)
            double want = t.kind() == DROP ? 0.0 : -0.25;
            Vec3 to = c0.add(dir.scale(want));
            Vec3 off = new Vec3(to.x - g.getX(), 0, to.z - g.getZ());
            if (off.length() > 0.06) { hands(g, off.normalize(), Mth.clamp(off.length() * 2.5, 0.15, 0.6), false, false); s.ground = 0; }
            else { hands(g, null, 0, false, false); if (g.onGround()) s.ground++; }
            if (s.ground >= 3 && jumpDelay(g) == 0 || s.ground >= 10 || age > 60 && off.length() < 0.3) { s.phase = 1; s.t0 = g.tickCount; }
            return null;
        }
        if (s.phase == 1) { // the run and the take-off
            Vec3 latFix = side.scale(Mth.clamp(-lat * 3, -0.3, 0.3));
            if (t.kind() == DROP) {
                if (!g.onGround() || g.isInWater() && along > 0.5) { s.phase = 2; s.takeoff = g.tickCount; }
                else hands(g, dir.add(latFix), 0.3, false, false); // a slow step over: little drift in the air
                if (age > 40 && s.phase == 1) { RUN.remove(g.getUUID()); com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop from {}: 2 s at the lip and still on it (along {}) (Leaps.tick)", t.from().toShortString(), String.format("%.2f", along)); return "stuck at the lip"; }
                if (s.phase == 1) return null;
            } else {
                double v = g.getDeltaMovement().x * dir.x + g.getDeltaMovement().z * dir.z;
                int dy = t.land().getY() - t.from().getY(); double target = gapOf(t) + 1;
                boolean edge = along + v + groundAcc(sprint) > 0.5 + 0.3 - 0.02; // the last tick his box still stands on the lip
                boolean far = predictJump(along, v, sprint, dy, 1) >= target + 0.25; // jumping now already carries him past the landing's middle with the air to steer back: no need to run on (a 1-wide pillar after a sprint-jump's 3.5 blocks)
                boolean jump = g.onGround() && (edge || far);
                if (!g.onGround() && along > 0.3) { s.phase = 2; s.takeoff = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] {} from {}: off the lip without the jump (along {}) (Leaps.tick)", kindName(t.kind()), t.from().toShortString(), String.format("%.2f", along)); }
                else {
                    hands(g, dir.add(latFix), 1.0, sprint, jump);
                    if (jump) { s.phase = 2; s.takeoff = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] {} from {}: take-off at {} along, {} b/t (Leaps.tick)", kindName(t.kind()), t.from().toShortString(), String.format("%.2f", along), String.format("%.3f", v)); }
                    if (age > 40 && s.phase == 1) { RUN.remove(g.getUUID()); return "no take-off in 2 s"; }
                    return null;
                }
            }
        }
        if (s.phase == 2) { // in the air: steer for the landing's middle
            boolean down = g.onGround() || g.onClimbable() || g.isInWater();
            if (down && g.tickCount - s.takeoff > 1) { s.phase = 3; s.t0 = g.tickCount; }
            else {
                double target = Math.abs((t.land().getX() - t.from().getX()) * dir.x + (t.land().getZ() - t.from().getZ()) * dir.z);
                double v = g.getDeltaMovement().x * dir.x + g.getDeltaMovement().z * dir.z;
                double best = Double.MAX_VALUE; int bu = 0;
                for (int u = 1; u >= -1; u--) { double e = Math.abs(predict(along, v, g.getY(), g.getDeltaMovement().y, t.land().getY(), u, g.isSprinting()) - target); if (e < best - 0.02) { best = e; bu = u; } }
                Vec3 latFix = side.scale(Mth.clamp(-lat * 3, -1, 1));
                Vec3 w = dir.scale(bu).add(latFix);
                hands(g, w.lengthSqr() < 1e-4 ? null : w.normalize(), Math.min(1.0, w.length()), sprint, false);
                if (g.tickCount - s.takeoff > 20 * 10) { RUN.remove(g.getUUID()); return "10 s in the air"; }
                return null;
            }
        }
        // landed: steady on the landing, then the word on it
        Vec3 lc = Vec3.atBottomCenterOf(t.land());
        Vec3 off = new Vec3(lc.x - g.getX(), 0, lc.z - g.getZ());
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        boolean onIt = Math.abs(feet.getX() - t.land().getX()) + Math.abs(feet.getZ() - t.land().getZ()) <= 1 && Math.abs(feet.getY() - t.land().getY()) <= 1;
        if (onIt && off.length() > 0.2 && g.tickCount - s.t0 < 10 && g.onGround()) { hands(g, off.normalize(), 0.3, false, false); return null; }
        hands(g, null, 0, false, false);
        RUN.remove(g.getUUID());
        float took = Math.max(0, s.hp0 - health(g));
        String how = kindName(t.kind()) + " from " + t.from().toShortString() + " to " + t.land().toShortString() + ": " + (onIt ? "landed" : "MISSED, came down at " + feet.toShortString()) + ", took " + fmt(took) + " HP (planned " + fmt(t.hp()) + "), at " + fmt(health(g)) + ", " + (g.tickCount - s.takeoff) + " ticks in the air";
        com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] {} (Leaps.tick)", how);
        if (!onIt) { g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, t.land(), g.tickCount); com.claudemod.entity.life.MindLog.refused(g, t.land(), kindName(t.kind()) + " [missed]"); }
        return onIt ? "landed" : "missed";
    }

    private static java.lang.reflect.Field noJumpDelay; private static boolean noJumpDelayLooked;
    /** Vanilla's 10 ticks after a jump before the next (LivingEntity.noJumpDelay; -1: not readable, the 10 ground ticks stand in). */
    static int jumpDelay(ClaudeEntity g) {
        try { if (!noJumpDelayLooked) { noJumpDelayLooked = true; noJumpDelay = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("noJumpDelay"); noJumpDelay.setAccessible(true); } return noJumpDelay == null ? -1 : noJumpDelay.getInt(g); }
        catch (ReflectiveOperationException | RuntimeException e) { noJumpDelay = null; return -1; }
    }

    /** The first drop or jump on the way a queued walk (or the walk to them) takes, or null (the chat's "jumping" claim). */
    public static ActionPath.Act.Leap routeLeaps(ClaudeEntity g, net.minecraft.server.level.ServerPlayer p, java.util.List<Task> ts) {
        BlockPos to = null;
        for (Task t : ts) if (t instanceof Task.GoTo gt && gt.pos().distSqr(g.blockPosition()) > 9) { to = gt.pos(); break; }
        if (to == null && p != null && p.level() == g.level()) to = p.blockPosition();
        if (to == null) return null;
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        var plan = ActionPath.plan(g, (ServerLevel) g.level(), feet, to, 1.5, false, 3000, ActionPath.Mode.NO_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
        if (plan == null) return null;
        for (var a : plan.acts()) if (a instanceof ActionPath.Act.Leap lp) return lp;
        return null;
    }

    /** A ceiling world's bridge block (the user, 16:30: netherrack "its not ghast proof. cobble is better"): cobblestone or cobbled deepslate first, then other tough stone, never netherrack or dirt; empty: none on him. The same rule as FortressScout.ghastProof (patch_nether_scout.py): whichever lands first, they agree. */
    public static net.minecraft.world.item.ItemStack ghastProof(ClaudeEntity g) {
        var s = g.inventory.find(x -> x.is(net.minecraft.world.item.Items.COBBLESTONE) || x.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE));
        if (!s.isEmpty()) return s;
        return g.inventory.find(x -> x.is(net.minecraft.world.item.Items.STONE) || x.is(net.minecraft.world.item.Items.DEEPSLATE) || x.is(net.minecraft.world.item.Items.BLACKSTONE) || x.is(net.minecraft.world.item.Items.ANDESITE) || x.is(net.minecraft.world.item.Items.DIORITE) || x.is(net.minecraft.world.item.Items.GRANITE) || x.is(net.minecraft.world.item.Items.TUFF)
                || x.is(net.minecraft.world.item.Items.STONE_BRICKS) || x.is(net.minecraft.world.item.Items.POLISHED_BLACKSTONE) || x.is(net.minecraft.world.item.Items.POLISHED_BLACKSTONE_BRICKS) || x.is(net.minecraft.world.item.Items.NETHER_BRICKS));
    }

    /** As predict, jumping this tick from the ground (the jump tick: +0.42 up, +0.2 along sprinting, ground speed and friction), landing dy up/down. */
    static double predictJump(double along, double v, boolean sprint, int dy, int u) {
        v += (sprint ? 0.2 : 0) + groundAcc(sprint); along += v; v *= 0.546;
        return predict(along, v, 0.42, (0.42 - 0.08) * 0.98, dy, u, sprint);
    }

    /** Where his centre comes down (along the row, from the lip's middle) holding input u along it from now. */
    static double predict(double along, double v, double y, double vy, int landY, int u, boolean sprint) {
        for (int t = 0; t < 80; t++) { v += u * airAcc(sprint); double ny = y + vy; along += v; v *= 0.91; boolean down = vy < 0; vy = (vy - 0.08) * 0.98; if (down && ny <= landY + 1e-3) return along; y = ny; }
        return along;
    }

    // ------------------------------------------------------------------------------------------------ "jump down" / "drop down"

    /** The drops off the ledges round him (from his cell or up to 3 cells along each side), nearest the target first; refused ones carry their reason. */
    public static java.util.List<Object[]> dropsAround(ClaudeEntity g, ServerLevel l, Vec3 toward) {
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        BlockPos me = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos lip = me;
            for (int s = 0; s <= 3; s++) {
                if (s > 0) { BlockPos n = lip.relative(d); if (!open(l, n) || !open(l, n.above()) || !solid(l, n.below())) break; lip = n; }
                BlockPos col = lip.relative(d);
                if (!open(l, col) || !open(l, col.above()) || solid(l, col.below()) || water(l, col.below())) continue;
                Fall f = drop(g, l, lip, col, 0);
                if (f.down() < 2 && f.ok()) continue;
                double score = toward == null ? s : Vec3.atBottomCenterOf(f.land()).distanceTo(toward) + s * 0.5;
                out.add(new Object[] {lip.immutable(), col.immutable(), f, score});
                break;
            }
        }
        out.sort((a, b) -> Double.compare((double) a[3], (double) b[3]));
        return out;
    }
    static boolean open(ServerLevel l, BlockPos p) { return l.getBlockState(p).getCollisionShape(l, p).isEmpty() && !lava(l, p); }

    /** drop_down: their "jump down" / "drop down": off the ledge toward them (or the way named); the cost said; a staircase dug down when it isn't survivable. */
    public static String dropDown(ClaudeEntity g, net.minecraft.server.level.ServerPlayer player, com.claudemod.entity.life.Skills.Step st) {
        ServerLevel l = (ServerLevel) g.level();
        String t = st.target() == null ? "" : st.target().toLowerCase();
        Vec3 toward = null;
        var m = java.util.regex.Pattern.compile("(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)").matcher(t);
        if (m.find()) toward = new Vec3(Integer.parseInt(m.group(1)) + 0.5, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) + 0.5);
        else if (player != null && player.level() == l && ("near_them".equals(st.where()) || t.matches(".*\\b(me|you|them|toward|towards|to)\\b.*") || t.isBlank())) toward = player.position();
        Direction named = null; for (Direction c : Direction.Plane.HORIZONTAL) if (t.matches(".*\\b" + c.getName() + "\\b.*")) named = c;
        if (named != null) toward = g.position().add(named.getStepX() * 8, -8, named.getStepZ() * 8);
        var all = dropsAround(g, l, toward);
        if (all.isEmpty()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop_down {}: no ledge within 3 blocks of {} (Leaps.dropDown)", t, g.blockPosition().toShortString()); return "failed: no ledge to drop off within 3 blocks of me"; }
        Object[] best = all.get(0); Fall f = (Fall) best[2];
        if (f.ok()) {
            if (!best[0].equals(BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()))) g.tasks.add(new Task.GoTo((BlockPos) best[0], 0.5)); // onto the lip first
            g.tasks.add(new Task.Leap((BlockPos) best[0], f.land(), DROP, f.down(), f.hp()));
            String line = sayLine(g, f);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop_down {}: off {} onto {}: {} ({}) (Leaps.dropDown)", t, ((BlockPos) best[0]).toShortString(), f.land().toShortString(), line, marginWhy(g));
            return line;
        }
        Object[] alt = null; for (Object[] o : all) if (((Fall) o[2]).ok()) { alt = o; break; }
        String no = cost(f) + ", I'm at " + fmt(health(g)) + ": not jumping (" + f.why() + ")";
        refusedNote(g, no);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop_down {}: the drop off {} refused: {} (Leaps.dropDown)", t, ((BlockPos) best[0]).toShortString(), no);
        if (alt != null && ((Fall) alt[2]).hp() <= f.hp() && Vec3.atBottomCenterOf(((Fall) alt[2]).land()).distanceTo(toward == null ? g.position() : toward) <= Vec3.atBottomCenterOf(f.land()).distanceTo(toward == null ? g.position() : toward) + 6) {
            Fall a = (Fall) alt[2];
            if (!alt[0].equals(BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()))) g.tasks.add(new Task.GoTo((BlockPos) alt[0], 0.5));
            g.tasks.add(new Task.Leap((BlockPos) alt[0], a.land(), DROP, a.down(), a.hp()));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop_down {}: a safer drop off {} instead: {} (Leaps.dropDown)", t, ((BlockPos) alt[0]).toShortString(), sayLine(g, a));
            return no + "; dropping off " + ((BlockPos) alt[0]).toShortString() + " instead: " + sayLine(g, a);
        }
        // a staircase down instead: his own planner, digging allowed, to the bottom of that drop
        BlockPos goal = f.land(); BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        net.minecraft.world.item.Item scaff = null; for (net.minecraft.world.item.Item it : new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.COBBLED_DEEPSLATE, net.minecraft.world.item.Items.DIRT}) if (g.inventory.count(x -> x.is(it)) > 0) { scaff = it; break; }
        var plan = ActionPath.plan(g, l, feet, goal, 1.5, scaff != null, 12000, ActionPath.Mode.MAY_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, java.util.List.of(), 3.0);
        if (plan == null || plan.acts().isEmpty()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop_down {}: no staircase plans down to {} either (Leaps.dropDown)", t, goal.toShortString()); return no + "; and no way down by digging from here either"; }
        g.tasks.addAll(ActionPath.tasks(plan, feet, scaff == null ? net.minecraft.world.item.Items.COBBLESTONE : scaff, goal, 1.5, l));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] drop_down {}: a staircase down to {} instead ({} digs, {} walks) (Leaps.dropDown)", t, goal.toShortString(), plan.digs(), plan.walks());
        return no + "; digging a staircase down instead";
    }

    /** The FALL line of his truth block: the drops round him with their cost, and the rule. */
    public static String talk(ClaudeEntity g, Vec3 toward) {
        ServerLevel l = (ServerLevel) g.level();
        StringBuilder sb = new StringBuilder("FALL DAMAGE (worked out exactly, say the number): a fall of N blocks costs N-3 HP (none into water, a cobweb, powder snow, slime, onto a ladder, vine or scaffolding; a fifth onto hay; half the distance onto a bed; less with Feather Falling / Protection). You're at " + fmt(health(g)) + " HP and keep " + (int) margin(g) + " after any drop" + marginWhy(g).substring(("keep " + (int) margin(g)).length()));
        int n = 0;
        for (Object[] o : dropsAround(g, l, toward)) { if (n++ >= 3) break; Fall f = (Fall) o[2]; sb.append("; the ledge at ").append(((BlockPos) o[1]).toShortString()).append(": ").append(cost(f)).append(f.ok() ? ", fine to take" : ", NOT taken (" + f.why() + ")"); }
        if (n == 0) sb.append("; no ledge right by you");
        return sb.append(". \"jump down\" / \"drop down\" is the skill drop_down (toward them by default).").toString();
    }
}
