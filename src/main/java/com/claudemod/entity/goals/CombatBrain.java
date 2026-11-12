package com.claudemod.entity.goals;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.CombatLog;
import com.claudemod.entity.life.Weights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * His combat moves (the user 11-08: "a lot of combat skills he can act on like dodging, blocking, falling back, rounding up for
 * groups ... tactics for every enemy type and mixed groups"; docs/combat-rewrite.md). Each move is priced from what's on him,
 * every factor a weight under combat.move.*, and counted in his fight record (CombatLog.move) so the review sees what he did.
 * Called from WorkGoal's fight and run each tick.
 */
public final class CombatBrain {
    private CombatBrain() {}
    private static double w(String n, double d) { return Weights.w(n, d); }

    /**
     * Dodge: a step across the line of what's about to hit him, a player's sidestep. An evoker's fang (magic: no shield takes it)
     * within reach of his feet before its bite; an arrow, bolt, trident or fireball closing on him within combat.move.dodge.eta_ticks
     * when his shield can't take it. Returns the spot stepped to, or null (nothing to dodge, or nowhere to step).
     */
    static Vec3 dodge(ClaudeEntity g, boolean shieldReady) {
        if (!(g.level() instanceof net.minecraft.server.level.ServerLevel lv) || !g.onGround()) return null;
        Vec3 me = g.position(); Vec3 from = null; String what = null;
        for (EvokerFangs f : lv.getEntitiesOfClass(EvokerFangs.class, g.getBoundingBox().inflate(w("combat.move.dodge.fang_r", 1.4), 1, w("combat.move.dodge.fang_r", 1.4)))) {
            if (f.tickCount > w("combat.move.dodge.fang_pending_ticks", 26)) continue; // (bitten already: it bites 8 ticks after its warm-up, warm-ups run to ~18)
            Vec3 src = f.getOwner() != null ? f.getOwner().position() : f.position();
            from = me.subtract(src).multiply(1, 0, 1); what = "an evoker's fangs";
            if (from.lengthSqr() < 1e-4) from = me.subtract(f.position()).multiply(1, 0, 1);
            break;
        }
        if (from == null && !shieldReady) {
            double eta = w("combat.move.dodge.eta_ticks", 10);
            for (Projectile p : lv.getEntitiesOfClass(Projectile.class, g.getBoundingBox().inflate(16))) {
                if (p.getOwner() == g || p instanceof AbstractArrow a && a.onGround() || !(p instanceof AbstractArrow || p instanceof AbstractHurtingProjectile)) continue;
                Vec3 v = p.getDeltaMovement(); if (v.lengthSqr() < 0.01) continue;
                Vec3 to = g.getBoundingBox().getCenter().subtract(p.position());
                double t = to.dot(v) / v.lengthSqr(); if (t < 0 || t > eta) continue; // (going away, or not here soon)
                Vec3 miss = p.position().add(v.scale(t)).subtract(g.getBoundingBox().getCenter());
                if (miss.horizontalDistance() > 0.9 || Math.abs(miss.y) > 1.2) continue; // (it misses him anyway)
                from = v.multiply(1, 0, 1); what = "a shot"; break;
            }
        }
        if (from == null || from.lengthSqr() < 1e-4) return null;
        Vec3 side = new Vec3(-from.z, 0, from.x).normalize().scale(w("combat.move.dodge.step", 1.8));
        Vec3 best = null; double bestS = Double.MAX_VALUE;
        for (Vec3 cand : new Vec3[] {me.add(side), me.subtract(side)}) {
            BlockPos c = BlockPos.containing(cand.x, me.y + 0.1, cand.z);
            if (!lv.getBlockState(c).getCollisionShape(lv, c).isEmpty() || !lv.getBlockState(c.above()).getCollisionShape(lv, c.above()).isEmpty()) continue; // (a wall there)
            if (lv.getBlockState(c.below()).getCollisionShape(lv, c.below()).isEmpty() || !lv.getFluidState(c).isEmpty()) continue; // (no floor, or water/lava)
            double s = 0; // (the side away from the other foes on him)
            for (LivingEntity m : lv.getEntitiesOfClass(LivingEntity.class, g.getBoundingBox().inflate(6), m -> m instanceof Enemy && m.isAlive())) s += 1 / Math.max(0.5, m.position().distanceTo(cand));
            if (s < bestS) { bestS = s; best = new Vec3(c.getX() + 0.5, me.y, c.getZ() + 0.5); }
        }
        if (best == null) return null;
        g.getNavigation().stop(); g.getMoveControl().setWantedPosition(best.x, best.y, best.z, w("combat.move.dodge.speed", 1.3));
        CombatLog.move(g, "dodge");
        if (g.tickCount % 10 == 0) ClaudeMod.LOGGER.info("[Claude fight] dodge: {} coming, a step across to {} (CombatBrain.dodge)", what, BlockPos.containing(best).toShortString());
        return best;
    }

    /**
     * Sweep or crit, priced: a crit adds half his hit to one; a sweep (on the ground, not sprinting, a sword) adds
     * 1 + SWEEPING_DAMAGE_RATIO x his hit to each other foe beside the target. True: hold the jump, sweep this swing.
     */
    static boolean sweepBeatsCrit(ClaudeEntity g, LivingEntity target) {
        if (!g.getMainHandItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SWORD_SWEEP)) return false;
        double dmg = g.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (sweepBystander(g, target) != null) return false; // a sweep would hit something calm beside it
        int n = g.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(1, 0.25, 1), m -> m != target && m != g && foe(g, m) && m.isAlive() && g.distanceToSqr(m) < 9).size();
        if (n == 0) return false;
        double sweep = n * (1 + g.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) * dmg) * w("combat.move.sweep.worth", 1.0), crit = 0.5 * dmg * w("combat.move.crit.worth", 1.0);
        if (sweep > crit) { CombatLog.move(g, "sweep"); return true; }
        return false;
    }

    /** A mob he's fighting: hostile, and a neutral one (enderman, zombified piglin, golem, wolf, bee) only once it's angry at him. */
    static boolean foe(ClaudeEntity g, LivingEntity m) {
        if (m instanceof net.minecraft.world.entity.NeutralMob nm) return nm.isAngryAt(g);
        return m instanceof Enemy;
    }

    /** What vanilla's sword sweep would hit beside the target that isn't his foe (a calm enderman, a villager, the user), or null. The sweep: a full-charge sword hit on the ground, not sprinting, not walking (Player.attack). */
    public static LivingEntity sweepBystander(ClaudeEntity g, net.minecraft.world.entity.Entity target) {
        if (!g.getMainHandItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SWORD_SWEEP) || !g.onGround() || g.isSprinting() || g.getAttackStrengthScale(0.5f) <= 0.9f || g.walkDist - g.walkDistO >= g.getSpeed()) return null;
        for (LivingEntity m : g.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(1, 0.25, 1)))
            if (m != target && m != g && m.isAlive() && !g.isAlliedTo(m) && !(m instanceof net.minecraft.world.entity.decoration.ArmorStand a && a.isMarker()) && g.distanceToSqr(m) < 9 && !foe(g, m)) return m;
        return null;
    }
}
