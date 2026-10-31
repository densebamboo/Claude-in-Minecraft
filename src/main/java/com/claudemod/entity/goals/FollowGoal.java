package com.claudemod.entity.goals;

import java.util.EnumSet;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * FRIENDLY: acts like an ordinary player hanging out. Stays a few blocks away, looks at them,
 * follows when they walk off, and now and then crouch-bobs or swings, the way players say hi.
 */
public class FollowGoal extends Goal {
    private final ClaudeEntity g;
    private int ticks;
    private int bobTicks;

    public FollowGoal(ClaudeEntity g) {
        this.g = g;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return g.currentRoll() == ClaudeEntity.Roll.FRIENDLY && g.getTarget() != null && g.isAlive() && g.getTarget().level() == g.level(); // (in another world: nobody here to follow)
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        g.setShiftKeyDown(false);
    }

    @Override
    public void tick() {
        LivingEntity t = g.getTarget();
        if (t == null) return;
        ticks++;
        g.getLookControl().setLookAt(t, 30.0f, 30.0f);
        double d = g.distanceTo(t);
        if (d > 7) {
            if (ticks % 10 == 0) g.getNavigation().moveTo(t, 1.0);
        } else if (d < 3) {
            g.getNavigation().stop(); // close enough; a person doesn't stand inside you
        } else if (g.getNavigation().isDone() && ticks % 40 == 0) {
            g.getNavigation().moveTo(t, 0.6);
        }

        // Crouch-bob a few times now and then, like saying hi.
        if (bobTicks > 0) {
            bobTicks--;
            g.setShiftKeyDown(bobTicks % 6 < 3);
            if (bobTicks == 0) g.setShiftKeyDown(false);
        } else if (d < 8 && g.getRandom().nextInt(200) == 0) {
            bobTicks = 24;
        } else if (d < 6 && g.getRandom().nextInt(300) == 0) {
            g.swing(InteractionHand.MAIN_HAND);
        }
    }
}
