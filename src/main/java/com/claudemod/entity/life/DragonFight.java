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
    static final class St { UUID target; String doing = ""; int said; BlockPos vantage, base; int pillarUp, vantageUp, sinceShot; boolean waterTried; }
    private static Map<UUID, St> sts; // (static: fields added live start null)
    static St st(ClaudeEntity g) { if (sts == null) sts = new HashMap<>(); return sts.computeIfAbsent(g.getUUID(), k -> new St()); }
    public static void reset(ClaudeEntity g) { if (sts != null) sts.remove(g.getUUID()); }

    static int count(ClaudeEntity g, java.util.function.Predicate<ItemStack> p) { return g.inventory.count(p); }
    static boolean block(ItemStack s) { return s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.END_STONE) || s.is(Items.NETHERRACK) || s.is(Items.DIRT) || s.is(Items.STONE); }

    // ------------------------------------------------------------------ the skill (Overworld): kit, the portal, in
    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        Skills.Step again = new Skills.Step("fight_dragon", st.target(), st.count(), st.where());
        if (level.dimension() == Level.END) { reset(g); g.tasks.add(new Task.Dragon(20 * 60 * 30)); return "in the End: the crystals first, then the dragon"; }
        if (level.dimension() != Level.OVERWORLD) return "failed: the End portal is in the Overworld (I'm in the " + level.dimension().location().getPath() + ")";
        // the kit (the expert's): a bow and arrows for the crystals and the dragon in flight, blocks to pillar, food, a sword
        int arrows = count(g, s -> s.is(Items.ARROW)), blocks = count(g, DragonFight::block), food = com.claudemod.director.Kit.food(g);
        boolean bow = count(g, s -> s.is(Items.BOW)) > 0, blade = count(g, s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem) > 0;
        List<String> miss = new ArrayList<>();
        if (!bow) miss.add("a bow"); if (arrows < 32) miss.add((32 - arrows) + " more arrows"); if (blocks < 48) miss.add((48 - blocks) + " more blocks to pillar"); if (food < 8) miss.add("food (" + food + " meals, 8 wanted)"); if (!blade) miss.add("a sword");
        if (!miss.isEmpty()) {
            ClaudeMod.LOGGER.info("[Claude dragon] the kit is short: {} (DragonFight.skill)", String.join(", ", miss));
            g.skillQueue.addFirst(again);
            if (!bow) { g.skillQueue.addFirst(new Skills.Step("obtain", "bow", 1)); return "packing for the dragon: a bow first"; }
            if (arrows < 32) { g.skillQueue.addFirst(new Skills.Step("obtain", "arrow", 64 - arrows)); return "packing for the dragon: arrows (" + arrows + " on me)"; }
            if (blocks < 48) { g.skillQueue.addFirst(new Skills.Step("obtain", "cobblestone", 64 - blocks)); return "packing for the dragon: blocks to pillar (" + blocks + " on me)"; }
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
        reset(g); g.tasks.add(new Task.Dragon(20 * 60 * 30));
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
    static boolean sees(ClaudeEntity g, Vec3 from, Vec3 to) { return g.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g)).getType() == HitResult.Type.MISS; }

    /** One tick of the fight; true: it's over (the dragon dead, or nothing more he can do from here). */
    public static boolean tick(ClaudeEntity g, int ticks) {
        ServerLevel level = (ServerLevel) g.level(); St s = st(g);
        if (level.dimension() != Level.END) { // at the portal: straight into it
            BlockPos p = nearestPortalBlock(level, g.blockPosition(), 6);
            if (p == null || ticks > 200) { ClaudeMod.LOGGER.info("[Claude dragon] no End portal block within 6 of {} (DragonFight.tick)", g.blockPosition().toShortString()); return true; }
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
            if (g.horizontalCollision && g.onGround()) g.getJumpControl().jump();
            say(g, s, "into the portal", "stepping onto the End portal at " + p.toShortString());
            return false;
        }
        var fight = level.getDragonFight();
        List<? extends EnderDragon> dragons = level.getDragons();
        EnderDragon dragon = dragons.stream().filter(EnderDragon::isAlive).findFirst().orElse(null);
        if (dragon == null && (fight == null || fight.hasPreviouslyKilledDragon())) { if (g.isUsingItem()) g.stopUsingItem(); ClaudeMod.LOGGER.info("[Claude dragon] the dragon is dead (DragonFight.tick)"); return true; }
        // breath: out of its cloud
        for (AreaEffectCloud c : level.getEntitiesOfClass(AreaEffectCloud.class, g.getBoundingBox().inflate(5), a -> a.getParticle().getType() == ParticleTypes.DRAGON_BREATH)) {
            Vec3 away = g.position().subtract(c.position()).multiply(1, 0, 1); if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
            Vec3 to = g.position().add(away.normalize().scale(c.getRadius() + 2.5));
            if (g.isUsingItem()) g.stopUsingItem(); g.setSprinting(true); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(to.x, g.getY(), to.z, 1.3);
            say(g, s, "out of the breath", "a dragon's breath cloud " + String.format("%.1f", c.distanceTo(g)) + " off, radius " + String.format("%.1f", c.getRadius()));
            return false;
        }
        // hurt: eat (a meal on hand) before anything else
        if (g.getHealth() <= 10 && g.onGround() && g.getFoodData().getFoodLevel() < 20) { var food = com.claudemod.director.ClaudeLife.foodToEat(g, g.getHealth() <= 6);
            if (food != null) { if (g.isUsingItem()) g.stopUsingItem(); g.tasks.addFirst(new Task.Dragon(20 * 60 * 30)); g.tasks.addFirst(new Task.Eat(food)); say(g, s, "eating", "health " + (int) g.getHealth()); return true; } }
        // off the spawn platform: onto the main island (end stone, toward its middle)
        double fromMid = Math.sqrt(g.getX() * g.getX() + g.getZ() * g.getZ());
        BlockPos under = BlockPos.containing(g.getX(), g.getY() - 0.5, g.getZ());
        if (fromMid > 70 || level.getBlockState(under).is(Blocks.OBSIDIAN) && fromMid > 40) {
            if (!g.onGround()) return false;
            BlockPos land = islandEdge(level, g);
            if (land == null) { say(g, s, "no island", "no end stone toward the middle from " + g.blockPosition().toShortString()); return false; }
            g.tasks.addFirst(new Task.Dragon(20 * 60 * 30)); g.tasks.addFirst(new Task.GoTo(land, 1.0));
            say(g, s, "onto the island", "from " + g.blockPosition().toShortString() + " to end stone at " + land.toShortString() + ", " + (int) Math.sqrt(land.distSqr(g.blockPosition())) + " off");
            return true;
        }
        // the crystals
        List<EndCrystal> crystals = level.getEntitiesOfClass(EndCrystal.class, new AABB(-80, 0, -80, 80, 200, 80), EndCrystal::isAlive);
        crystals.removeIf(c -> c.getY() < 60); // (the ones the respawn places on the fountain: not these)
        if (!crystals.isEmpty()) return crystals(g, level, s, crystals, ticks);
        // the dragon
        if (dragon != null) return dragon(g, level, s, dragon, ticks);
        return false;
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
        for (int k = 1; k <= n; k++) g.tasks.add(new Task.Break(feet.below(k)));
        g.tasks.add(new Task.Dragon(20 * 60 * 30)); s.base = null;
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
        EndCrystal c = crystals.stream().min(Comparator.comparingDouble(x -> x.distanceToSqr(g))).orElse(null);
        if (caged(level, c)) return cage(g, level, s, c);
        // none in sight: a spot on the island with a clear line to it, from the ground or a few blocks up a pillar there (a tall tower's rim hides its crystal from close by), the cheapest walk and climb
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (s.vantage == null || ticks % 600 == 0) {
            s.vantage = null; double bd = Double.MAX_VALUE;
            for (int r = 16; r <= 64; r += 6) for (int a = 0; a < 24; a++) { double ang = a * Math.PI / 12; int x = (int) Math.floor(c.getX() + Math.cos(ang) * r), z = (int) Math.floor(c.getZ() + Math.sin(ang) * r);
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z)); if (!level.getBlockState(top.below()).is(Blocks.END_STONE)) continue;
                for (int k = 0; k <= 16; k += 2) { Vec3 e = new Vec3(top.getX() + 0.5, top.getY() + k + 1.62, top.getZ() + 0.5); if (seenPoint(g, e, c) == null) continue;
                    double cost = Math.sqrt(top.distSqr(feet)) + k * 4; if (cost < bd) { bd = cost; s.vantage = top.immutable(); s.vantageUp = k; } break; } }
        }
        if (s.vantage == null) { say(g, s, "no clear shot", "the crystal at " + c.blockPosition().toShortString() + ": no spot on the island 16-64 off with a clear line to it, even 16 up"); return false; }
        int up = feet.getY() - (s.base == null ? feet.getY() : s.base.getY());
        boolean atVantage = Math.abs(feet.getX() - s.vantage.getX()) <= 1 && Math.abs(feet.getZ() - s.vantage.getZ()) <= 1;
        if (!atVantage && up > 2 && s.base != null) return down(g, s, "to a clear shot elsewhere");
        if (!atVantage) { s.base = null; g.tasks.addFirst(new Task.Dragon(20 * 60 * 30)); g.tasks.addFirst(new Task.GoTo(s.vantage, 0.8)); say(g, s, "to a clear shot", "the crystal at " + c.blockPosition().toShortString() + " from " + s.vantage.toShortString() + (s.vantageUp > 0 ? ", " + s.vantageUp + " up a pillar" : "")); return true; }
        if (s.base == null) s.base = feet.immutable();
        int need = s.vantageUp - (feet.getY() - s.base.getY());
        if (need > 0) { g.tasks.add(new Task.Pillar(need)); g.tasks.add(new Task.Dragon(20 * 60 * 30)); say(g, s, "pillaring for a clear shot", need + " up at " + feet.toShortString() + " to see the crystal at " + c.blockPosition().toShortString()); return true; }
        s.vantage = null; return false; // (there and still no line: look again)
    }

    /** A caged crystal: up a pillar beside its tower to the cage, the bars broken, back down his pillar, then shot from the ground. */
    static boolean cage(ClaudeEntity g, ServerLevel level, St s, EndCrystal c) {
        BlockPos cb = c.blockPosition();
        List<BlockPos> bars = new ArrayList<>(); for (BlockPos q : BlockPos.betweenClosed(cb.offset(-2, -1, -2), cb.offset(2, 3, 2))) if (level.getBlockState(q).is(Blocks.IRON_BARS)) bars.add(q.immutable());
        Vec3 eye = g.getEyePosition();
        List<BlockPos> inReach = new ArrayList<>(); for (BlockPos b : bars) if (Vec3.atCenterOf(b).distanceTo(eye) <= 4.4) inReach.add(b);
        if (!inReach.isEmpty() && g.getY() > cb.getY() - 4) { // at the cage: its bars (every one in reach: the shot from the ground needs a clear way in)
            for (BlockPos b : inReach) g.tasks.add(new Task.Break(b));
            g.tasks.add(new Task.Dragon(20 * 60 * 30));
            say(g, s, "breaking the cage", inReach.size() + " bars in reach of " + bars.size() + " round the crystal at " + cb.toShortString());
            return true;
        }
        if (g.getY() > cb.getY() - 4) { // up here and no bar in reach: the cage is open enough; down his pillar
            int n = (int) Math.floor(g.getY()) - (s.base == null ? (int) g.getY() - 1 : s.base.getY());
            for (int k = 1; k <= n; k++) g.tasks.add(new Task.Break(BlockPos.containing(g.getX(), g.getY() - 0.5, g.getZ()).below(k - 1)));
            g.tasks.add(new Task.Dragon(20 * 60 * 30));
            say(g, s, "down the pillar", n + " blocks down from " + g.blockPosition().toShortString() + ", the cage open");
            return true;
        }
        // to the foot of its tower, then up
        BlockPos foot = null; double bd = Double.MAX_VALUE;
        for (int r = 2; r <= 7 && foot == null; r++) for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos col = new BlockPos(cb.getX() + d.getStepX() * r, 0, cb.getZ() + d.getStepZ() * r);
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, col); if (top.getY() > cb.getY() - 6 || !level.getBlockState(top.below()).is(Blocks.END_STONE)) continue; // (outside the tower: end stone, low)
            if (!level.getBlockState(new BlockPos(cb.getX() + d.getStepX() * (r - 1), top.getY() + 1, cb.getZ() + d.getStepZ() * (r - 1))).is(Blocks.OBSIDIAN)) continue; // (right beside its obsidian)
            double dd = top.distSqr(g.blockPosition()); if (dd < bd) { bd = dd; foot = top.immutable(); } }
        if (foot == null) { say(g, s, "no foot to the tower", "the caged crystal at " + cb.toShortString() + ": no end stone beside its tower"); return false; }
        if (foot.distSqr(BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ())) > 2) { g.tasks.addFirst(new Task.Dragon(20 * 60 * 30)); g.tasks.addFirst(new Task.GoTo(foot, 0.4)); say(g, s, "to the caged crystal's tower", "its foot at " + foot.toShortString()); return true; }
        s.base = foot;
        int up = cb.getY() - foot.getY() - 1;
        g.tasks.add(new Task.Pillar(up)); g.tasks.add(new Task.Dragon(20 * 60 * 30));
        say(g, s, "pillaring up", up + " blocks beside the tower to the cage at " + cb.toShortString());
        return true;
    }

    static boolean dragon(ClaudeEntity g, ServerLevel level, St s, EnderDragon d, int ticks) {
        var phase = d.getPhaseManager().getCurrentPhase();
        Vec3 head = d.head.getBoundingBox().getCenter();
        if (phase.isSitting()) { // on the fountain: its head with the sword, crits between
            if (g.isUsingItem()) g.stopUsingItem();
            equipBlade(g);
            double reach = d.head.getBoundingBox().distanceToSqr(g.getEyePosition());
            g.getLookControl().setLookAt(head.x, head.y, head.z);
            if (reach > 2.9 * 2.9) { Vec3 to = head.add(g.position().subtract(head).multiply(1, 0, 1).normalize().scale(2.2)); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(to.x, g.getY(), to.z, 1.2); g.setSprinting(true); say(g, s, "to its head", "the dragon perched, its head " + String.format("%.1f", Math.sqrt(reach)) + " off"); return false; }
            g.getNavigation().stop(); g.setSprinting(false);
            boolean charged = g.getAttackStrengthScale(0.5f) >= 0.95f;
            if (charged && g.onGround() && !g.isInWater()) { g.getJumpControl().jump(); return false; } // (a crit: the hit lands falling)
            if (charged && !g.onGround() && g.getDeltaMovement().y < 0 || charged && g.isInWater()) {
                float hp = d.getHealth(); g.attack(d.head); g.swing(InteractionHand.MAIN_HAND);
                ClaudeMod.LOGGER.info("[Claude dragon] a hit on its head: {} -> {} (DragonFight)", hp, d.getHealth());
            }
            say(g, s, "hitting its head", "the dragon perched at " + d.blockPosition().toShortString() + ", health " + (int) d.getHealth());
            return false;
        }
        // flying: arrows when it's in range and in sight, else waiting near the fountain out of its path
        double dist = d.distanceTo(g);
        if (dist <= 56 && sees(g, g.getEyePosition(), head) && g.onGround()) {
            if (shoot(g, head, d.getDeltaMovement())) ClaudeMod.LOGGER.info("[Claude dragon] an arrow at the dragon, {} off, health {} (DragonFight)", (int) dist, (int) d.getHealth());
            say(g, s, "shooting the dragon", "flying, " + (int) dist + " off, health " + (int) d.getHealth());
            return false;
        }
        if (g.isUsingItem() && g.getUseItem().is(Items.BOW)) g.stopUsingItem();
        double fromMid = Math.sqrt(g.getX() * g.getX() + g.getZ() * g.getZ());
        if (fromMid > 22 && g.onGround()) { Vec3 to = new Vec3(g.getX(), 0, g.getZ()).normalize().scale(14); BlockPos t = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(to.x, 0, to.z)); g.tasks.addFirst(new Task.Dragon(20 * 60 * 30)); g.tasks.addFirst(new Task.GoTo(t, 2.0)); say(g, s, "to the fountain", "waiting for the dragon near the middle"); return true; }
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
        boolean drawing = g.isUsingItem() && g.getUseItem().is(Items.BOW);
        if (!drawing) { g.hold(g.inventory.find(x -> x.is(Items.BOW))); g.startUsingItem(InteractionHand.MAIN_HAND); return false; }
        Vec3 from = g.getEyePosition().add(0, -0.1, 0);
        float[] yp = aim(from, target, vel);
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
            double lo = -80, hi = 45, ok = Double.NaN; int ticksAt = 0;
            for (int i = 0; i < 40; i++) { double mid = (lo + hi) / 2; double[] r = fly(Math.toRadians(mid), h); if (Double.isNaN(r[0])) { lo = mid; continue; } if (r[0] < dy) lo = mid; else { hi = mid; ok = mid; ticksAt = (int) r[1]; } }
            if (Double.isNaN(ok)) return null;
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
