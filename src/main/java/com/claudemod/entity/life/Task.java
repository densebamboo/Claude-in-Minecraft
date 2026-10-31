package com.claudemod.entity.life;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/** One concrete step of Claude playing the game. WorkGoal executes them in order. */
public sealed interface Task {
    /** Walk until within {@code within} blocks of pos. */
    record GoTo(BlockPos pos, double within) implements Task {}
    /** Mine a block like a player: walk in reach, swing, crack, break, keep the drops. */
    record Break(BlockPos pos) implements Task {}
    /** Place a block (or plant a seed) from his inventory. */
    record Place(BlockPos pos, Item item, net.minecraft.core.Direction facing, net.minecraft.world.level.block.state.BlockState want) implements Task { public Place(BlockPos pos, Item item) { this(pos, item, null, null); } public Place(BlockPos pos, Item item, net.minecraft.core.Direction facing) { this(pos, item, facing, null); } } // want: the state a build plans there (a stair's facing, a door's hinge); his click is chosen to give it (WorkGoal.placeAimed) // facing: which way a chest's or furnace's front is to look (null: whatever the spot suggests)
    /** Open or close a door. */
    record UseDoor(BlockPos pos, boolean open) implements Task {}
    /** Open a chest, take a stack (take=true) or leave one item of {@code leave}, close it. */
    record Chest(BlockPos pos, boolean take, Item leave, int max) implements Task { public Chest(BlockPos pos, boolean take, Item leave) { this(pos, take, leave, Integer.MAX_VALUE); } } // max: how many of that item to take (what was asked for, not the chest's whole stock)
    /** Put everything he doesn't need into a chest. */
    record Deposit(BlockPos pos) implements Task { public Deposit { StackTraceElement[] st = Thread.currentThread().getStackTrace(); com.claudemod.ClaudeMod.LOGGER.info("[Claude chest] trip to {} queued by {}", pos == null ? "?" : pos.toShortString(), st.length > 2 ? st[2].getFileName() + ":" + st[2].getLineNumber() + " " + st[2].getMethodName() : "?"); } } // (which rule sent him to a chest: the user, "no debugging without exact logs")
    /** Load a furnace with input and fuel, wait for it, take the results. */
    record Smelt(BlockPos pos, Item input, int count) implements Task {}
    /** Put items and fuel into a furnace and walk away (it cooks while he does other things). */
    record FurnaceLoad(BlockPos pos, Item input, int count) implements Task {}
    /** Go back to a loaded furnace, wait for it to finish, and take the results. */
    record FurnaceTake(BlockPos pos) implements Task {}
    /** Fight an entity until it dies or he gives up. */
    record Attack(UUID target, int maxTicks) implements Task {}
    /** Get away from the mobs on him (low health, unarmed against a crowd, a creeper), toward the player; then heal up. */
    record Flee(int maxTicks) implements Task {}
    /** Turn dirt or grass into farmland with a hoe. */
    record Till(BlockPos pos) implements Task {}
    /** Throw items to the player, the way players hand each other things. */
    record Toss(Item item, int count) implements Task {}
    /** Eat something to heal. */
    record Eat(Item food) implements Task {}
    /** Pillar up {@code steps} blocks by placing blocks under himself; steps < 0 mines his pillar back down. */
    record Pillar(int steps) implements Task {}
    /** A drop off a ledge (4+ down) or a jump over a gap, from the lip cell to the landing (kind Leaps.DROP / WALK_JUMP / SPRINT_JUMP; down and hp as planned): ActionPath plans it, Leaps does it. */
    record Leap(BlockPos from, BlockPos land, int kind, int down, float hp) implements Task {}
    /** While tunnelling: if the tunnel broke into a cave, go explore it, then come back. */
    record CheckForCave() implements Task {}
    /** A torch on the side of a block (the wall is in direction {@code wall} from pos). */
    record WallTorch(BlockPos pos, net.minecraft.core.Direction wall) implements Task {}
    /** Look around and mine any ore he can see within {@code radius} (strip-mine walls, cave walls). */
    record MineVisibleOres(int radius) implements Task {}
    /** Walk over nearby dropped items so they get picked up. */
    record Pickup(int maxTicks) implements Task {}
    /** A step of his mine just dug: seal any wall/floor/ceiling opening into a cave so the mine stays an enclosed tube. */
    record SealStep(net.minecraft.core.BlockPos feet, net.minecraft.core.Direction dir, int width, int tall) implements Task {}
    /** Done with a tree: look it over and go back for any log still standing (a player doesn't leave floating tops). */
    record FinishTree(net.minecraft.core.BlockPos base, int round) implements Task {}
    record ChopTree(net.minecraft.core.BlockPos base, int round) implements Task {} // one tree, taken down like a player (WorkGoal.doChopTree)
    /** A sapling back where a felled tree stood, once the leaves have dropped one. */
    record Replant(net.minecraft.core.BlockPos pos, int tries) implements Task { public Replant(net.minecraft.core.BlockPos pos) { this(pos, 0); } }
    /** Stand still for a while (looking around, or at the player if {@code atPlayer}). */
    record Wait(int ticks, boolean atPlayer) implements Task {}
    /** Fish with a rod like a player: cast at the water, wait for the bite, reel in; until {@code catches} or the time runs out. */
    record Fish(BlockPos water, int catches, int maxTicks) implements Task {}
}
