package com.claudemod.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.claudemod.ClaudeMod;
import com.claudemod.Config;
import com.claudemod.context.PlayerProfile;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.body.Controls;
import com.claudemod.entity.body.PlayerNav;
import com.claudemod.entity.goals.FollowGoal;
import com.claudemod.entity.goals.WorkGoal;
import com.claudemod.entity.life.ClaudeInventory;
import com.claudemod.entity.life.Skills;
import com.claudemod.entity.life.Task;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Claude: a real player in your world, driven by an AI instead of a keyboard. His body is an actual server
 * player (inventory, hunger, reach, mining speed, attack cooldown, sneaking, death and drops all come from
 * Minecraft's own player code); his "hands" are {@link Controls} and his routes {@link PlayerNav}.
 * ClaudeLife plans, WorkGoal carries it out.
 */
public class ClaudeEntity extends ServerPlayer {
    public static final String NAME = "Claude";

    public enum Roll {
        /** Playing: running the task queue (WorkGoal). */
        WORK,
        /** Hanging out with them: stays close, looks at them (FollowGoal). */
        FRIENDLY
    }

    private Roll currentRoll = Roll.WORK;

    // His body's controls (the same calls the brain used with the old mob body).
    private final PlayerNav nav = new PlayerNav(this);
    private final Controls.Look look = new Controls.Look(this);
    private final Controls.Move move = new Controls.Move(this);
    private final Controls.Jump jump = new Controls.Jump(this);
    public final WorkGoal work = new WorkGoal(this);
    private final FollowGoal follow = new FollowGoal(this);
    private boolean workRunning, followRunning;

    // His life as a player (see ClaudeLife / WorkGoal).
    public final ClaudeInventory inventory = new ClaudeInventory(this.getInventory());
    /** Who queued each job (class.method:line, at the moment it went in): conflict and give-up lines name it (the user: task origin, for debugging). */
    public static final java.util.Map<Task, String> ORIGIN = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Task, String>(256, 0.75f, false) { @Override protected boolean removeEldestEntry(java.util.Map.Entry<Task, String> e) { return size() > 4000; } });
    private static final StackWalker WALK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    public static String originOf(Task t) { String o = t == null ? null : ORIGIN.get(t); return o == null ? "?" : o; }
    private static void noteOrigin(ClaudeEntity g, Task t) {
        if (t == null) return;
        String o = WALK.walk(st -> st.filter(f -> !java.util.ArrayDeque.class.isAssignableFrom(f.getDeclaringClass()) && !f.getClassName().startsWith("java.") && !f.getMethodName().startsWith("noteOrigin") && !f.getMethodName().contains("lambda$noteOrigin")).findFirst()
                .map(f -> f.getDeclaringClass().getSimpleName() + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?"));
        { String had = ORIGIN.get(t); if (had != null) o = had; else ORIGIN.put(t, o); } // (a job moved up the queue or put back keeps who first queued it)
        net.minecraft.core.BlockPos p = t instanceof Task.Break b0 ? b0.pos() : t instanceof Task.Place p0 ? p0.pos() : null;
        if (p == null) return;
        if (t instanceof Task.Place pl0 && (pl0.item().builtInRegistryHolder().is(net.minecraft.tags.ItemTags.SAPLINGS) || pl0.item() == net.minecraft.world.item.Items.TORCH)) return; if (t instanceof Task.Place && com.claudemod.entity.life.MineBlueprint.ore(g.level().getBlockState(p))) return; // (a fill queued on a cell still holding its ore: the hole's refill after the ore comes out, one job with its dig, not a second claim; 14 of 18 owner lines 11-08 were this, each cell dug once and filled once) // (a sapling back in the stump's spot, a torch: the job's own next step, not a claim)
        String who = o.replaceAll(":\\d+$", "").replaceAll("^(ClaudeLife\\.bp\\w+|WorkGoal\\.doMineVisibleOres)$", "his mine").replaceAll("^WorkGoal\\.(digThroughToward|doPickup)$", "a pickup").replaceAll("^ClaudeLife\\.(placeNextTo|pickUpTempStations|refillNotch)$", "his stations' cleanup") /* (its notch dug, the station taken up, the notch filled: one job; live 12:05 11-08, review 140; 00:10 11-12, review 216, stage 1536) */, kind = t instanceof Task.Break ? (com.claudemod.entity.life.MineBlueprint.ore(g.level().getBlockState(p)) ? "dug out for its ore" : "dug out") : "filled"; long now = System.currentTimeMillis();
        synchronized (CLAIM) {
            if (CLAIM.size() > 5000) CLAIM.clear();
            String[] was = CLAIM.get(p.asLong());
            if (was != null && "logged".equals(was[4]) && now - Long.parseLong(was[2]) < 60_000) return; // (said once a minute per block)
            if (was != null && !was[0].equals(who) && !was[1].equals(kind) && !(was[1].equals("dug out for its ore") && kind.equals("filled") && (g.blueprint == null || g.blueprint.roleAt(p) != com.claudemod.entity.life.MineBlueprint.Role.WALK)) /* (the ore out, then its hole patched: the mine's integrity order, one job; review 195) */ && !(was[1].startsWith("dug out") && kind.equals("filled") && who.equals("ClaudeLife.sealNotches")) /* (his access notch dug for a job, sealed once it's done: one job; a dig after the seal still counts. D2D review 042324-46; stage 1486) */ && now - Long.parseLong(was[2]) > 1000 && now - Long.parseLong(was[2]) < 60_000) {
                ClaudeMod.LOGGER.info("[Claude owner] {} claimed by two of his systems: {} wants it {} ({}), then {} wants it {} ({}), {} s apart", p.toShortString(), was[0], was[1], was[3], who, kind, o, (now - Long.parseLong(was[2])) / 1000);
                CLAIM.put(p.asLong(), new String[] {who, kind, String.valueOf(now), o, "logged"}); return;
            }
            CLAIM.put(p.asLong(), new String[] {who, kind, String.valueOf(now), o, ""});
        }
    }
    /** Each block's last claim: which system queued a dig or a fill there, and when (the user: cell ownership, for debugging). */
    private static final java.util.Map<Long, String[]> CLAIM = new java.util.HashMap<>();
    public final ArrayDeque<Task> tasks = new ArrayDeque<>() {
        @Override public void addFirst(Task t) { noteOrigin(ClaudeEntity.this, t); if (!com.claudemod.entity.life.Parked.blocks(ClaudeEntity.this, t) && !torchStuck(t) && com.claudemod.entity.life.Gate.admits(ClaudeEntity.this, t)) super.addFirst(t); } // (a refused upkeep patch, parked: not taken again till something changed)
        @Override public void addLast(Task t) { noteOrigin(ClaudeEntity.this, t); if (!com.claudemod.entity.life.Parked.blocks(ClaudeEntity.this, t) && !torchStuck(t) && com.claudemod.entity.life.Gate.admits(ClaudeEntity.this, t)) super.addLast(t); }
        /** A torch spot he got stuck on (the 45 s rung put it on record with its cause): not queued again while that holds (stage 799). */
        private boolean torchStuck(Task t) { if (!(t instanceof Task.WallTorch w) || attempts == null || !attempts.skipped(com.claudemod.entity.life.Attempts.Kind.PLACE, w.pos(), tickCount, 0)) return false; if (tickCount - torchStuckSaid > 200) { torchStuckSaid = tickCount; ClaudeMod.LOGGER.info("[Claude life] the torch at {}: stuck on it before, not queued again while that holds (ClaudeEntity.tasks)", w.pos().toShortString()); } return true; }
        private int torchStuckSaid = -100000;
        @Override public void clear() { dropNote(size(), "clear"); super.clear(); }
        @Override public boolean removeIf(java.util.function.Predicate<? super Task> f) { int n = size(); boolean r = super.removeIf(f); dropNote(n - size(), "removeIf"); return r; }
    };
    private static java.util.Map<String, Integer> dropNoted; // (static: fields added live start null)
    /** 3+ tasks off his queue at once: who did it (live 20:47 11-06: his 12 route legs gone 40 ms after they were queued, no line said by what). */
    private void dropNote(int n, String how) {
        if (n < 3) return;
        var st = Thread.currentThread().getStackTrace(); String at = "?";
        for (int i = 3; i < st.length; i++) { String f = st[i].getFileName(); if (f != null && !f.equals("ClaudeEntity.java")) { at = f + ":" + st[i].getLineNumber() + (i + 1 < st.length ? " < " + st[i + 1].getFileName() + ":" + st[i + 1].getLineNumber() : ""); break; } }
        if (at.startsWith("ClaudeLife.java") && st.length > 3 && "clearTasks".equals(st[3].getMethodName())) return; // (clearTasks logs its own)
        if (dropNoted == null) dropNoted = new java.util.HashMap<>();
        Integer last = dropNoted.get(at); if (last != null && tickCount - last < 100 && tickCount >= last) return;
        dropNoted.put(at, tickCount);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude tasks] {} task(s) off his queue at once by {} at {} (ClaudeEntity.tasks)", n, how, at);
    }
    public final ArrayDeque<Task> stashedTasks = new ArrayDeque<>();
    public final ArrayDeque<Skills.Step> skillQueue = new ArrayDeque<>();
    public boolean busy = false;
    public boolean planning = false;
    public int planStartTick = -100000; // when the plan being thought up was asked for (a lost answer can't leave him thinking forever)
    public int nextPlanTick = 100;
    public int farTicks = 0;
    public UUID urgentAttacker = null;   // a mob to fight before anything else
    public int foodHuntTick = -100000;
    public int tidyTick = -100000;
    public BlockPos progressPos = null;  // where he was when he last got anywhere (moved, or got something)
    public int progressTick = 0, progressItems = -1, stuckLevel = 0;
    public String stuckNote = "";        // he knows he's stuck (and says so)       // last time he checked his stairs against their pattern   // last time he went hunting for his own food
    public boolean approaching = false;  // walking up to them on purpose (to give them something)
    public boolean staying = false;      // they told him to wait here
    public String goal = "";
    public int greetAt;                   // just joined: say hi at this tick
    public String playerGoal = "";
    public String lessonLast = "", lessonPlaces = ""; // the lesson's last instruction in their words, and its places (LessonWords)
    public String wornChoice = "";       // their wear / take_off choices, "feet=minecraft:golden_boots;head=none;": equipArmor leaves those slots alone (ClaudeLife.held)
    public String lessonOpen = "";       // a lesson of theirs he's in (their words when it opened), till they end it (ExactSpot)       // what the player wants the two of them to do
    public String pausedGoal = "";
    public BlockPos adoptMineAt = null;
    public java.util.List<BlockPos> pastMineEntrances = new java.util.ArrayList<>(); // mines he's had (never just thrown away)
    public java.util.List<BlockPos> storageChests = new java.util.ArrayList<>(); // his storage: every chest of it, remembered
    public java.util.List<Direction> pastMineDirs = new java.util.ArrayList<>();

    /** Fields added by a live code push start out null on the running body (no initializer runs): give them their empty value. */
    public void ensureLiveFields() {
        if (furnaceReadyAt == null) furnaceReadyAt = new java.util.HashMap<>();
        if (pastMineEntrances == null) pastMineEntrances = new java.util.ArrayList<>();
        if (storageChests == null) storageChests = new java.util.ArrayList<>();
        if (pastMineDirs == null) pastMineDirs = new java.util.ArrayList<>();
    }  // "this is your mine": the staircase there becomes his       // the job he was on when they said "follow me" (picked back up on "continue")
    public int playerGoalTick = -100000;
    public String lastSkillLine = "";
    public Skills.Step lastStep = null;
    public String lastPlan = "";
    /** Why he's doing what he's doing: the reasoning behind his current plan (or "they asked: ..."). */
    public String lastWhy = "";
    public int planRepeats = 0;
    public final Map<String, Integer> failCount = new HashMap<>();  // skill -> failures in a row
    public final Map<String, String> failReason = new HashMap<>(); // skill -> last failure message
    public boolean commandActive = false;
    public boolean resumeAfterCommand = false;
    public final List<String> commandResults = new ArrayList<>();
    /** Their standing instructions: torches_while_digging, torch_every, torch_side, tunnel_width, stay_close, build_with. */
    public final Map<String, String> prefs = new LinkedHashMap<>();
    public String pref(String key, String fallback) { return prefs.getOrDefault(key, fallback); }
    public int walkedToTableTick = -1000;
    public int woodTripTick = -100000;   // last time he stocked up on wood before mining
    public int charcoalTick = -100000;   // last time he made charcoal for torches
    public int askedSizeTick = -100000;  // last time he asked how wide/tall to make a new mine
    public int lastDigY = Integer.MIN_VALUE, lastDigTick = -100000; // staircase progress check
    public final java.util.Set<Direction> badDigDirs = java.util.EnumSet.noneOf(Direction.class); // ways down that got stuck
    public int awaitingAnswerUntil = 0;  // asked them a question (found a cave): hold off on his own work until this tick
    public int stoppedTick = -1;         // last stop / new order; plans started before it are thrown away
    public int ongoingSetTick = -1;      // when they last set (or cleared) an open-ended job
    public final com.claudemod.entity.life.Attempts attempts = new com.claudemod.entity.life.Attempts(this); // what failed where lately, by kind (was one map for every kind: "unreachable")
    public int tablePlacedTick = -1;
    /**
     * Temporary workstations: crafting tables / furnaces he put down out in the field for a quick job; he picks
     * them back up when he moves on. Anything at a base, or that the player placed, is a dedicated workstation:
     * reused, never taken.
     */
    public final List<BlockPos> tempStations = new ArrayList<>();
    public int retoolTick = -100000; // last time he stopped to make a tool (not bare-handed)
    public int gatherForCraftTick = -100000; // went to gather what a craft was missing
    public java.util.Map<String, Integer> expandTicks = new java.util.HashMap<>(); // per thing made: when a craft or smelt last expanded into its chain
    public final java.util.Set<BlockPos> notches = new java.util.HashSet<>(); // stations set into the mine wall: refill when taken
    public int digStalls; // dig_down calls in a row that got no deeper
    public int sideStepTries; // short tunnels sideways off a lake since he last got deeper
    public int askedMineTick = -100000; // asked: back to the old mine or a new one?
    public int mineReturnTick = -100000;
    public int shovelTryTick = -100000; // last time he tried to make the right tool on the spot
    public int dryWalkTick = -100000; // walked off to dry ground to start a mine
    public int packTick = -100000; // last time he counted up and gathered a mining trip's materials
    public int exploreTick = -100000; public double exploreAngle; // keeps a search heading one way
    public int scaffoldDigTick = -100000; // dug dirt to tower with
    public int sideJobTick = -100000; // last side job done while a furnace cooked
    public boolean justRespawned; // died a moment ago: get his things back first
    public final java.util.Set<BlockPos> landingCells = new java.util.HashSet<>(); // the landings at his mine's turns
    public final java.util.Set<BlockPos> spottedDiamonds = new java.util.HashSet<>();
    public final java.util.List<BlockPos> spottedMineshafts = new java.util.ArrayList<>(); // to offer them once the job's done
    public boolean mineshaftOffered = false; // diamond ore he's seen (to come back for)
    public final java.util.Set<BlockPos> pendingPatches = new java.util.HashSet<>(); // holes he couldn't fill (no blocks on him): filled when he's back with some
    public final java.util.Set<BlockPos> myStations = new java.util.HashSet<>();
    public BlockPos sleptAt; // the bed he's in tonight: the morning takes it up even when the game's own night skip woke him
    public java.util.Map<BlockPos, Integer> furnaceReadyAt = new java.util.HashMap<>(); // furnaces he loaded, and when the batch is done // every table/furnace he put down himself (this session)
    public final java.util.Set<BlockPos> mineCells = new java.util.HashSet<>(); // every cell of his stairs/tunnel, as dug (the real shape, turns and all)
    public final java.util.List<BlockPos> mineSpine = new java.util.ArrayList<>(); // his walking line through it, entrance to end
    public int lightCheckTick = -100000; // last look for dark stretches of his stairs
    public boolean recoverPending; // his things are where he died but mobs are still there
    public int stationWalkTick = -100000; // last time he walked to an existing station (no trekking back and forth)
    /** The action he's doing right now (WorkGoal keeps it; it's no longer in `tasks`). */
    @Nullable public Task currentTask;
    /** Set to make WorkGoal drop the current action (something more important was put first). */
    public boolean dropCurrent;
    public final ArrayDeque<BlockPos> pillarBlocks = new ArrayDeque<>();
    public final Map<Item, Integer> lastGiven = new HashMap<>();
    /** Items he tossed to them on purpose: theirs now, never his to pick back up (his own death drops are fine). */
    private final java.util.Set<UUID> gifts = new java.util.HashSet<>();
    public void markGift(net.minecraft.world.entity.item.ItemEntity e) { gifts.add(e.getUUID()); if (gifts.size() > 200) gifts.clear(); }
    public boolean isGift(net.minecraft.world.entity.item.ItemEntity e) { return gifts.contains(e.getUUID()); }
    public void unGift(net.minecraft.world.entity.item.ItemEntity e) { gifts.remove(e.getUUID()); } // a drop he gave up on, his to get again (recover_left)
    public BlockPos projectOrigin = null; // a build he is doing away from his own place
    public String projectDim = null; // the dimension that build is in (an overworld origin read as a Nether cell refused his digs there, Day to Day)
    // his mine in progress: mining continues from its face instead of starting new holes
    public Direction tunnelDir = null;
    public BlockPos mineFace = null;
    public BlockPos stairStart = null;    // top of his staircase mine
    public Direction stairDir = null;
    public Direction mineDir = null;
    public boolean mineIsTunnel = false;
    public int mineReturnTries = 0;
    public double mineReturnBest = Double.MAX_VALUE; // closest he got while walking back to the mine
    public int mineTunnelBlocks = 0;
    public int ribPhase = 0;               // blocks of main tunnel left before the next branch (from a survey)

    /**
     * A mine's own proportions, fixed when the mine is started (from their answer/settings) or read from it (a
     * survey). Every bit of digging in that mine uses these, so it never grows in a pattern it didn't already have.
     */
    public static final class MineShape {
        public int width, height, branchEvery, branchLength, torchEvery;
        public String torchSide;

        public MineShape(int width, int height, int branchEvery, int branchLength, int torchEvery, String torchSide) {
            this.width = width; this.height = height; this.branchEvery = branchEvery; this.branchLength = branchLength;
            this.torchEvery = torchEvery; this.torchSide = torchSide;
        }

        @Override public String toString() {
            return width + " wide, " + height + " tall, branches every " + branchEvery + " (" + branchLength + " long), torches every "
                    + torchEvery + " on the " + torchSide;
        }
    }
    /** The shape of his mine in progress (null: no mine, or one he hasn't started digging yet). */
    @Nullable public MineShape mineShape;
    @Nullable public com.claudemod.entity.life.FarmBlueprint farm; // his farm as one plan (null until he starts one)
    @Nullable public com.claudemod.entity.life.BaseNook nook; // his base at the foot of the stairs as one plan (null until the storage goes in)
    public java.util.List<com.claudemod.entity.life.BaseNook> nooks = new java.util.ArrayList<>(); // every base nook of his, one per branch level (nook: the one he's using, the nearest by his tunnels: ClaudeLife.nookFocus)
    @Nullable public com.claudemod.entity.life.BaseNook nookOld; // a nook sited wrong (beside the stairs), being moved: its things carried to the new one, then it comes down and is walled in
    @Nullable public com.claudemod.entity.life.MineBlueprint blueprint; // the mine as one blueprint (mine_model = blueprint, the default); null until he starts one
    @Nullable public com.claudemod.entity.life.HouseBlueprint house; // his house as one blueprint (house_model = blueprint, the default); null until he picks the site
    @Nullable public BlockPos surveyedAt;  // where he last read a mine's layout
    public int autoRibs = 0;
    public final List<BlockPos> exploredCaves = new ArrayList<>();
    public UUID lastGuardTarget = null;
    public int lastGuardTick = -100000;
    public int gearTick = -100000; // last time he went for his kit (no looping on a kit he can't finish)
    public java.util.Map<String, int[]> lineRepeats; // each step line: {when it last came up, times in a row within seconds} (live-added: starts null)

    @Nullable private ServerPlayer owner;
    @Nullable private UUID ownerId;
    private boolean mineLoaded = false;
    private boolean brainCrashed = false;
    // holding left click on a block (real survival mining through the game's own block-breaking code)
    @Nullable private BlockPos digging;
    private float digProgress; public float digProgressNow() { return digProgress; } // (the dug way to air: a dig rising is progress, Watchdog.airRoute)

    public ClaudeEntity(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
        // defaults, listed so his prompts see them; they can change any of these in chat
        prefs.put("stay_close", "yes");
        prefs.put("torches_while_digging", "yes");
        prefs.put("tunnel_width", "2");
        prefs.put("tunnel_height", "4");
        prefs.put("torch_side", "right"); // the right-hand wall going down/in
    }

    // ------------------------------------------------------------------ the old mob-body calls, player-backed

    /** Cover now (a tower, or dug in and lidded): true if he could make some. */
    public boolean shelter() { return work.shelter(); }
    public boolean holdingNight() { return work.holdingNight(); }
    /** Why the last cover attempt said no, if it said so this tick; else null. */
    public String noCoverWhyNow() { return work.lastNoCoverTick == tickCount ? work.lastNoCoverWhy : null; }
    public boolean noCoverGapNow() { return work.coverGapNow(); }
    public boolean holdingCover() { return work.holdingCover(); }
    public boolean buildingCover() { return work.buildingCover(); }
    public boolean coverCell(BlockPos p) { return work.coverCell(p); }
    public java.util.List<com.claudemod.entity.life.Task> coverTasks() { return work.coverTasks(); }

    public PlayerNav getNavigation() { return nav; }
    public Controls.Look getLookControl() { return look; }
    public Controls.Move getMoveControl() { return move; }
    public Controls.Jump getJumpControl() { return jump; }

    /** The player he plays with. */
    @Nullable public LivingEntity getTarget() { return owner; }
    public void setTarget(@Nullable LivingEntity target) {
        owner = target instanceof ServerPlayer sp && !(target instanceof ClaudeEntity) ? sp : null;
        ownerId = owner != null ? owner.getUUID() : ownerId;
    }

    public Roll currentRoll() { return currentRoll; }
    public void setRoll(Roll roll) { this.currentRoll = roll; }

    // ------------------------------------------------------------------ ticking: brain, then hands, then real player physics

    private int zeroHpTicks;
    @Override
    public boolean hasLineOfSight(net.minecraft.world.entity.Entity e) { // ponytail: the user 14:25 "he should be able to see past transparent blocks": vanilla's COLLIDER clip stops at glass/leaves/fences; his eyes use Perception.clear (eye to eye) like everything else he sees
        if (e.level() != level() || e.distanceToSqr(this) > 128 * 128 || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) return super.hasLineOfSight(e);
        return com.claudemod.entity.life.Perception.clear(sl, getEyePosition(), e.getEyePosition());
    }
    @Override
    public void tick() {
        if (!level().isClientSide) com.claudemod.entity.life.CombatLog.tick(this);
        if (!level().isClientSide && portalProcess != null && portalProcess.isInsidePortalThisTick() && portalProcess.getPortalTime() > 0 && portalProcess.getPortalTime() % 20 == 0 && !"enter_portal".equals(skillQueue.isEmpty() ? null : skillQueue.peekFirst().skill())) /* in a portal with no portal step: what held him there, each second (D2D death 11:33:46 11-12: a walk to his table, 4 s silent, then the Nether; no trace of why) */
            com.claudemod.ClaudeMod.LOGGER.info("[Claude portal] {} ticks in the portal at {} with no portal step (at {}, v {}, on ground {}, task {}, path next {}, queue {}) (ClaudeEntity.tick)", portalProcess.getPortalTime(), portalProcess.getEntryPosition().toShortString(), String.format("%.2f %.2f %.2f", getX(), getY(), getZ()), String.format("%.2f %.2f %.2f", getDeltaMovement().x, getDeltaMovement().y, getDeltaMovement().z), onGround(), currentTask, getNavigation().getPath() == null || getNavigation().getPath().isDone() ? "none" : getNavigation().getPath().getNextNodePos().toShortString(), tasks.stream().limit(3).toList());
        if (isChangingDimension()) { hasChangedDimension(); com.claudemod.entity.life.PortalMap.arrived(this); com.claudemod.entity.life.ExactSpot.reset(this); com.claudemod.entity.nav.Route.forgetTrail(this); com.claudemod.entity.life.FarSight.forgetSurfaces(this); /* a spot given in the other dimension means nothing here; nor does the trail */ if (!level().getBlockState(blockPosition()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) && !level().getBlockState(blockPosition().above()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) { tasks.clear(); com.claudemod.director.ClaudeLife.dropCurrent(this); nav.stop(); move.clear(); /* (the straight-line step target too: test 819->820, he walked 3 blocks toward the overworld spot of the walk he was on, facing away from the blaze) */ for (var it = skillQueue.iterator(); it.hasNext(); ) if ("enter_portal".equals(it.next().skill())) it.remove(); ClaudeMod.LOGGER.info("[Claude life] moved to {} not by a portal: the walks and portal steps planned in the other world dropped, re-planning here (ClaudeEntity.tick)", level().dimension().location().getPath()); } /* ponytail: live 13:55, respawned in the overworld, recover_drops queued enter_portal + its overworld route; a /tp into the Nether kept it, he pillared to y 80 and died in lava */ stoppedTick = tickCount; /* ponytail: live 13:30, a plan asked for in the overworld landed after the tp into the Nether: go_to the overworld portal 70 68 65 there */
            if (level().getBlockState(blockPosition()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) { /* where he came out: his way back */
                if (level().dimension() == net.minecraft.world.level.Level.NETHER) ClaudeLife.netherPortal.put(getUUID(), blockPosition().immutable());
                else if (level().dimension() == net.minecraft.world.level.Level.OVERWORLD) ClaudeLife.hisPortal.put(getUUID(), blockPosition().immutable());
                ClaudeMod.LOGGER.info("[Claude life] came out of the portal at {} in {}: noted as his way back", blockPosition().toShortString(), level().dimension().location().getPath()); } } /* a real client confirms its arrival in the new dimension at once; with no client the flag stayed set and isInvulnerableTo was true for everything (live 18:12: ghast fireballs, fire, arrows "not taken" all over the Nether) */
        com.claudemod.entity.life.HuntPatrol.tick(this); // (a hunt's walk stops for an enderman coming into view)
        com.claudemod.entity.life.PortalMap.tick(this); // (after the arrival above: the side he left is still the one he stood in last)
        if (!level().isClientSide && getHealth() <= 0 && !ClaudeSpawner.pendingRespawn(this)) { // health 0 and no death under way: it ends in one (World 15 22:48: "health 0 ... no harm (not taken)" in lava, no death event, then the server hung)
            if (++zeroHpTicks > 60) { zeroHpTicks = 0; ClaudeMod.LOGGER.warn("[Claude] health 0 for 3 s with no death under way at {}: dying now (ClaudeEntity.tick)", blockPosition().toShortString()); try { die(damageSources().generic()); } catch (Throwable e) { ClaudeMod.LOGGER.error("[Claude] the forced death failed: {}", e.toString()); ClaudeSpawner.respawnSoon(this); } }
        } else zeroHpTicks = 0;
        if (getServer() != null && getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            { int view = getServer().getPlayerList().getViewDistance(); var ci = ClientInformation.createDefault(); // a player's client asks for its render distance; the default's 2 chunks left him blind past ~40 blocks (live 14:02-14:21 11-04: 31 tree-scouting legs, "no trees within 100")
              if (requestedViewDistance() != view) updateOptions(new ClientInformation(ci.language(), view, ci.chatVisibility(), ci.chatColors(), ci.modelCustomisation(), ci.mainHand(), ci.textFilteringEnabled(), ci.allowsListing())); }
            serverLevel().getChunkSource().move(this); // he loads the chunks around him, like any player
            if (level().dimension() != net.minecraft.world.level.Level.OVERWORLD) serverLevel().getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.PORTAL, chunkPosition(), 3, blockPosition()); // (away from home: the ticket a portal leaves, renewed, or his chunks stop ticking and he freezes)
        }
        if (isAlive()) {
            try {
                brainTick();
            } catch (RuntimeException e) { // a bug in his thinking must never take the server down with it
                if (!brainCrashed || tickCount % 1200 < 20) ClaudeMod.LOGGER.error("[Claude] his brain hit an error (he'll keep going)", e); // (once a minute while it keeps failing: after the first, a crash every tick froze him with no log)
                brainCrashed = true;
                tasks.clear();
            }
        }
        Controls.apply(this, look, move, jump, nav);
        if (!level().isClientSide) { net.minecraft.world.entity.monster.EnderMan aim = currentTask instanceof com.claudemod.entity.life.Task.Attack a0 && level() instanceof net.minecraft.server.level.ServerLevel sl0 && sl0.getEntity(a0.target()) instanceof net.minecraft.world.entity.monster.EnderMan e0 && e0.isAlive() ? e0 : null; boolean hunt = aim != null || com.claudemod.director.ClaudeLife.endermanTime(this); /* (an enderman he has set out to fight is the hunt, rods or not: World 17 19:26, pearls before rods with the fortress out of reach, 25 min with his eyes held 25 deg under the one he was after) */ net.minecraft.world.entity.monster.EnderMan free = null; boolean onMe = false; // ponytail: the user 13:30 "only engage 1 enderman at a time": in the hunt his eyes may meet only the nearest in sight, and none while one is on him (13:28: four stared at, four on him, dead in 2 s; live 14:55: every tick, the look control re-aims each tick, so an every-2nd-tick guard left odd ticks staring, 10 on him, dead)
            if (hunt) for (var e : level().getEntitiesOfClass(net.minecraft.world.entity.monster.EnderMan.class, getBoundingBox().inflate(64))) { if (!e.isAlive()) continue; long angryAt = e.getPersistentData().getLong("claudeAngryAt"); if (e.getTarget() == this) { onMe = true; e.getPersistentData().putLong("claudeAngryAt", level().getGameTime()); } else if (angryAt > 0 && level().getGameTime() - angryAt < 200) onMe = true; /* ponytail: the user 20:13 "not if he keeps dying": live 20:12 a second one angered by his gaze in the Pickup with the first alive; one that just lost him (a teleport) still counts 10 s */ else if (hasLineOfSight(e) && (free == null || e.distanceToSqr(this) < free.distanceToSqr(this))) free = e; }
            if (onMe) free = null; else if (aim != null && hasLineOfSight(aim)) free = aim; /* (the one he is after is the one his eyes may meet, not the nearest in sight) */ /* the user 01:40: endermen are avoided unless it's the pearl stage and he's geared; a player never meets their eyes (vanilla's stare check: view within 0.025/dist of the eyes, in sight) */
            net.minecraft.world.phys.Vec3 view = getViewVector(1.0F).normalize();
            java.util.List<net.minecraft.world.entity.monster.EnderMan> ems = level().getEntitiesOfClass(net.minecraft.world.entity.monster.EnderMan.class, getBoundingBox().inflate(64)); int angry = 0; for (var e : ems) if (e.isAlive() && e.getTarget() == this) angry++;
            for (var em : ems) { if (!em.isAlive() || em.getTarget() != this || em.getPersistentData().getBoolean("claudeAngry")) continue; // ponytail: the user 18:55 "why did he aggro 3 or 4 enderman at once": each new anger logged once with its cause (hit by him / his gaze / neither), tag kept in the mob's own data (no new field, hotswap-safe)
                em.getPersistentData().putBoolean("claudeAngry", true);
                net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(em.getX() - getX(), em.getEyeY() - getEyeY(), em.getZ() - getZ()); double d = Math.max(1e-3, to.length()), dot = view.dot(to.normalize());
                ClaudeMod.LOGGER.info("[Claude fight] an Enderman {} off turned on him: {}; his gaze {} deg off its eyes (stare cone {} deg), in sight {}, {} angry at him in 64, hunt {}, task {} (ClaudeEntity.tick)", (int) d,
                        em.getLastHurtByMob() == this ? "he hit it" : dot > 1.0 - 0.025 / d ? "he looked it in the eyes" : "not hit, not stared at by the check now (stared a tick earlier, or something else)",
                        String.format("%.1f", Math.toDegrees(Math.acos(Math.min(1, dot)))), String.format("%.1f", Math.toDegrees(Math.acos(1 - 0.025 / d))), hasLineOfSight(em),
                        angry, hunt, tasks.isEmpty() ? "none" : tasks.peek().getClass().getSimpleName());
            }
            for (var em : level().getEntitiesOfClass(net.minecraft.world.entity.monster.EnderMan.class, getBoundingBox().inflate(64), e -> e.isAlive() && e.getTarget() != this)) {
                net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(em.getX() - getX(), em.getEyeY() - getEyeY(), em.getZ() - getZ());
                double d = to.length(); if (d < 1e-3 || em == free) continue;
                if (em.getPersistentData().getBoolean("claudeAngry")) em.getPersistentData().remove("claudeAngry"); // calm again: the next anger logs anew
                if (view.dot(to.normalize()) > 1.0 - 0.025 / d * 1.5 && hasLineOfSight(em)) { /* every one in the cone this tick, not only the first: the view is read again after each turn */ if (hunt && tickCount % 40 == 0) ClaudeMod.LOGGER.info("[Claude fight] eyes off the enderman {} off: one at a time ({}) (ClaudeEntity.tick)", (int) d, onMe ? "one is on me" : "after the nearest"); setXRot(Math.min(90f, Math.max(getXRot(), (float) Math.toDegrees(Math.atan2(em.getEyeY() - getEyeY(), Math.sqrt(to.x * to.x + to.z * to.z))) * -1f + 25f))); setYHeadRot(getYRot()); view = getViewVector(1.0F).normalize(); }
            }
        }
        super.tick();  // the light per-tick player stuff (block breaking progress, menus, criteria)
        doTick();      // the full player tick: movement physics, hunger, item pickup, using items
        // fall damage, the way the server applies it to a real player (from their movement packets): without this he never took a fall hit in two days of logs and walked off a 50-block drop into a cave
        if (isAlive() && !isSleeping()) doCheckFallDamage(getX() - xo, getY() - yo, getZ() - zo, onGround());
        traceMovement();
    }

    // ---- movement trace (the user: see where he stalls, shuffles or spins while walking; one "[Claude move]" line per event) ----
    private net.minecraft.world.phys.Vec3 mtLastPos, mtLastDir; private float mtLastYaw; private int mtStall, mtFlips, mtFlipStart, mtSpinStart; private double mtSpin, mtSpinPath; private net.minecraft.world.phys.Vec3 mtSpinFrom; private int mtQuietUntil;
    private BlockPos mtLoopAt; private int mtLoopN; // shuffles/spins on one block: the 2nd is proof the walk isn't working (static rules: fields added live start null)
    /** A shuffle or spin is no progress; the 2nd on the same block hands over to his own way out at once (the 20 s clock wasted 40 s at -303,87,-63). */
    private void mtLoop(com.claudemod.entity.life.Task.GoTo go, net.minecraft.world.level.pathfinder.Node nx) {
        BlockPos b = blockPosition();
        if (!b.equals(mtLoopAt)) { mtLoopAt = b; mtLoopN = 0; }
        if (go != null && nx != null && work.mobInCell(go, new BlockPos(nx.x, nx.y, nx.z))) { mtLoopN = 0; return; } // a mob standing where his next step goes: dealt with as a choice, not the same plan again (World 17 01:29, two endermen in the hole)
        if (++mtLoopN < 2 || go == null) return;
        mtLoopN = 0;
        String nodeBlocks = nx == null ? "none" : level().getBlockState(new BlockPos(nx.x, nx.y - 1, nx.z)).getBlock().getName().getString() + " under / " + level().getBlockState(new BlockPos(nx.x, nx.y, nx.z)).getBlock().getName().getString() + " / " + level().getBlockState(new BlockPos(nx.x, nx.y + 1, nx.z)).getBlock().getName().getString() + " head";
        ClaudeMod.LOGGER.info("[Claude move] looping at {} on the walk to {}: node {} is {}; above him {}: his own way instead (ClaudeEntity.mtLoop)", b.toShortString(), go.pos().toShortString(), nx == null ? "none" : nx.x + "," + nx.y + "," + nx.z, nodeBlocks, level().getBlockState(b.above(2)).getBlock().getName().getString());
        ClaudeMod.LOGGER.info("[Claude move] the loop at {}: he at {}, steering {}; his path {} (ClaudeEntity.mtLoop)", b.toShortString(), String.format("%.2f %.2f %.2f", getX(), getY(), getZ()), nav.steerWhy, nav.around());
        com.claudemod.director.ClaudeLife.loopStuck(this, currentTask, "the 2nd shuffle on " + b.toShortString()); // (as the watchdog's own 20 s step: stuck level, the goal pinned, the way out)
    }
    /** A spin is a loop only when the turning gets him nowhere: 270+ degrees in 2 s and little ground made. A turn to face a mob, a corner, a U-turn on the way are turns, not spins (11-08: 170 of 290 live spins covered 2.5-5.9 blocks net). */
    public static boolean spinLoop(double turnedDeg, double path, double net) {
        return turnedDeg > com.claudemod.entity.life.Weights.w("move.spin.deg", 270) && (net < com.claudemod.entity.life.Weights.w("move.spin.net_min", 1.5) || net < com.claudemod.entity.life.Weights.w("move.spin.net_ratio", 0.5) * path);
    }
    private void traceMovement() {
        if (!isAlive() || isSleeping() || level().isClientSide) return;
        boolean walking = work != null && (currentTask instanceof com.claudemod.entity.life.Task.GoTo || (nav.getPath() != null && !nav.isDone()));
        net.minecraft.world.phys.Vec3 pos = position();
        if (mtLastPos == null || !walking) { mtLastPos = pos; mtLastYaw = getYRot(); mtStall = 0; mtFlips = 0; mtSpin = 0; mtSpinPath = 0; mtSpinFrom = pos; mtSpinStart = tickCount; mtFlipStart = tickCount; return; }
        net.minecraft.world.phys.Vec3 d = pos.subtract(mtLastPos); double h = Math.sqrt(d.x * d.x + d.z * d.z);
        String where = blockPosition().toShortString(), doing = currentTask == null ? "-" : currentTask.getClass().getSimpleName();
        String to = currentTask instanceof com.claudemod.entity.life.Task.GoTo go ? go.pos().toShortString() : "-";
        var nx = nav.getPath() != null && !nav.isDone() ? nav.getPath().getNextNode() : null; String node = nx == null ? "none" : nx.x + "," + nx.y + "," + nx.z;
        // stall: walking, hardly moving for 10+ ticks
        if (h < 0.03 && onGround()) { if (++mtStall == 10 && tickCount > mtQuietUntil) { ClaudeMod.LOGGER.info("[Claude move] stall at {} doing {} to {} next node {} (y {})", where, doing, to, node, String.format("%.2f", getY())); mtQuietUntil = tickCount + 20; if (nx != null && currentTask instanceof com.claudemod.entity.life.Task.GoTo g1) work.mobInCell(g1, new BlockPos(nx.x, nx.y, nx.z)); } } else mtStall = 0;
        // reversal: the way he moves flips back on itself, 3 times within 2 s
        if (h > 0.05) { net.minecraft.world.phys.Vec3 dir = new net.minecraft.world.phys.Vec3(d.x / h, 0, d.z / h);
            if (mtLastDir != null && dir.dot(mtLastDir) < -0.5) { if (tickCount - mtFlipStart > 40) { mtFlipStart = tickCount; mtFlips = 0; } if (++mtFlips == 3 && tickCount > mtQuietUntil) { ClaudeMod.LOGGER.info("[Claude move] shuffle at {} doing {} to {} next node {}", where, doing, to, node); mtQuietUntil = tickCount + 40;  mtLoop(currentTask instanceof com.claudemod.entity.life.Task.GoTo g0 ? g0 : null, nx); } }
            mtLastDir = dir; }
        // spin: more than 270 degrees of turning within 2 s while walking
        mtSpin += Math.abs(net.minecraft.util.Mth.wrapDegrees(getYRot() - mtLastYaw)); mtSpinPath += h; if (mtSpinFrom == null) mtSpinFrom = pos;
        if (tickCount - mtSpinStart > 40) { mtSpinStart = tickCount; mtSpin = 0; mtSpinPath = 0; mtSpinFrom = pos; }
        else if (spinLoop(mtSpin, mtSpinPath, Math.hypot(pos.x - mtSpinFrom.x, pos.z - mtSpinFrom.z)) && tickCount > mtQuietUntil) { ClaudeMod.LOGGER.info("[Claude move] spin at {} doing {} to {} next node {} ({} deg in {} ticks)", where, doing, to, node, (int) mtSpin, tickCount - mtSpinStart); mtSpin = 0; mtSpinPath = 0; mtSpinFrom = pos; mtSpinStart = tickCount; mtQuietUntil = tickCount + 40;  mtLoop(currentTask instanceof com.claudemod.entity.life.Task.GoTo g0 ? g0 : null, nx); }
        mtLastPos = pos; mtLastYaw = getYRot();
    }

    private void brainTick() {
        resolveOwner();
        unstick();
        if (owner != null) {
            if (!mineLoaded) { mineLoaded = true; loadMine(owner); }
            if (tickCount % 200 == 0) saveMine(owner); // every 10s: where his mine is and its shape survive anything
            ClaudeLife.tick(this, owner);
        }
        // his two ways of being: playing (task queue) or hanging out
        if (work.canUse()) { workRunning = true; work.tick(); }
        else if (workRunning) { workRunning = false; work.stop(); }
        com.claudemod.entity.life.Consent.tick(this); // their go-ahead runs out; the ward after a break they had him make
        com.claudemod.entity.life.Perception.tick(this); // what he sees now (after his work: a turn to a sound wins over the idle head)
        com.claudemod.entity.life.ChestLedger.tick(this); // his chests in sight: one gone, or opened in front of him
        com.claudemod.entity.life.WatchBuild.tick(this);
        com.claudemod.entity.life.ExactSpot.tick(this);
        com.claudemod.entity.life.CopyMode.tick(this); // showing him: watching their hands // their trail as he sees it; a walk onto their cell stops short the tick they're in the way (no pushing) // watching them build: facing it, a spot that sees it, the look round after
        if (follow.canUse()) { followRunning = true; if (!work.reflexes()) follow.tick(); } // (fire, air, lava first while hanging out too: WorkGoal.reflexes)
        else if (followRunning) { followRunning = false; follow.stop(); }
    }

    /** Whoever he plays with: the same person after they respawn (a new player object), else the nearest real player. */
    private void resolveOwner() {
        if (owner != null && !owner.isRemoved() && !owner.hasDisconnected()) return; // (in another world too: through a portal on his own he's still theirs, and his life goes on there)
        ServerPlayer found = null;
        if (ownerId != null && getServer() != null) {
            ServerPlayer same = getServer().getPlayerList().getPlayer(ownerId);
            if (same != null) found = same; // (whatever world they're in)
        }
        if (found == null) {
            double best = Double.MAX_VALUE;
            for (ServerPlayer sp : serverLevel().players()) {
                if (sp instanceof ClaudeEntity || sp.isSpectator()) continue;
                double d = sp.distanceToSqr(this);
                if (d < best) { best = d; found = sp; }
            }
        }
        setTarget(found);
    }

    /** True when Claude's AI is on (LLM enabled and a key set). */
    public static boolean brainDriven() {
        return Config.ENABLE_LLM.get() && !Config.ANTHROPIC_API_KEY.get().isBlank();
    }

    /** The jump key, for his input record (the same field a player's client sets). */
    public boolean isJumpingNow() { return jumping; }

    /** Why vanilla dropped a hit (D2D 11-08, review 258: "1.0 damage from onFire ... no harm (not taken)" read as a lie while a zombie's hits took his health): the hurt cooldown (a hit within 10 ticks of the last, no harder than it), the source he's immune to, or dead. */
    String notTakenWhy(DamageSource source, float amount, int inv0, float last0) {
        if (isDeadOrDying()) return "dead";
        if (isInvulnerableTo(source)) return "immune to " + source.getMsgId();
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) && hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) return "fire resistance";
        if (source.scalesWithDifficulty() && level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) return "Peaceful: a mob's hit does nothing";
        if (inv0 > 10 && amount <= last0) return "hurt cooldown, " + (inv0 - 10) + " ticks left, " + amount + " no more than the last hit's " + last0;
        return "dropped by vanilla: invulnerable " + isInvulnerable() + ", cooldown " + inv0 + ", last hit " + last0 + ", abilities invulnerable " + getAbilities().invulnerable + " (else his spawn guard, 3 s after a respawn)";
    }
    /** The last hit's outcome as his hurt line gave it (tests). */
    public String hurtSaid = "";

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && source.getEntity() != null) com.claudemod.entity.life.Perception.felt(this, source.getEntity()); // a hit: he knows what did it, and turns to it
        if (!level().isClientSide && source.getEntity() instanceof net.minecraft.world.entity.Mob m && (m instanceof net.minecraft.world.entity.monster.Enemy || m instanceof net.minecraft.world.entity.NeutralMob nm && nm.isAngryAt(this))) urgentAttacker = m.getUUID(); // fight back (phantoms and slimes too, not just the Monster class; and a neutral one angry at him: a wolf his sweep caught bit him 20 -> 1 untouched, live 14:56 11-08, stage 1133)
        boolean blocked0 = !level().isClientSide && isDamageSourceBlocked(source); float before0 = getHealth(); int inv0 = invulnerableTime; float last0 = lastHurt; // (for why a hit is not taken)
        boolean took = super.hurt(source, amount);
        if (!level().isClientSide && getHealth() < before0 && source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.SmallFireball sf) com.claudemod.entity.life.FortressFight.fireballHit(this, sf); // (a fireball that got through: his blaze cover failed him there)
        if (!level().isClientSide && amount >= 1.0f) { // every hit, mobs too, written once it's resolved (16:30: shield-blocked hits logged as damage)
            String res0 = blocked0 ? "blocked by his shield" : getHealth() < before0 ? "health " + (int) before0 + " -> " + (int) getHealth() : took ? "no harm" : "no harm (not taken: " + notTakenWhy(source, amount, inv0, last0) + ")"; hurtSaid = res0;
            if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || source.getMsgId().startsWith("explosion")) { /* the user 01:38: do ghast fireballs and soul fire set him alight like any player? his burn clock and the block he's in, on every fire hit */
                net.minecraft.core.BlockPos fp = blockPosition();
                String in = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level().getBlockState(fp).getBlock()).getPath(), under = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level().getBlockState(fp.below()).getBlock()).getPath();
                res0 += " [burn ticks " + getRemainingFireTicks() + ", on fire " + isOnFire() + ", in " + in + " on " + under + "]";
            }
            ClaudeMod.LOGGER.info("[Claude hurt] {} damage from {}{} at {} (health {} before, hunger {}): " + res0, String.format("%.1f", amount), source.getMsgId(), source.getEntity() == null ? "" : " (" + source.getEntity().getType().getDescription().getString() + (source.getEntity() instanceof net.minecraft.world.entity.Mob mb ? (mb.getTarget() == this ? ", on him" : ", not targeting him") : "") + ")", blockPosition().toShortString(), (int) getHealth(), getFoodData().getFoodLevel());
            com.claudemod.entity.life.CombatLog.hitHim(this, source, amount, before0 - getHealth(), blocked0); // (his fight episode: the hit, from the enemy's side)
            if (source.is(net.minecraft.world.damagesource.DamageTypes.LAVA)) { // which way of his put him in the lava: the path's nodes round him as the planner typed them, and the fluid in each now (live 12:15-12:17 World 17: five touches on one lava fall's edge, no repro in stage 853)
                var lp = nav.getPath(); StringBuilder nb = new StringBuilder();
                if (lp != null) for (int k = Math.max(0, lp.getNextNodeIndex() - 3); k < Math.min(lp.getNodeCount(), lp.getNextNodeIndex() + 3); k++) { var nd = lp.getNode(k); var fs = level().getFluidState(new net.minecraft.core.BlockPos(nd.x, nd.y, nd.z)); nb.append(k == lp.getNextNodeIndex() ? " >" : " ").append(nd.x).append(',').append(nd.y).append(',').append(nd.z).append(' ').append(nd.type).append(fs.isEmpty() ? "" : fs.is(net.minecraft.tags.FluidTags.LAVA) ? (fs.isSource() ? " LAVA-src" : " lava-flow" + fs.getAmount()) : " water"); }
                { var bb = getBoundingBox().deflate(0.001); StringBuilder tb = new StringBuilder(); var fl = net.minecraft.core.BlockPos.containing(getX(), getY() - 0.001, getZ()); var lv = level(); // his box against each lava cell it's in (his own cell or one beside: a corner, a turn's slack), its surface against his feet (soul sand's .875 is under a source's .89), whether he knew of it, and what the planner saw there (D2D 23:10 11-07: live 23:04 316,31,431 'in soul_sand on soul_sand', nothing said which)
                  for (int x = net.minecraft.util.Mth.floor(bb.minX); x < net.minecraft.util.Mth.ceil(bb.maxX); x++) for (int z = net.minecraft.util.Mth.floor(bb.minZ); z < net.minecraft.util.Mth.ceil(bb.maxZ); z++) for (int y = net.minecraft.util.Mth.floor(bb.minY); y < net.minecraft.util.Mth.ceil(bb.maxY); y++) { var q = new net.minecraft.core.BlockPos(x, y, z); var f = lv.getFluidState(q); if (!f.is(net.minecraft.tags.FluidTags.LAVA) || y + f.getHeight(lv, q) < bb.minY) continue;
                      String pl = com.claudemod.entity.nav.ActionPath.plannedBeside(q); tb.append("; ").append(q.toShortString()).append(f.isSource() ? " source" : " flow " + f.getAmount()).append(" surface ").append(String.format("%.3f", y + f.getHeight(lv, q))).append(q.equals(blockPosition()) ? " (his own cell)" : " (beside him)").append(com.claudemod.entity.life.Perception.knowsLava(this, q) ? ", known to him" : ", not known to him").append(pl == null ? ", beside no walk of a plan" : ", at plan time: " + pl); }
                  var fs0 = lv.getBlockState(fl); var sh0 = fs0.getCollisionShape(lv, fl);
                  ClaudeMod.LOGGER.info("[Claude lava] his box at {} ({} off his cell's middle; feet {} on {} top {}): the lava it's in{} (ClaudeEntity.hurt)", String.format("%.2f, %.3f, %.2f", getX(), getY(), getZ()), String.format("%.2f", Math.hypot(getX() - getBlockX() - 0.5, getZ() - getBlockZ() - 0.5)), String.format("%.3f", getY()), fs0.getBlock().getName().getString(), sh0.isEmpty() ? "none" : String.format("%.3f", fl.getY() + sh0.max(net.minecraft.core.Direction.Axis.Y)), tb.length() == 0 ? ": none found" : tb); }
                ClaudeMod.LOGGER.info("[Claude lava] in lava at {} (v {}, sprint {}, jump {}): task {}, path {}{} (ClaudeEntity.hurt)", blockPosition().toShortString(), String.format("%.2f %.2f %.2f", getDeltaMovement().x, getDeltaMovement().y, getDeltaMovement().z), isSprinting(), !onGround(), currentTask, lp == null ? "none" : (lp.getNodeCount() + " nodes:"), nb);
            }
        }
        return took;
    }

    private int sweepJumpTick = -100; // his last jump to keep a swing off a calm bystander
    @Override
    public void attack(net.minecraft.world.entity.Entity target) { // every swing at a mob, landed or not (the fight episode's swings)
        if (!level().isClientSide) { var by = com.claudemod.entity.goals.CombatBrain.sweepBystander(this, target); // (death 20:55:49 11-11: a ground hit on a wither skeleton swept a calm enderman beside it; stage 1075)
            if (by != null && tickCount - sweepJumpTick > 10) { sweepJumpTick = tickCount; getJumpControl().jump();
                ClaudeMod.LOGGER.info("[Claude fight] the swing at the {} held for a jump: on the ground it would sweep the {} {} off beside it, calm; the hit lands in the air (ClaudeEntity.attack)", target.getType().getDescription().getString(), by.getType().getDescription().getString(), String.format("%.1f", by.distanceTo(target)));
                return; }
            if (by != null) ClaudeMod.LOGGER.info("[Claude fight] the swing at the {} sweeps the {} beside it: the jump {} ticks ago never left the ground (ClaudeEntity.attack)", target.getType().getDescription().getString(), by.getType().getDescription().getString(), tickCount - sweepJumpTick); }
        boolean foe = !level().isClientSide && target instanceof net.minecraft.world.entity.monster.Enemy;
        if (foe) com.claudemod.entity.life.CombatLog.swing(this);
        float hp0 = target instanceof net.minecraft.world.entity.LivingEntity l0 ? l0.getHealth() : 0; int iframes = target.invulnerableTime;
        double reach = Math.sqrt(target.getBoundingBox().distanceToSqr(getEyePosition())); boolean line = hasLineOfSight(target);
        super.attack(target);
        if (foe && target instanceof net.minecraft.world.entity.LivingEntity l1 && l1.getHealth() >= hp0) // (a swing that took nothing: why, for the fight review)
            com.claudemod.entity.life.CombatLog.missed(this, iframes > 10 ? "its hurt frames" : reach > 3.0 ? "out of reach" : !line ? "no line of sight" : l1.isBlocking() ? "its shield" : "nothing taken");
    }

    /** Dies like a player: death message, his things drop where he fell, then he respawns (see ClaudeSpawner). */
    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) com.claudemod.entity.life.CombatLog.died(this, source);
        try { com.claudemod.entity.life.Watchdog.death(this, "died to " + source.getMsgId() + (source.getEntity() == null ? "" : " (" + source.getEntity().getType().getDescription().getString() + ")") + " at " + blockPosition().toShortString()); } catch (Throwable e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude watchdog] death report threw", e); }
        ClaudeLife.noteDeath(this, source); // what did it and how he stood, for the respawned body's reading of the spot
        ClaudeLife.lesson(this, "died to " + source.getMsgId() + (source.getEntity() == null ? "" : " (" + source.getEntity().getType().getDescription().getString() + ")")
                + " while " + (currentTask == null ? "idle" : currentTask.getClass().getSimpleName().toLowerCase()) + (lastStep == null ? "" : " on " + lastStep.skill())
                + " at " + blockPosition().toShortString() + ", armor " + getArmorValue() + ", " + (inventory.count(s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem) > 0 || getMainHandItem().getItem() instanceof net.minecraft.world.item.SwordItem || getMainHandItem().getItem() instanceof net.minecraft.world.item.AxeItem ? "armed" : "unarmed"));
        if (owner != null) saveMine(owner);
        abortDig();
        tasks.clear();
        skillQueue.clear();
        com.claudemod.entity.life.ExactSpot.reset(this); /* live 00:13: died in the Nether on go_to -3 80 -4 (nether coords), the spot job outlived him and walked the new body to -3 80 -4 in the overworld */
        nav.stop();
        super.die(source);
        ClaudeLife.markOwnDrops(this); // what just fell out of his pockets is his, and he knows it
        ClaudeSpawner.respawnSoon(this);
    }

    /** Logging off / leaving the world: write down his mine first. */
    @Override
    public void remove(RemovalReason reason) {
        if (owner != null && mineLoaded) saveMine(owner);
        super.remove(reason);
    }

    /** Stuck inside a block (falling gravel, a bad step): mine out, like a player. */
    private void unstick() {
        if (!isInWall()) return;
        BlockPos feet = blockPosition();
        for (BlockPos p : new BlockPos[] {feet.above(), feet}) {
            BlockState st = level().getBlockState(p);
            if (st.getCollisionShape(level(), p).isEmpty() || st.getDestroySpeed(level(), p) < 0 || st.hasBlockEntity()) continue;
            if (currentTask instanceof Task.Break b && b.pos().equals(p)) return; // on it already
            if (!tasks.isEmpty() && tasks.peek() instanceof Task.Break b && b.pos().equals(p)) { dropCurrent = true; return; } // queued next: now, not after whatever he's on
            tasks.addFirst(new Task.Break(p)); // the block he's suffocating in, before anything else (gravel that came down on him, a step cut short)
            dropCurrent = true;
            return;
        }
    }

    // ------------------------------------------------------------------ hands: what a player does with the mouse

    /**
     * One tick of holding left click on a block, through the game's own survival block breaking (same speed,
     * reach and tool rules as a player; drops pop out; tools wear). Returns true once the block is gone.
     */
    public boolean mineTick(BlockPos pos) {
        ServerLevel level = serverLevel();
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) { digging = null; return true; }
        Direction face = Direction.getNearest(getEyePosition().subtract(Vec3.atCenterOf(pos)));
        if (!pos.equals(digging)) {
            abortDig();
            digging = pos.immutable();
            digProgress = 0;
            look.faceNow(Vec3.atCenterOf(pos));
            gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, face, level.getMaxBuildHeight(), 0);
            if (level.getBlockState(pos).isAir()) { digging = null; return true; } // broke instantly (grass, a flower...)
        }
        digProgress += st.getDestroyProgress(this, level, pos);
        if (tickCount % 4 == 0) swing(InteractionHand.MAIN_HAND); // a held click keeps the arm swinging
        if (digProgress < 1.0f) return false;
        gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, face, level.getMaxBuildHeight(), 0);
        if (!level.getBlockState(pos).isAir()) { // the game's own count of the dig runs a tick or two behind his: "too early", the block stays and the crack reset (he stopped right before every block broke); the click stays held and he asks again
            if (digProgress < 3.0f) return false;
            ClaudeMod.LOGGER.info("[Claude life] the game won't give up the block at {} after {} ticks of digging: starting that one over", pos.toShortString(), (int) (digProgress / Math.max(1e-4f, st.getDestroyProgress(this, level, pos))));
            abortDig();
            return false;
        }
        digging = null;
        return true;
    }

    /** Let go of left click (stops the crack animation). */
    public void abortDig() {
        if (digging == null) return;
        gameMode.handleBlockBreakAction(digging, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, serverLevel().getMaxBuildHeight(), 0);
        digging = null;
    }

    /**
     * Right click on a block face with what he's holding, through the game's own item-use code (placing
     * blocks, a hoe tilling, a torch on a wall). Sneaks while clicking, like a player placing against a
     * crafting table or chest instead of opening it. Returns true if it did something.
     */
    public boolean useOn(BlockPos clicked, Direction face) { return useOn(clicked, face, true); }

    /** Right click on a block's face; sneaking (like a player placing against a chest so it doesn't open) only when asked. */
    public boolean useOn(BlockPos clicked, Direction face, boolean sneak) {
        return useOnAt(clicked, face, sneak, Vec3.atCenterOf(clicked).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5));
    }

    /** Right click a block's face at a chosen point on it (where the crosshair is lined up). */
    public boolean useOnAt(BlockPos clicked, Direction face, boolean sneak, Vec3 hit) {
        if (hit.distanceTo(getEyePosition()) > 4.5 + 0.25) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} is out of reach to click ({} blocks from his eyes)", clicked.toShortString(), String.format("%.1f", hit.distanceTo(getEyePosition()))); return false; } // the packet handler's reach check, which a direct call skips
        look.faceNow(hit);
        boolean wasSneaking = isShiftKeyDown();
        setShiftKeyDown(sneak);
        InteractionResult r = gameMode.useItemOn(this, serverLevel(), getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, clicked, false));
        setShiftKeyDown(wasSneaking);
        if (r.consumesAction()) swing(InteractionHand.MAIN_HAND);
        return r.consumesAction();
    }

    /** Places one of `item` at pos, clicking a solid neighbour like a player would (the block under it first). */
    /** A block that does something when right clicked (opens, toggles): a player sneaks to place against it. */
    public static boolean usableBlock(net.minecraft.world.level.Level level, BlockPos n) {
        BlockState ns = level.getBlockState(n);
        return ns.hasBlockEntity() || ns.getMenuProvider(level, n) != null || ns.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || ns.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock || ns.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                || ns.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock || ns.getBlock() instanceof net.minecraft.world.level.block.LeverBlock
                || ns.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    public boolean placeAt(BlockPos pos, Item item) {
        if (!hold(s -> s.is(item))) return false;
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        // like a player: against a plain block, no sneaking (a chest joins the one beside it); against something that
        // opens or works when clicked (a chest, a table, a door), sneaking so it doesn't
        for (int pass = 0; pass < 2; pass++) for (Direction d : order) {
            BlockPos n = pos.relative(d);
            BlockState ns = level().getBlockState(n);
            if (ns.isAir() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue;
            boolean usable = usableBlock(level(), n);
            if (usable != (pass == 1)) continue;
            if (useOn(n, d.getOpposite(), usable)) return true;
        }
        return false;
    }

    /** Right click in the air with what he's holding (start eating, drinking...). */
    public boolean useHeld() {
        return gameMode.useItem(this, serverLevel(), getMainHandItem(), InteractionHand.MAIN_HAND).consumesAction();
    }

    /** Puts a matching stack in his hand: picks it on the hotbar, or swaps it into the hotbar slot he's on. */
    public boolean hold(Predicate<ItemStack> which) {
        Inventory inv = getInventory();
        if (!inv.getSelected().isEmpty() && which.test(inv.getSelected())) return true;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !which.test(s)) continue;
            if (Inventory.isHotbarSlot(i)) { inv.selected = i; return true; }
            int sel = inv.selected;
            ItemStack cur = inv.items.get(sel);
            inv.items.set(sel, s);
            inv.items.set(i, cur);
            return true;
        }
        return false;
    }

    /** Holds the given stack (the actual stack object in his inventory). */
    public boolean hold(ItemStack stack) {
        return !stack.isEmpty() && hold(s -> s == stack);
    }

    // ------------------------------------------------------------------ memory that isn't in player data

    /** Remembers something he did (for his prompts). */
    public boolean jumpInput() { return jumping; } // (diagnostics: the jump flag the body sets each tick)
    public int jumpDelay() { return -1; } // ponytail: noJumpDelay is private in LivingEntity; an access transformer if the jump bug needs it

    public void noteDoing(String what) {
        if (getTarget() instanceof ServerPlayer sp) PlayerProfile.noteDoing(sp, what);
    }

    public void loadMineTest(ServerPlayer owner) { loadMine(owner); } // (tests)
    private void loadMine(ServerPlayer owner) {
        var mine = PlayerProfile.mine(owner);
        if (mine != null) recoverPending = mine.getBoolean("recoverPending"); // (a restart while he waited out the mobs on his drops forgot them: a diamond chestplate despawned; read with no mine too)
        if (!recoverPending) for (var d : com.claudemod.entity.life.DeathLog.of(this)) if (d.pending() && d.worth(this)) { recoverPending = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude deaths] a pile still pending on his checklist at {} {} ({}): the run to it is back on after the restart", d.dim, d.at.toShortString(), d.what); break; } // his checklist, not his queue, says what's left lying (live 11:22 11-07: a restart mid-run forgot his Nether pile, gear_up from nothing instead, stage 899)
        if (mine != null && mine.contains("house")) {
            try { house = com.claudemod.entity.life.HouseBlueprint.fromNbt(mine.getCompound("house")); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] house on file: {}", house); }
            catch (Exception e) { house = null; com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] couldn't read the house on file: {}", e.toString()); }
        }
        if (mine != null && mine.contains("farm")) {
            try { farm = com.claudemod.entity.life.FarmBlueprint.fromNbt(mine.getCompound("farm")); } catch (Exception e) { farm = null; com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] couldn't read the farm on file: {}", e.toString()); }
        }
        if (mine != null && mine.contains("nookOld")) { try { nookOld = com.claudemod.entity.life.BaseNook.fromNbt(mine.getCompound("nookOld")); } catch (Exception e) { nookOld = null; } }
        if (mine != null && mine.contains("nook")) {
            try { nook = com.claudemod.entity.life.BaseNook.fromNbt(mine.getCompound("nook")); } catch (Exception e) { nook = null; com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] couldn't read the base nook on file: {}", e.toString()); }
        }
        if (mine != null) { // his nooks: the list, the old single key folded in; the one he was using is his current one
            nooks = new java.util.ArrayList<>(com.claudemod.entity.life.BaseNook.readAll(mine));
            if (nook != null) { int i = nooks.indexOf(nook); if (i >= 0) nooks.set(i, nook); else nooks.add(nook); }
            if (nookOld != null) nooks.remove(nookOld);
            if (nooks.size() > 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his base nooks on file: {}", nooks);
        }
        if (mine != null && mine.contains("blueprint")) {
            try { blueprint = com.claudemod.entity.life.MineBlueprint.fromNbt(mine.getCompound("blueprint")); ClaudeLife.bpSync(this); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] blueprint on file: {}", blueprint); }
            catch (Exception e) { blueprint = null; com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] couldn't read the blueprint on file: {}", e.toString()); }
        }
        if (mine != null && mine.contains("deaths")) ClaudeLife.loadDeathSpots(this, mine.getLongArray("deaths"));
        if (mine != null && mine.contains("whereabouts")) com.claudemod.entity.life.Whereabouts.load(this, mine.getCompound("whereabouts"));
        if (mine != null && mine.contains("atlas")) com.claudemod.entity.life.Atlas.load(this, mine.getCompound("atlas"));
        if (mine != null && mine.contains("seenOre")) for (long l0 : mine.getLongArray("seenOre")) ClaudeLife.seenOre(this).add(BlockPos.of(l0));
        if (mine != null && mine.contains("signs")) com.claudemod.entity.life.Perception.loadSigns(this, mine.getCompound("signs"));
        if (mine != null && mine.contains("lava")) ClaudeLife.loadLavaMemory(this, mine.getLongArray("lava"));
        if (mine != null && mine.contains("lavaNether")) ClaudeLife.loadLavaList(this, net.minecraft.world.level.Level.NETHER, mine.getLongArray("lavaNether"));
        if (mine != null && mine.contains("netherPortal")) ClaudeLife.netherPortal.put(getUUID(), BlockPos.of(mine.getLong("netherPortal")));
        if (mine != null && mine.contains("portalMap")) com.claudemod.entity.life.PortalMap.load(this, mine.getCompound("portalMap")); else if (mine != null) com.claudemod.entity.life.PortalMap.adopt(this, ClaudeLife.hisPortal.get(getUUID()), ClaudeLife.netherPortal.get(getUUID())); // (a world saved before the portal map: the stopgap's two spots)
        if (mine != null && mine.contains("lessons")) { var ls = mine.getList("lessons", net.minecraft.nbt.Tag.TAG_STRING); java.util.List<String> saved = new java.util.ArrayList<>(); for (int i = 0; i < ls.size(); i++) saved.add(ls.getString(i)); ClaudeLife.loadLessons(this, saved); }
        if (mine != null && mine.contains("face")) {
            mineFace = BlockPos.of(mine.getLong("face"));
            mineDir = Direction.from3DDataValue(mine.getInt("dir"));
            mineIsTunnel = mine.getBoolean("tunnel");
            tunnelDir = mine.contains("tunnelDir") ? Direction.from3DDataValue(mine.getInt("tunnelDir")) : null;
            mineTunnelBlocks = mine.getInt("blocks");
        }
        if (mine != null && mine.contains("stairStart")) {
            stairStart = BlockPos.of(mine.getLong("stairStart"));
            stairDir = Direction.from3DDataValue(mine.getInt("stairDir"));
        }
        if (mine != null && mine.contains("shape")) {
            var s = mine.getCompound("shape");
            mineShape = new MineShape(s.getInt("width"), s.getInt("height"), s.getInt("branchEvery"), s.getInt("branchLength"),
                    s.getInt("torchEvery"), s.getString("torchSide"));
        }
        if (mine != null) for (long p : mine.getLongArray("tempStations")) tempStations.add(BlockPos.of(p)); // his to pick up
        if (mine != null) for (long p : mine.getLongArray("notches")) notches.add(BlockPos.of(p));
        if (mine != null) for (long p : mine.getLongArray("pillars")) pillarBlocks.add(BlockPos.of(p)); // his towers, to take down
        if (mine != null && mine.contains("goal") && playerGoal.isBlank()) playerGoal = mine.getString("goal"); // what they asked for, across a restart
        if (mine != null && mine.contains("lesson")) lessonOpen = mine.getString("lesson");
        if (mine != null && mine.contains("wornChoice")) wornChoice = mine.getString("wornChoice");
        if (mine != null && mine.contains("lessonLast")) lessonLast = mine.getString("lessonLast"); // their last lesson instruction, for "ok continuing lesson" after a restart
        if (mine != null && mine.contains("lessonPlaces")) lessonPlaces = mine.getString("lessonPlaces"); // the pocket, the spot // a lesson he's in, across a restart
        if (mine != null) for (long p : mine.getLongArray("cells")) mineCells.add(BlockPos.of(p));
        if (mine != null) for (long p : mine.getLongArray("stations")) myStations.add(BlockPos.of(p));
        if (mine != null) for (long p : mine.getLongArray("storage")) storageChests.add(BlockPos.of(p));
        if (mine != null) { if (mine.contains("ledger")) com.claudemod.entity.life.ChestLedger.load(this, mine.getCompound("ledger")); else { java.util.Set<BlockPos> s0 = new java.util.HashSet<>(storageChests); s0.addAll(myStations); com.claudemod.entity.life.ChestLedger.seedFrom(this, s0); } } // what's in his chests as of his last look (a save from before the ledger: read once as each loads)
        if (mine != null) { long[] pe = mine.getLongArray("pastMines"); int[] pd = mine.getIntArray("pastDirs");
            for (int i = 0; i < Math.min(pe.length, pd.length); i++) { pastMineEntrances.add(BlockPos.of(pe[i])); pastMineDirs.add(Direction.from3DDataValue(pd[i])); } }
        if (mine != null) for (long p : mine.getLongArray("spine")) mineSpine.add(BlockPos.of(p));
        if (mine != null) for (long p : mine.getLongArray("landings")) landingCells.add(BlockPos.of(p));
        if (mine != null) { if (mine.contains("temps")) com.claudemod.entity.life.TempLedger.load(this, mine.getCompound("temps")); else com.claudemod.entity.life.TempLedger.seedFrom(this, serverLevel()); } // his temporary blocks (a save from before the ledger: his old take-down lists)
    }

    /** His mine in progress (his inventory and position are saved by the game, like any player's). */
    public void saveMine(ServerPlayer owner) {
        var mine = new net.minecraft.nbt.CompoundTag();
        if (isAlive() && onGround()) { mine.putLong("lastPos", blockPosition().asLong()); mine.putString("lastDim", level().dimension().location().toString()); } // (where he is, every 10 s: a restart puts him back here, like any player logging in where he logged off)
        if (blueprint != null) mine.put("blueprint", blueprint.toNbt());
        mine.putBoolean("recoverPending", recoverPending); // (mine or no mine: it sat under the mine face's block)
        com.claudemod.entity.life.BaseNook.saveAll(mine, nooks, nook); // (every nook of his; the current one under "nook" too)
        if (nookOld != null) mine.put("nookOld", nookOld.toNbt());
        if (farm != null) mine.put("farm", farm.toNbt());
        if (house != null) mine.put("house", house.toNbt());
        if (mineFace != null && mineDir != null) {
            mine.putLong("face", mineFace.asLong());
            mine.putInt("dir", mineDir.get3DDataValue());
            mine.putBoolean("tunnel", mineIsTunnel);
            if (tunnelDir != null) mine.putInt("tunnelDir", tunnelDir.get3DDataValue());
            mine.putInt("blocks", mineTunnelBlocks);
        }
        if (stairStart != null && stairDir != null) {
            mine.putLong("stairStart", stairStart.asLong());
            mine.putInt("stairDir", stairDir.get3DDataValue());
        }
        if (mineShape != null) {
            var s = new net.minecraft.nbt.CompoundTag();
            s.putInt("width", mineShape.width); s.putInt("height", mineShape.height);
            s.putInt("branchEvery", mineShape.branchEvery); s.putInt("branchLength", mineShape.branchLength);
            s.putInt("torchEvery", mineShape.torchEvery); s.putString("torchSide", mineShape.torchSide);
            mine.put("shape", s);
        }
        mine.putLongArray("tempStations", tempStations.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("notches", notches.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("pillars", pillarBlocks.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("cells", mineCells.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("stations", myStations.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("pastMines", pastMineEntrances.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("storage", storageChests.stream().mapToLong(BlockPos::asLong).toArray());
        mine.put("ledger", com.claudemod.entity.life.ChestLedger.save(this)); // his chests' contents as he last saw them
        mine.put("temps", com.claudemod.entity.life.TempLedger.save(this)); // every block he put down for now, and the trees he owes
        mine.putIntArray("pastDirs", pastMineDirs.stream().mapToInt(Direction::get3DDataValue).toArray());
        mine.putString("goal", playerGoal == null ? "" : playerGoal);
        mine.putString("lesson", lessonOpen == null ? "" : lessonOpen);
        mine.putString("wornChoice", wornChoice == null ? "" : wornChoice);
        mine.putString("lessonLast", lessonLast == null ? "" : lessonLast); mine.putString("lessonPlaces", lessonPlaces == null ? "" : lessonPlaces);
        mine.putLongArray("spine", mineSpine.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("landings", landingCells.stream().mapToLong(BlockPos::asLong).toArray());
        mine.putLongArray("lava", ClaudeLife.lavaMemory(this));
        mine.putLongArray("lavaNether", ClaudeLife.lavaList(this, net.minecraft.world.level.Level.NETHER));
        { BlockPos np = ClaudeLife.netherPortal.get(getUUID()); if (np != null) mine.putLong("netherPortal", np.asLong()); } mine.put("portalMap", com.claudemod.entity.life.PortalMap.save(this)); // the lava he saw and his portal: the dragon run's memory across a relaunch
        mine.put("atlas", com.claudemod.entity.life.Atlas.save(this)); // the biomes he has seen, the ground he has covered
        mine.put("whereabouts", com.claudemod.entity.life.Whereabouts.save(this)); // where people were last seen, kept across a relaunch
        mine.putLongArray("seenOre", ClaudeLife.seenOre(this).stream().mapToLong(BlockPos::asLong).toArray()); // ore he saw (and maybe sealed behind a patch), kept across a relaunch
        mine.put("signs", com.claudemod.entity.life.Perception.saveSigns(this)); // the hazards he saw (spawners, the deep dark...), kept across a relaunch
        mine.putLongArray("deaths", ClaudeLife.deathSpotsFor(this)); // where he's died: the den rules need it across a relaunch
        var ls = new net.minecraft.nbt.ListTag(); for (String l : ClaudeLife.lessonsFor(this)) ls.add(net.minecraft.nbt.StringTag.valueOf(l)); mine.put("lessons", ls); // and what he's learned
        var before = PlayerProfile.mine(owner); if (before != null && before.contains("animals")) mine.putLong("animals", before.getLong("animals"));
        if (before != null && before.contains("bridges")) mine.putLongArray("bridges", before.getLongArray("bridges")); // and his bridges still to take down (ClaudeLife keeps that key up to date) // and where the animals were (ClaudeLife keeps that key up to date)
        PlayerProfile.setMine(owner, mine);
        PlayerProfile.setInventory(owner, null); // ponytail: inventory now lives in his player data; just saves the profile
    }

    /** After a respawn (a new player object): keep what he knew. */
    public void copyBrainFrom(ClaudeEntity old) {
        lessonOpen = old.lessonOpen; lessonLast = old.lessonLast; lessonPlaces = old.lessonPlaces; wornChoice = old.wornChoice; nookOld = old.nookOld; // (saved by saveMine, never carried over: a death mid-lesson or mid-nook-move wiped them, and the new body's save wrote the blanks)
        tickCount = old.tickCount; // (his clock goes on: the static stamps keyed by his UUID read a fresh 0 as "just now" for a whole life's length, planners and guards off)
        prefs.clear(); prefs.putAll(old.prefs);
        playerGoal = old.playerGoal;
        ownerId = old.ownerId;
        mineFace = old.mineFace; mineDir = old.mineDir; mineIsTunnel = old.mineIsTunnel; tunnelDir = old.tunnelDir;
        blueprint = old.blueprint; house = old.house; nook = old.nook; if (old.nooks != null) { nooks = new java.util.ArrayList<>(old.nooks); } farm = old.farm;
        mineTunnelBlocks = old.mineTunnelBlocks; stairStart = old.stairStart; stairDir = old.stairDir; mineShape = old.mineShape;
        projectOrigin = old.projectOrigin; projectDim = old.projectDim;
        tempStations.addAll(old.tempStations);
        // everything he knows carries over a death, like a player's memory: his stations, storage, mines, the ore he saw, the patches he owes
        myStations.addAll(old.myStations);
        if (old.storageChests != null) storageChests.addAll(old.storageChests);
        if (old.pastMineEntrances != null) { pastMineEntrances.clear(); pastMineEntrances.addAll(old.pastMineEntrances); pastMineDirs.clear(); pastMineDirs.addAll(old.pastMineDirs); }
        spottedDiamonds.addAll(old.spottedDiamonds);
        spottedMineshafts.addAll(old.spottedMineshafts);
        if (old.furnaceReadyAt != null) furnaceReadyAt.putAll(old.furnaceReadyAt);
        pendingPatches.addAll(old.pendingPatches);
        notches.addAll(old.notches);
        mineCells.addAll(old.mineCells); mineSpine.addAll(old.mineSpine); landingCells.addAll(old.landingCells);
        pillarBlocks.addAll(old.pillarBlocks);
        setLastDeathLocation(old.getLastDeathLocation()); // so he knows where his things are
        mineLoaded = true;
    }

    // ------------------------------------------------------------------ lookups

    /** True if the player has him in view (roughly facing him, with line of sight). */
    public boolean targetIsFacingMe() {
        if (!(getTarget() instanceof Player p)) return false;
        Vec3 view = p.getViewVector(1.0F).normalize();
        Vec3 diff = new Vec3(getX() - p.getX(), getEyeY() - p.getEyeY(), getZ() - p.getZ()).normalize();
        return view.dot(diff) > 0.4 && p.hasLineOfSight(this);
    }

    public static boolean isClaude(@Nullable Entity e) {
        return e instanceof ClaudeEntity;
    }

    /** The Claude playing with this player, if any. */
    public static Optional<ClaudeEntity> activeFor(ServerPlayer player) {
        if (player instanceof ClaudeEntity || player.getServer() == null) return Optional.empty();
        for (ServerPlayer sp : player.getServer().getPlayerList().getPlayers()) {
            if (sp instanceof ClaudeEntity c && c.isAlive() && (c.level() == player.level() || c.owner == player || player.getUUID().equals(c.ownerId)) // (theirs answers from any world: he's in the Nether, they're at home)
                    && (c.owner == player || player.getUUID().equals(c.ownerId) || c.owner == null)) {
                if (c.owner == null) c.setTarget(player);
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** Claude, if he's in the game. */
    public static Optional<ClaudeEntity> any(MinecraftServer server) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) if (sp instanceof ClaudeEntity c) return Optional.of(c);
        return Optional.empty();
    }
}
