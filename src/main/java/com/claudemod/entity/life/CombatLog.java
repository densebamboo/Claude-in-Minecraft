package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * His fights as episodes, both sides counted, and each one reviewed (the user 11-08: "elite at combat in any scenario ... more
 * detailed logging to enemies as well to track his combat performance"; "indirect reinforcement learning ... very obvious where
 * time was wasted, and where something should have been done differently").
 *
 * An episode opens on the first hit either way or the first fight/flee decision, and closes after combat.episode_quiet_s with
 * neither, or at his death. Per enemy: its HP at the start and end, the hits and damage each way (crits too), the shots it
 * loosed at him, its closest approach, killed or not. His side: swings (charged or not, landed or not), shield blocks, the time
 * he stood with a charged hit and a foe in reach, hits taken running, blast damage, every combat decision with the HP and time
 * that followed it (its credit) and the weights it read.
 *
 * The review: an ideal time (sprint in, every hit charged) against the time it took, then each waste as "lost: what; better:
 * what", and the decision that cost the most HP with the weights that priced it and which way they were off. Closed: one
 * "[Claude combat]" line, one "[Claude combat review]" line, and a JSON line in world/claude/combat.jsonl for
 * tools/combat_report.py (the tuning report across fights).
 */
public final class CombatLog {
    private CombatLog() {}

    static final class Foe {
        final String type; final UUID id; final double dist0; final float max; float hp0;
        float hpLast; double minDist; int hitsOnHim, shots, hitsTaken, crits; float dmgOnHim, dmgTaken; boolean killed;
        Foe(LivingEntity m, ClaudeEntity g) { type = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath(); id = m.getUUID(); dist0 = m.distanceTo(g); hp0 = hpLast = m.getHealth(); max = m.getMaxHealth(); minDist = dist0; }
    }
    /** One decision (repeats of the same pick folded in) and what followed it till the next. */
    static final class Dec {
        final long t; final String key; final long s; int n = 1; final float taken0, dealt0; final Map<String, Double> weights = new LinkedHashMap<>();
        Dec(long t, String key, long s, float taken0, float dealt0) { this.t = t; this.key = key; this.s = s; this.taken0 = taken0; this.dealt0 = dealt0; }
    }
    static final class Fight {
        final int n; final long start; long last; final float hp0; float hpMin; final int armor; final String weapon, offhand, dim; final boolean underground, night;
        final double dps, speed; // his full-charge damage per second with what he held at the start
        final Map<UUID, Foe> foes = new LinkedHashMap<>(); final List<Dec> decs = new ArrayList<>(); final Map<String, Double> pending = new LinkedHashMap<>();
        int swings, landed, weakSwings, blocked, hitsOnHim, flips, readyIdle, runHits, shotThroughShield, attackTicks, fleeTicks, otherTicks, ticks;
        float dealt, taken, blastHp, runHp; String lastPick; Vec3 lastPos; double moved; boolean readyLast;
        Fight(ClaudeEntity g, int n) {
            this.n = n; start = last = g.tickCount; hp0 = hpMin = g.getHealth(); armor = g.getArmorValue();
            weapon = BuiltInRegistries.ITEM.getKey(g.getMainHandItem().getItem()).getPath(); offhand = BuiltInRegistries.ITEM.getKey(g.getOffhandItem().getItem()).getPath();
            dim = g.level().dimension().location().getPath(); underground = g.level() instanceof net.minecraft.server.level.ServerLevel sl && com.claudemod.entity.goals.WorkGoal.underground(sl, g.blockPosition());
            night = !com.claudemod.director.ClaudeLife.dayHere(g.level()); lastPos = g.position();
            speed = g.getAttributeValue(Attributes.ATTACK_SPEED); dps = g.getAttributeValue(Attributes.ATTACK_DAMAGE) * speed;
        }
        Foe foe(LivingEntity m, ClaudeEntity g) { return foes.computeIfAbsent(m.getUUID(), k -> new Foe(m, g)); }
        String state(ClaudeEntity g) { return g.currentTask == null ? "idle" : g.currentTask.getClass().getSimpleName(); }
    }

    private static final Map<UUID, Fight> OPEN = new HashMap<>();
    private static Fight CUR; // the fight of the bot ticking now: the weights read are its decisions' (one server thread)
    private static int counter;
    private static double w(String n, double d) { return Weights.w(n, d); }

    static Fight open(ClaudeEntity g) {
        Fight f = OPEN.get(g.getUUID());
        if (f == null) { f = new Fight(g, ++counter); OPEN.put(g.getUUID(), f); }
        f.last = g.tickCount; return f;
    }
    /** The fight open now, if any (tests, his own state lines). */
    public static boolean inFight(ClaudeEntity g) { return OPEN.containsKey(g.getUUID()); }

    /** A weight read (Weights.w): a combat one, inside a fight, rides on his next decision. */
    static void read(String name, double v) {
        Fight f = CUR; if (f == null || f.pending.size() > 24) return;
        if (name.startsWith("fight.") || name.startsWith("flee.") || name.startsWith("cover.") || name.startsWith("creeper.") || name.startsWith("escape.") || name.startsWith("blaze.") || name.startsWith("enderman."))
            f.pending.put(name, v);
    }

    /** A hit on him (ClaudeEntity.hurt, after vanilla resolved it). */
    public static void hitHim(ClaudeEntity g, DamageSource src, float amount, float lost, boolean blocked) {
        if (!(src.getEntity() instanceof LivingEntity m) || !(m instanceof Enemy)) return;
        Fight f = open(g); Foe o = f.foe(m, g);
        if (blocked) { f.blocked++; return; }
        if (lost <= 0) return;
        o.hitsOnHim++; o.dmgOnHim += lost; f.hitsOnHim++; f.taken += lost; f.hpMin = Math.min(f.hpMin, g.getHealth());
        if (src.is(DamageTypeTags.IS_EXPLOSION)) f.blastHp += lost;
        if (src.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile && g.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) f.shotThroughShield++;
        if (f.state(g).equals("Flee")) { f.runHits++; f.runHp += lost; }
    }
    /** A shot loosed at him (an arrow or fireball whose shooter is after him, as it enters the world). */
    public static void shotAt(ClaudeEntity g, LivingEntity shooter) {
        if (!(shooter instanceof Enemy)) return;
        open(g).foe(shooter, g).shots++;
    }
    /** His hit on a mob (LivingDamageEvent.Post with him as the source). */
    public static void hitBy(ClaudeEntity g, LivingEntity m, float dmg, boolean crit) {
        if (!(m instanceof Enemy)) return;
        Fight f = open(g); boolean fresh = !f.foes.containsKey(m.getUUID()); Foe o = f.foe(m, g);
        if (fresh) o.hp0 = o.hpLast = Math.min(o.max, m.getHealth() + dmg); // first met by his hit: its HP before it (the event is after)
        float real = Math.min(dmg, o.hpLast); // what it lost, not the overkill
        o.hitsTaken++; o.dmgTaken += real; if (crit) o.crits++; o.hpLast = m.getHealth(); f.landed++; f.dealt += real;
    }
    public static void killed(ClaudeEntity g, LivingEntity m) {
        Fight f = OPEN.get(g.getUUID()); if (f == null) return;
        Foe o = f.foes.get(m.getUUID()); if (o != null) { o.killed = true; o.hpLast = 0; }
    }
    /** A swing of his at a mob (hit or miss), before vanilla resets the charge. */
    public static void swing(ClaudeEntity g) {
        Fight f = OPEN.get(g.getUUID()); if (f == null) return;
        f.swings++; if (g.getAttackStrengthScale(0.5f) < w("combat.review.charged", 0.9)) f.weakSwings++;
    }
    /** A fight/flee/cover decision (Decide.choose): the pick, its price, the weights read since the last one; a flip when the pick changes. */
    public static void decision(ClaudeEntity g, String area, String pick, double s) {
        if (g == null || g.level().isClientSide) return;
        boolean opens = area.startsWith("fight") || area.startsWith("flee");
        Fight f = opens ? open(g) : OPEN.get(g.getUUID()); // cover/escape/blaze/... only counted inside a fight (cover.leave runs at night too)
        if (f == null || !(opens || area.startsWith("cover") || area.startsWith("escape") || area.startsWith("blaze") || area.startsWith("enderman") || area.startsWith("walk.mob"))) return;
        if (f.lastPick != null && !f.lastPick.equals(pick)) f.flips++;
        f.lastPick = pick;
        String key = area + ":" + pick; Dec last = f.decs.isEmpty() ? null : f.decs.get(f.decs.size() - 1);
        if (last != null && last.key.equals(key)) { last.n++; last.weights.putAll(f.pending); f.pending.clear(); return; } // the same pick every tick: one entry, counted
        if (f.decs.size() >= w("combat.max_decisions_kept", 40)) return;
        Dec d = new Dec(g.tickCount - f.start, key, Math.round(s), f.taken, f.dealt); d.weights.putAll(f.pending); f.pending.clear(); f.decs.add(d);
    }

    /** Every tick of his: foes' distances, his path, a charged hit unused, the quiet clock. */
    public static void tick(ClaudeEntity g) {
        Fight f = OPEN.get(g.getUUID()); CUR = f; if (f == null) return;
        f.ticks++;
        switch (f.state(g)) { case "Attack" -> f.attackTicks++; case "Flee" -> f.fleeTicks++; default -> f.otherTicks++; }
        f.moved += g.position().distanceTo(f.lastPos); f.lastPos = g.position(); f.hpMin = Math.min(f.hpMin, g.getHealth());
        boolean ready = false;
        if (g.level() instanceof net.minecraft.server.level.ServerLevel sl && g.getAttackStrengthScale(0.5f) >= w("combat.review.charged", 0.9))
            for (Foe o : f.foes.values()) if (sl.getEntity(o.id) instanceof LivingEntity m && m.isAlive() && m.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(m)) { ready = true; break; }
        if (ready && f.readyLast) f.readyIdle++; // (charged at two tick starts running, a foe in reach both: no swing between)
        f.readyLast = ready;
        if (f.ticks % 5 == 0 && g.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            for (Foe o : f.foes.values()) if (sl.getEntity(o.id) instanceof LivingEntity m && m.isAlive()) { o.minDist = Math.min(o.minDist, m.distanceTo(g)); o.hpLast = m.getHealth(); }
            boolean hunted = !g.level().getEntitiesOfClass(Mob.class, g.getBoundingBox().inflate(w("combat.episode_hunt_r", 16)), m -> m.isAlive() && m instanceof Enemy && m.getTarget() == g).isEmpty();
            if (hunted) f.last = g.tickCount; // (one still after him keeps it open)
        }
        if (g.tickCount - f.last > w("combat.episode_quiet_s", 10) * 20) close(g, f, null);
    }
    /** Closes his open fight now (the combat benchmark's stage end). */
    public static void closeNow(ClaudeEntity g) { Fight f = OPEN.get(g.getUUID()); if (f != null) close(g, f, null); }
    /** His death: the open fight closes as lost. */
    public static void died(ClaudeEntity g, DamageSource src) { Fight f = OPEN.get(g.getUUID()); if (f != null) close(g, f, "died to " + src.getMsgId() + (src.getEntity() == null ? "" : " (" + BuiltInRegistries.ENTITY_TYPE.getKey(src.getEntity().getType()).getPath() + ")")); }

    /** The review of a closed fight: the ideal time against his, each waste as lost/better, the costliest decision. */
    static List<String> review(Fight f, String outcome, double secs, List<float[]> credit) {
        List<String> out = new ArrayList<>();
        double charge = f.dps > 0 ? 1 : 0; // (no weapon attributes: no ideal)
        float foeHp = 0; double near = Double.MAX_VALUE; for (Foe o : f.foes.values()) { foeHp += o.hp0; near = Math.min(near, o.dist0); }
        if (charge > 0 && (outcome.equals("won") || outcome.startsWith("lost"))) {
            double ideal = Math.max(0, near - 3) / w("combat.review.sprint_bps", 5.6) + foeHp / f.dps;
            if (secs > ideal + w("combat.review.slack_s", 1.5)) out.add(String.format("%.1f s over the ideal %.1f s (%.0f HP of foes at %.1f HP/s, every hit charged)", secs - ideal, ideal, foeHp, f.dps));
        }
        double idleS = f.readyIdle / 20.0;
        if (idleS >= w("combat.review.ready_idle_min_s", 0.5)) out.add(String.format("lost %.1f s: a charged hit and a foe in reach, no swing; better: swing the tick the charge is full", idleS));
        if (f.weakSwings > 0) out.add(String.format("lost %d swings to half charge (weak hits); better: wait out the charge (%.2f s with this weapon)", f.weakSwings, 1 / Math.max(0.1, f.speed)));
        int miss = Math.max(0, f.swings - f.landed);
        if (miss >= w("combat.review.miss_min", 2)) out.add(String.format("lost %d of %d swings that landed nothing (out of reach, its hurt frames, or blocked); better: swing in reach, charged, after its hurt flash", miss, f.swings));
        int crits = 0; for (Foe o : f.foes.values()) crits += o.crits;
        if (f.landed >= w("combat.review.crit_min_landed", 4) && crits * 4 < f.landed) out.add(String.format("%d crits in %d hits; better: jump-hits (falling) do 1.5x", crits, f.landed));
        if (f.shotThroughShield > 0) out.add(String.format("took %d shots with a shield in hand; better: shield up toward the shooter while closing in", f.shotThroughShield));
        if (f.blastHp > 0) out.add(String.format("took %.1f HP from a blast; better: back off at the hiss, or hit it out of its fuse", f.blastHp));
        if (f.runHits > 0) out.add(String.format("took %d hits (%.1f HP) running; better: fight what keeps pace, or run before it's in reach", f.runHits, f.runHp));
        if (f.flips > w("combat.review.flips_max", 2)) out.add(String.format("%d flips between picks; better: one choice held till something changes", f.flips));
        int worst = -1; float worstHp = 0;
        for (int i = 0; i < credit.size(); i++) if (credit.get(i)[0] > worstHp) { worstHp = credit.get(i)[0]; worst = i; }
        if (worst >= 0 && worstHp >= w("combat.review.blame_min_hp", 4)) {
            Dec d = f.decs.get(worst); String pick = d.key.substring(d.key.indexOf(':') + 1);
            out.add(String.format("costliest pick %s at %.1f s: %.1f HP lost after it (%.1f dealt); its price ~%d was too low%s", d.key, d.t / 20.0, worstHp, credit.get(worst)[1], d.s,
                    d.weights.isEmpty() ? "" : ": weights " + String.join(", ", d.weights.keySet().stream().filter(k -> pick.equals("fight") ? k.startsWith("fight.") : !k.startsWith("fight.")).limit(6).toList())));
        }
        return out;
    }

    static void close(ClaudeEntity g, Fight f, String died) {
        OPEN.remove(g.getUUID()); if (CUR == f) CUR = null;
        if (f.foes.isEmpty() && f.decs.isEmpty()) return;
        if (f.swings == 0 && f.landed == 0 && f.hitsOnHim == 0 && f.blocked == 0 && f.decs.isEmpty() && died == null) return; // (opened by a shot that missed and nothing more)
        int killed = 0; for (Foe o : f.foes.values()) if (o.killed) killed++;
        String outcome = died != null ? "lost: " + died : killed == f.foes.size() ? "won" : f.taken == 0 && killed == 0 ? "avoided" : killed > 0 ? "won in part, broke off" : "broke off";
        double secs = (Math.max(f.last, died != null ? g.tickCount : f.last) - f.start) / 20.0; // (the active fight, not the quiet tail)
        List<float[]> credit = new ArrayList<>(); // per decision: HP he lost and dealt till the next one
        for (int i = 0; i < f.decs.size(); i++) { Dec d = f.decs.get(i); float t1 = i + 1 < f.decs.size() ? f.decs.get(i + 1).taken0 : f.taken, d1 = i + 1 < f.decs.size() ? f.decs.get(i + 1).dealt0 : f.dealt; credit.add(new float[] {t1 - d.taken0, d1 - d.dealt0}); }
        List<String> rev = review(f, outcome, secs, credit);
        StringBuilder foes = new StringBuilder();
        for (Foe o : f.foes.values()) foes.append(foes.length() == 0 ? "" : "; ").append(String.format("%s %.0f off at the start, closest %.1f, its HP %.0f->%.0f/%.0f%s, it landed %d (%.1f HP)%s, he landed %d (%.1f HP%s)",
                o.type, o.dist0, o.minDist, o.hp0, o.hpLast, o.max, o.killed ? " KILLED" : "", o.hitsOnHim, o.dmgOnHim, o.shots > 0 ? " of " + o.shots + " shots" : "", o.hitsTaken, o.dmgTaken, o.crits > 0 ? ", " + o.crits + " crits" : ""));
        List<String> decs = new ArrayList<>();
        for (int i = 0; i < f.decs.size(); i++) { Dec d = f.decs.get(i); decs.add(String.format("%.1fs %s~%d%s -> -%.1f/+%.1f HP", d.t / 20.0, d.key, d.s, d.n > 1 ? " x" + d.n : "", credit.get(i)[0], credit.get(i)[1])); }
        ClaudeMod.LOGGER.info("[Claude combat] fight #{} over after {} s: {}; him {}->{} HP (lowest {}), armor {}, {} | {}, {}{}{}; dealt {} HP in {} of {} swings ({} half-charged), took {} HP in {} hits, {} blocked; time attack {} s / run {} s / other {} s; moved {} blocks; decisions {} ({} flips): {}; foes: {} (CombatLog)",
                f.n, String.format("%.1f", secs), outcome, (int) f.hp0, (int) Math.max(0, g.getHealth()), (int) f.hpMin, f.armor, f.weapon, f.offhand, f.dim, f.underground ? ", underground" : "", f.night ? ", night" : "",
                String.format("%.1f", f.dealt), f.landed, Math.max(f.swings, f.landed), f.weakSwings, String.format("%.1f", f.taken), f.hitsOnHim, f.blocked, f.attackTicks / 20, f.fleeTicks / 20, f.otherTicks / 20,
                Math.round(f.moved), f.decs.size(), f.flips, String.join(", ", decs), foes);
        ClaudeMod.LOGGER.info("[Claude combat review] fight #{} ({}): {} (CombatLog.review)", f.n, outcome, rev.isEmpty() ? "nothing an expert would have done better" : String.join(" | ", rev));
        try {
            var p = g.getServer().getWorldPath(LevelResource.ROOT).resolve("claude").resolve("combat.jsonl");
            java.nio.file.Files.createDirectories(p.getParent());
            StringBuilder j = new StringBuilder("{");
            kv(j, "n", f.n); kv(j, "t", f.start); kv(j, "secs", secs); kv(j, "outcome", outcome); kv(j, "hp0", f.hp0); kv(j, "hp1", Math.max(0, g.getHealth())); kv(j, "hpMin", f.hpMin);
            kv(j, "armor", f.armor); kv(j, "weapon", f.weapon); kv(j, "offhand", f.offhand); kv(j, "dim", f.dim); kv(j, "underground", f.underground); kv(j, "night", f.night); kv(j, "dps", f.dps);
            kv(j, "dealt", f.dealt); kv(j, "landed", f.landed); kv(j, "swings", Math.max(f.swings, f.landed)); kv(j, "weakSwings", f.weakSwings); kv(j, "taken", f.taken); kv(j, "hitsOnHim", f.hitsOnHim);
            kv(j, "blocked", f.blocked); kv(j, "readyIdleS", f.readyIdle / 20.0); kv(j, "runHits", f.runHits); kv(j, "runHp", f.runHp); kv(j, "blastHp", f.blastHp); kv(j, "shotThroughShield", f.shotThroughShield);
            kv(j, "attackS", f.attackTicks / 20.0); kv(j, "fleeS", f.fleeTicks / 20.0); kv(j, "otherS", f.otherTicks / 20.0); kv(j, "moved", Math.round(f.moved)); kv(j, "flips", f.flips);
            j.append("\"review\":["); for (int i = 0; i < rev.size(); i++) j.append(i == 0 ? "" : ",").append(q(rev.get(i))); j.append("],\"decisions\":[");
            for (int i = 0; i < f.decs.size(); i++) {
                Dec d = f.decs.get(i); StringBuilder o = new StringBuilder("{");
                kv(o, "t", d.t / 20.0); kv(o, "key", d.key); kv(o, "s", d.s); kv(o, "n", d.n); kv(o, "lost", credit.get(i)[0]); kv(o, "dealt", credit.get(i)[1]);
                o.append("\"weights\":{"); int k = 0; for (var e : d.weights.entrySet()) o.append(k++ == 0 ? "" : ",").append(q(e.getKey())).append(':').append(e.getValue()); o.append("}}");
                j.append(i == 0 ? "" : ",").append(o);
            }
            j.append("],\"foes\":[");
            int i = 0; for (Foe o : f.foes.values()) {
                StringBuilder s = new StringBuilder("{");
                kv(s, "type", o.type); kv(s, "dist0", o.dist0); kv(s, "minDist", o.minDist); kv(s, "hp0", o.hp0); kv(s, "hp1", o.hpLast); kv(s, "max", o.max); kv(s, "killed", o.killed);
                kv(s, "hitsOnHim", o.hitsOnHim); kv(s, "dmgOnHim", o.dmgOnHim); kv(s, "shots", o.shots); kv(s, "hitsTaken", o.hitsTaken); kv(s, "dmgTaken", o.dmgTaken); s.append("\"crits\":").append(o.crits).append('}');
                j.append(i++ == 0 ? "" : ",").append(s);
            }
            j.append("]}\n");
            java.nio.file.Files.writeString(p, j, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude combat] couldn't write combat.jsonl: {}", e.toString()); }
    }
    private static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "'") + "\""; }
    private static void kv(StringBuilder j, String k, Object v) {
        j.append(q(k)).append(':');
        if (v instanceof String s) j.append(q(s));
        else if (v instanceof Float x) j.append(String.format(Locale.ROOT, "%.2f", x));
        else if (v instanceof Double x) j.append(String.format(Locale.ROOT, "%.2f", x));
        else j.append(v);
        j.append(',');
    }

    /** His hits on mobs, with crits (falling, off the ground: the vanilla crit test). */
    public static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) {
        if (e.getEntity().level().isClientSide || !(e.getSource().getEntity() instanceof ClaudeEntity g) || e.getEntity() == g) return;
        hitBy(g, e.getEntity(), e.getNewDamage(), g.fallDistance > 0 && !g.onGround() && !g.isInWater() && !g.onClimbable());
    }
    public static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (e.getEntity().level().isClientSide || !(e.getSource().getEntity() instanceof ClaudeEntity g)) return;
        killed(g, e.getEntity());
    }
    /** A shot at him: an arrow/fireball/trident from a mob whose target is him. */
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p) || e.loadedFromDisk()) return;
        if (p.getOwner() instanceof Mob m && m.getTarget() instanceof ClaudeEntity g) shotAt(g, m);
    }
}
