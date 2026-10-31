package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * His portal memory as a travel network (the user, 2026-10-31 00:07: "go back to the portal" got "i dont have the portal's
 * coords logged" though he'd named it at 23:51; "portal memory is gonna need to be more complex than that ... especially when we
 * get into nether tunneling/highway stuff").
 * Nodes: every portal he has used, lit or seen by line of sight (far sight's lit portals, ruined portals, frames): its dimension,
 * where, lit / unlit / broken / gone as last seen, who made it, when, and its link to the other side (learned by walking through,
 * else predicted by vanilla's rule: overworld/8, nearest known portal within 128 blocks in the overworld or 16 in the Nether).
 * Edges: the ways between portals in one dimension (walked, tunnel, highway) with their y, covered or open, lit or not, length:
 * what highway building (a later milestone) fills in. Routes: walk to a portal, through, along the Nether way, out, against
 * walking the overworld. Saved per world in his mine tag. Every change is logged "(PortalMap.method)".
 */
public final class PortalMap {
    private PortalMap() {}

    public static final String OW = "overworld", NETHER = "the_nether";
    public static final int SEARCH_OW = 128, SEARCH_NETHER = 16; // vanilla's destination search (1.21)

    public static final class Portal {
        public int id; public String dim; public BlockPos at; // the lowest portal cell (or the frame's inside bottom)
        public String state = "lit";     // lit | unlit (frame whole, no fire) | broken (frame partly gone) | gone
        public String by = "unknown";    // him | them | ruined | unknown
        public long seen;                // game time last seen
        public int link = -1;            // the other side's id
        public boolean linkWalked;       // learned by walking through (else none / predicted at read time)
        public String name = "";         // their words for it, if any
        Portal() {}
        public String where() { return at.getX() + ", " + at.getY() + ", " + at.getZ(); }
    }
    public static final class Edge {
        public int a, b; public String kind = "walked"; // walked | tunnel | highway
        public int y, length; public boolean covered, lit;
        Edge() {}
    }
    public static final class Net { public final List<Portal> portals = new ArrayList<>(); public final List<Edge> edges = new ArrayList<>(); int next = 1; String lastInDim; BlockPos lastIn; int lastArrival = -1, lastEntered = -1; }

    private static Map<UUID, Net> nets; // (static: fields added live start null)
    public static Net net(ClaudeEntity g) { if (nets == null) nets = new HashMap<>(); return nets.computeIfAbsent(g.getUUID(), k -> new Net()); }

    public static String dim(Level l) { return l.dimension().location().getPath(); }
    static ResourceKey<Level> key(String d) { return NETHER.equals(d) ? Level.NETHER : OW.equals(d) ? Level.OVERWORLD : Level.END; }
    public static Portal byId(ClaudeEntity g, int id) { for (Portal p : net(g).portals) if (p.id == id) return p; return null; }

    /** The portal record at (or within 3 of) this cell in that dimension, made if new. */
    static Portal find(ClaudeEntity g, String dim, BlockPos c) { for (Portal p : net(g).portals) if (p.dim.equals(dim) && Math.abs(p.at.getX() - c.getX()) <= 3 && Math.abs(p.at.getZ() - c.getZ()) <= 3 && Math.abs(p.at.getY() - c.getY()) <= 4) return p; return null; }
    public static Portal note(ClaudeEntity g, String dim, BlockPos cell, String by, String state) {
        Net n = net(g); BlockPos c = cell.immutable();
        { Portal p = find(g, dim, c); if (p != null) {
            if (!"unknown".equals(by) && ("unknown".equals(p.by) || "him".equals(by))) p.by = by;
            if (state != null && !state.equals(p.state)) { ClaudeMod.LOGGER.info("[Claude portal] #{} at {} ({}): {} -> {} (PortalMap.note)", p.id, p.where(), p.dim, p.state, state); p.state = state; }
            p.seen = g.level().getGameTime(); return p;
        } }
        Portal p = new Portal(); p.id = n.next++; p.dim = dim; p.at = c; p.by = by; p.state = state == null ? "lit" : state; p.seen = g.level().getGameTime();
        n.portals.add(p);
        ClaudeMod.LOGGER.info("[Claude portal] new #{}: {} portal at {} ({}), made by {} (PortalMap.note)", p.id, p.state, p.where(), p.dim, p.by);
        return p;
    }

    /** Each tick from ClaudeEntity.tick: the portal cell he stands in (the side he left from); every 2 s the portals he sees. */
    public static void tick(ClaudeEntity g) {
        Level lv = g.level(); BlockPos f = g.blockPosition();
        if (lv.getBlockState(f).is(Blocks.NETHER_PORTAL) || lv.getBlockState(f.above()).is(Blocks.NETHER_PORTAL)) { Net n = net(g); n.lastInDim = dim(lv); n.lastIn = (lv.getBlockState(f).is(Blocks.NETHER_PORTAL) ? f : f.above()).immutable(); }
        if (g.tickCount % 100 == 3) walkSample(g);
        if (g.tickCount % 40 != 7 || !(lv instanceof ServerLevel level)) return;
        for (Perception.Sighting s : Perception.landmarks(g)) // far sight: what he saw by line of sight
            if (s.id().equals("nether_portal") || s.id().equals("ruined_portal") || s.id().equals("obsidian")) { // (a frame seen first files as "obsidian")
                BlockPos c = s.nearestCell(g.getEyePosition()), at = c;
                for (BlockPos q : BlockPos.betweenClosed(c.offset(-3, -3, -3), c.offset(3, 3, 3))) if (level.getBlockState(q).is(Blocks.NETHER_PORTAL)) { at = lowest(level, q); break; }
                if (find(g, dim(level), at) != null) { note(g, dim(level), at, "unknown", null); continue; } // (the state only when new: refresh owns it after)
                String st0 = stateAt(level, at); if (!"lit".equals(st0) && !"unlit".equals(st0) && !s.id().equals("ruined_portal")) continue; // obsidian that isn't a frame (lava met water): not a portal
                note(g, dim(level), at, s.id().equals("ruined_portal") ? "ruined" : "unknown", st0);
            }
        refresh(g, level);
    }

    /** Known portals in this dimension within 48 that his eye has a clear line to: their state as he sees it now. */
    static void refresh(ClaudeEntity g, ServerLevel level) {
        String d = dim(level); Vec3 eye = g.getEyePosition();
        for (Portal p : net(g).portals) {
            if (!p.dim.equals(d) || p.at.distSqr(g.blockPosition()) > 48 * 48 || !level.isLoaded(p.at)) continue;
            if (!Perception.clear(level, eye, Vec3.atCenterOf(p.at)) && !Perception.clear(level, eye, Vec3.atCenterOf(p.at.above()))) continue; // (no seeing through walls)
            String now = stateAt(level, p.at);
            if (!now.equals(p.state)) { ClaudeMod.LOGGER.info("[Claude portal] #{} at {} seen {} now (was {}) (PortalMap.refresh)", p.id, p.where(), now, p.state); p.state = now; }
            p.seen = level.getGameTime();
        }
    }

    /** lit: portal blocks there; unlit: no fire but 8+ obsidian round it (a ghast put it out); broken: some frame left; gone: none. */
    public static String stateAt(ServerLevel level, BlockPos at) {
        int obs = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 4, 3))) { var st = level.getBlockState(q); if (st.is(Blocks.NETHER_PORTAL) && Math.abs(q.getX() - at.getX()) <= 1 && Math.abs(q.getZ() - at.getZ()) <= 1) return "lit"; if (st.is(Blocks.OBSIDIAN) || st.is(Blocks.CRYING_OBSIDIAN)) obs++; }
        return obs >= 8 ? "unlit" : obs > 0 ? "broken" : "gone";
    }
    static BlockPos lowest(ServerLevel level, BlockPos c) { BlockPos p = c; while (level.getBlockState(p.below()).is(Blocks.NETHER_PORTAL)) p = p.below(); return p.immutable(); }

    /** Called where ClaudeEntity.tick sees the dimension change: both sides noted and linked (learned by walking through). */
    public static void arrived(ClaudeEntity g) {
        Net n = net(g); Level lv = g.level();
        if (!(lv instanceof ServerLevel level) || !lv.getBlockState(g.blockPosition()).is(Blocks.NETHER_PORTAL)) { ClaudeMod.LOGGER.info("[Claude portal] changed world to {} not standing in a portal: nothing linked (PortalMap.arrived)", dim(lv)); return; }
        Portal to = note(g, dim(lv), lowest(level, g.blockPosition()), "unknown", "lit");
        n.lastArrival = to.id;
        if (n.lastIn != null && n.lastInDim != null && !n.lastInDim.equals(to.dim)) {
            Portal from = note(g, n.lastInDim, n.lastIn, "unknown", "lit");
            n.lastEntered = from.id;
            if (from.link != to.id || !from.linkWalked) ClaudeMod.LOGGER.info("[Claude portal] walked through: #{} at {} ({}) links to #{} at {} ({}) (PortalMap.arrived)", from.id, from.where(), from.dim, to.id, to.where(), to.dim);
            from.link = to.id; from.linkWalked = true;
            if (to.link < 0 || !to.linkWalked) { to.link = from.id; } // (the way back goes to the nearest within range of from*8 or /8; usually from itself: confirmed when he walks back)
            if (NETHER.equals(to.dim)) walkStart(g, to); else walkEnd(g, to);
        } else ClaudeMod.LOGGER.info("[Claude portal] came out at #{} {} ({}), the side he left unknown (PortalMap.arrived)", to.id, to.where(), to.dim);
    }

    // ---- the Nether ways between portals (edges): a walk from one portal to another is recorded; highways fill the same record later
    private static Map<UUID, long[]> walk; // {start portal id, start y sum, samples, start time}
    static void walkStart(ClaudeEntity g, Portal p) { if (walk == null) walk = new HashMap<>(); walk.put(g.getUUID(), new long[]{p.id, 0, 0, g.level().getGameTime()}); }
    /** Every 5 s in the Nether: his y, for the way's level. */
    public static void walkSample(ClaudeEntity g) { if (walk == null) return; long[] w = walk.get(g.getUUID()); if (w != null && NETHER.equals(dim(g.level()))) { w[1] += g.getBlockY(); w[2]++; } }
    static void walkEnd(ClaudeEntity g, Portal out) {
        Net n = net(g); if (walk == null || n.lastEntered < 0) return; long[] w = walk.remove(g.getUUID()); if (w == null) return;
        Portal a = byId(g, (int) w[0]), b = byId(g, n.lastEntered); if (a == null || b == null || a.id == b.id) return;
        Edge e = edge(g, a.id, b.id); if (e == null) { e = new Edge(); e.a = a.id; e.b = b.id; n.edges.add(e); }
        e.length = (int) Math.sqrt(a.at.distSqr(b.at)); if (w[2] > 0) e.y = (int) (w[1] / w[2]); else e.y = a.at.getY();
        ClaudeMod.LOGGER.info("[Claude portal] a Nether way walked: #{} -> #{}, about {} blocks at y {} ({}) (PortalMap.walkEnd)", a.id, b.id, e.length, e.y, e.kind);
    }
    public static Edge edge(ClaudeEntity g, int a, int b) { for (Edge e : net(g).edges) if ((e.a == a && e.b == b) || (e.a == b && e.b == a)) return e; return null; }
    /** A tunnel or highway he built or found between two portals (the highway milestone's entry point). */
    public static Edge addWay(ClaudeEntity g, int a, int b, String kind, int y, boolean covered, boolean lit) {
        Edge e = edge(g, a, b); if (e == null) { e = new Edge(); e.a = a; e.b = b; net(g).edges.add(e); }
        Portal pa = byId(g, a), pb = byId(g, b); e.kind = kind; e.y = y; e.covered = covered; e.lit = lit; e.length = pa != null && pb != null ? (int) Math.sqrt(pa.at.distSqr(pb.at)) : e.length;
        ClaudeMod.LOGGER.info("[Claude portal] way #{} - #{}: {} at y {}, {}, {}, {} blocks (PortalMap.addWay)", a, b, kind, y, covered ? "covered" : "open", lit ? "lit" : "dark", e.length); return e;
    }

    // ---- links: learned, else vanilla's rule
    /** Where the other side's portal is searched for: overworld /8, Nether *8 (in the other dimension). */
    public static BlockPos partnerSpot(Portal p) { return OW.equals(p.dim) ? new BlockPos(Math.floorDiv(p.at.getX(), 8), p.at.getY(), Math.floorDiv(p.at.getZ(), 8)) : new BlockPos(p.at.getX() * 8, p.at.getY(), p.at.getZ() * 8); }
    /** The portal it leads to: the walked link, else the nearest known one inside vanilla's search square round partnerSpot; null: unknown (the game finds one he doesn't know, or makes a new one there). */
    public static Portal leadsTo(ClaudeEntity g, Portal p) {
        if (p.linkWalked && byId(g, p.link) != null && !"gone".equals(byId(g, p.link).state)) return byId(g, p.link);
        String other = OW.equals(p.dim) ? NETHER : OW; int r = OW.equals(other) ? SEARCH_OW : SEARCH_NETHER; BlockPos t = partnerSpot(p);
        Portal best = null; double bd = Double.MAX_VALUE;
        for (Portal q : net(g).portals) if (q.dim.equals(other) && !"gone".equals(q.state) && Math.abs(q.at.getX() - t.getX()) <= r && Math.abs(q.at.getZ() - t.getZ()) <= r) { double d = q.at.distSqr(t); if (d < bd) { bd = d; best = q; } }
        return best;
    }
    /** "links to #3 at x y z (walked through)" / "(by the /8 rule)" / "unknown: ...". */
    public static String linkTalk(ClaudeEntity g, Portal p) {
        Portal q = leadsTo(g, p); BlockPos t = partnerSpot(p);
        if (q == null) return "where it leads: unknown, the game searches round " + t.getX() + ", " + t.getZ() + " in the " + (OW.equals(p.dim) ? "Nether" : "overworld") + " and makes a new portal there if it finds none (you know none within " + (OW.equals(p.dim) ? SEARCH_NETHER : SEARCH_OW) + ")";
        return "leads to #" + q.id + " at " + q.where() + " in the " + (OW.equals(q.dim) ? "overworld" : "Nether") + (p.linkWalked && p.link == q.id ? " (walked through)" : " (by the /8 rule, not walked yet)");
    }

    // ---- what the words mean: "the portal", "my portal", "the way back", "the fortress portal", "home"
    public static BlockPos home(ClaudeEntity g) { return g.house != null ? g.house.centre() : g.nook != null ? g.nook.mouth() : g.getRespawnPosition(); }
    public static Portal resolve(ClaudeEntity g, String words) {
        String w = words == null ? "" : words.toLowerCase(); String d = dim(g.level()); BlockPos me = g.blockPosition(); Net n = net(g);
        List<Portal> here = new ArrayList<>(); for (Portal p : n.portals) if (p.dim.equals(d) && !"gone".equals(p.state)) here.add(p);
        if (here.isEmpty()) return null;
        Comparator<Portal> near = Comparator.comparingDouble(p -> p.at.distSqr(me));
        if (w.matches(".*\\b(home|back|way back|base|house)\\b.*") && NETHER.equals(d)) { // the one whose overworld side is nearest home; else the one he came out of
            BlockPos h = home(g); Portal best = null; double bd = Double.MAX_VALUE;
            if (h != null) for (Portal p : here) { Portal o = leadsTo(g, p); if (o != null) { double dd = o.at.distSqr(h); if (dd < bd) { bd = dd; best = p; } } }
            if (best != null) return best;
            Portal arr = byId(g, n.lastArrival); if (arr != null && arr.dim.equals(d)) return arr;
        }
        for (String kind : List.of("fortress", "bastion_remnant")) if (w.contains(kind.substring(0, 7))) { // the portal nearest the one he has seen (here, or its other side's)
            Perception.Sighting s = null; double sd = Double.MAX_VALUE;
            for (Perception.Sighting x : Perception.sightings(g)) if (x.id().equals(kind) && x.dim() == Level.NETHER) { double dd = x.saw().distSqr(me); if (dd < sd) { sd = dd; s = x; } }
            if (s == null) return null;
            Portal best = null; double bd = Double.MAX_VALUE;
            for (Portal p : here) { Portal nside = NETHER.equals(d) ? p : leadsTo(g, p); if (nside == null) continue; double dd = nside.at.distSqr(s.saw()); if (dd < bd) { bd = dd; best = p; } }
            return best;
        }
        if (w.matches(".*\\b(my|mine|his|your)\\b.*")) { Portal mine = here.stream().filter(p -> "him".equals(p.by)).min(near).orElse(null); if (mine != null) return mine; }
        Portal arr = byId(g, n.lastArrival); if (w.matches(".*\\b(came|out of|arrived)\\b.*") && arr != null && arr.dim.equals(d)) return arr;
        return here.stream().filter(p -> "lit".equals(p.state)).min(near).orElse(here.stream().min(near).orElse(null));
    }

    // ---- routes: overworld from -> to, by foot or through the Nether
    public static final double NETHER_OFF_ROAD = 1.6; // a Nether walk with no way of his yet (netherrack, lava, drops) against a straight line
    /** "walking: N blocks; via portals #a -> #b: M (walk W to #a, ... )", or null when no linked pair helps. */
    public static String travelPlan(ClaudeEntity g, BlockPos from, BlockPos to) {
        double walkCost = Math.sqrt(from.distSqr(to)); String best = null; double bestCost = walkCost;
        for (Portal a : net(g).portals) { if (!OW.equals(a.dim) || "gone".equals(a.state)) continue; Portal na = leadsTo(g, a); if (na == null) continue;
            for (Portal b : net(g).portals) { if (!OW.equals(b.dim) || b == a || "gone".equals(b.state)) continue; Portal nb = leadsTo(g, b); if (nb == null || nb == na) continue;
                Edge e = edge(g, na.id, nb.id); double nether = e != null ? e.length * ("highway".equals(e.kind) ? 0.8 : 1.0) : Math.sqrt(na.at.distSqr(nb.at)) * NETHER_OFF_ROAD;
                double c = Math.sqrt(from.distSqr(a.at)) + nether + Math.sqrt(b.at.distSqr(to)) + 20; // (+20: two crossings, 4 s each, and the step-outs)
                if (c < bestCost) { bestCost = c; best = "via portals: walk " + (int) Math.sqrt(from.distSqr(a.at)) + " to #" + a.id + " (" + a.where() + "), " + (int) nether + " in the Nether " + (e != null ? "along your " + e.kind + " at y " + e.y : "off-road (no way of yours there yet)") + " to #" + nb.id + ", out at #" + b.id + " (" + b.where() + "), walk " + (int) Math.sqrt(b.at.distSqr(to)) + ": about " + (int) c; } } }
        return best == null ? null : "walking: about " + (int) walkCost + "; " + best;
    }

    /** The truth line: every portal he knows, nearest first, with what it is, where it leads, and the way home if portals help. */
    public static String truthLine(ClaudeEntity g) {
        Net n = net(g); if (n.portals.isEmpty()) return "none known (you remember every portal you use, light or see)";
        String d = dim(g.level()); BlockPos me = g.blockPosition(); long now = g.level().getGameTime(); BlockPos h = home(g);
        List<Portal> ps = new ArrayList<>(n.portals); ps.sort(Comparator.comparing((Portal p) -> !p.dim.equals(d)).thenComparingDouble(p -> p.at.distSqr(me)));
        StringBuilder sb = new StringBuilder();
        for (Portal p : ps) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("#").append(p.id).append(" ").append(p.state).append(" ").append(OW.equals(p.dim) ? "overworld" : NETHER.equals(p.dim) ? "Nether" : p.dim).append(" portal at ").append(p.where());
            if (p.dim.equals(d)) sb.append(" (").append((int) Math.sqrt(p.at.distSqr(me))).append(" blocks from you)");
            sb.append(", made by ").append("him".equals(p.by) ? "you" : "them".equals(p.by) ? "them" : "ruined".equals(p.by) ? "nobody (a ruined portal)" : "someone (not you)");
            sb.append(", last seen ").append(Math.max(0, (now - p.seen) / 1200)).append(" min ago, ").append(linkTalk(g, p));
            if (OW.equals(p.dim) && h != null && p.at.distSqr(h) < 64 * 64) sb.append(", by your home");
            for (Perception.Sighting s : Perception.sightings(g)) if (s.dim() == key(p.dim) && (s.id().equals("fortress") || s.id().equals("bastion_remnant")) && s.saw().distSqr(p.at) < 96 * 96) { sb.append(", near the ").append(s.id().equals("fortress") ? "fortress" : "bastion").append(" you saw"); break; }
            if ("unlit".equals(p.state)) sb.append(" (out: relight it with flint and steel on the bottom obsidian)");
            if (n.lastArrival == p.id) sb.append(" (the one you last came out of)");
        }
        for (Edge e : n.edges) sb.append("; Nether way #").append(e.a).append(" - #").append(e.b).append(": ").append(e.kind).append(", ").append(e.length).append(" blocks at y ").append(e.y).append(e.kind.equals("walked") ? "" : (e.covered ? ", covered" : ", open") + (e.lit ? ", lit" : ", dark"));
        if (OW.equals(d) && h != null && h.distSqr(me) > 300 * 300) { String tp = travelPlan(g, me, h); if (tp != null) sb.append("; to home ").append(tp); }
        Portal mineHere = ps.stream().filter(p -> p.dim.equals(d) && "him".equals(p.by)).findFirst().orElse(null);
        if (mineHere != null && OW.equals(d)) { BlockPos t = partnerSpot(mineHere); sb.append("; a Nether side built at ").append(t.getX()).append(", ").append(t.getZ()).append(" links to your #").append(mineHere.id).append(" both ways"); }
        return sb.toString();
    }

    // ---- enter_portal: which portal their words mean; walk to it, relight it if a ghast put it out, step in
    private static Map<Integer, Integer> walkedTo; // portal id -> tick of the last walk (one walk per 2 min: no loop on one he can't reach)
    /** The step, or null to let enter_portal take the nearest lit one in reach (no words naming one, none known). */
    public static String enterStep(ClaudeEntity g, ServerLevel level, Skills.Step st, String target) {
        String t = target == null ? "" : target.replace('_', ' ').trim();
        boolean named = !t.isEmpty() && !t.startsWith("@") && !t.matches("(the |a )?(nether )?portal|it|nearest|any");
        Portal p = resolve(g, t);
        if (p == null) { if (named) { ClaudeMod.LOGGER.info("[Claude portal] enter \"{}\": no portal he knows answers to it here (PortalMap.enterStep)", t); return "failed: no portal I know of here answers to \"" + t + "\" (portals I know: " + truthLine(g) + ")"; } return null; }
        if (!named && "lit".equals(p.state) && p.at.distSqr(g.blockPosition()) <= 24 * 24) return null; // (the nearest lit one in reach: enter_portal's own walk-in)
        if (level.isLoaded(p.at) && p.at.distSqr(g.blockPosition()) <= 16 * 16) { String now = stateAt(level, p.at); if (!now.equals(p.state)) { ClaudeMod.LOGGER.info("[Claude portal] #{} is {} now (was {}) (PortalMap.enterStep)", p.id, now, p.state); p.state = now; } }
        if ("gone".equals(p.state) || "broken".equals(p.state)) return "failed: my portal #" + p.id + " at " + p.where() + " is " + p.state + " (frame " + ("gone".equals(p.state) ? "gone" : "partly broken") + "): it needs rebuilding (build_portal there) before it goes anywhere";
        double d = Math.sqrt(p.at.distSqr(g.blockPosition()));
        if (d > 3.5) {
            if (walkedTo == null) walkedTo = new HashMap<>();
            Integer last = walkedTo.get(p.id); if (last != null && g.tickCount - last < 20 * 120 && d > 6) return "failed: couldn't get to portal #" + p.id + " at " + p.where() + " (" + (int) d + " off) on the last try";
            walkedTo.put(p.id, g.tickCount);
            g.tasks.add(new Task.GoTo(p.at, 2)); g.skillQueue.addFirst(new Skills.Step(st.skill(), target, st.count(), st.where()));
            ClaudeMod.LOGGER.info("[Claude portal] enter \"{}\" -> #{} at {} ({}, {} off): walking there (PortalMap.enterStep)", t, p.id, p.where(), p.state, (int) d);
            return "walking to portal #" + p.id + " at " + p.where() + " (" + linkTalk(g, p) + ")";
        }
        if ("unlit".equals(p.state)) { // a ghast's fireball put it out, or it was never lit: flint and steel on the bottom obsidian, from inside the frame
            if (!g.hold(s -> s.is(net.minecraft.world.item.Items.FLINT_AND_STEEL) || s.is(net.minecraft.world.item.Items.FIRE_CHARGE))) { g.skillQueue.addFirst(new Skills.Step(st.skill(), target, st.count(), st.where())); g.skillQueue.addFirst(new Skills.Step("obtain", "flint_and_steel", 1)); return "my portal #" + p.id + " is out: getting flint and steel to relight it"; }
            g.useOnAt(p.at.below(), net.minecraft.core.Direction.UP, false, Vec3.atCenterOf(p.at.below()).add(0, 0.5, 0));
            p.state = stateAt(level, p.at);
            ClaudeMod.LOGGER.info("[Claude portal] relit #{} at {}: {} (PortalMap.enterStep)", p.id, p.where(), p.state);
            g.skillQueue.addFirst(new Skills.Step(st.skill(), target, st.count(), st.where()));
            return "lit".equals(p.state) ? "relit my portal #" + p.id + " at " + p.where() : "failed: the frame of #" + p.id + " at " + p.where() + " didn't light (check it's whole and clear inside)";
        }
        g.noteDoing("into portal #" + p.id + " at " + p.where() + ": " + linkTalk(g, p));
        g.tasks.add(new Task.GoTo(p.at, 0.3)); g.tasks.add(new Task.Wait(100, false));
        ClaudeMod.LOGGER.info("[Claude portal] enter \"{}\" -> #{} at {}: stepping in, {} (PortalMap.enterStep)", t, p.id, p.where(), linkTalk(g, p));
        return "stepping into portal #" + p.id + " at " + p.where() + " (" + linkTalk(g, p) + ")";
    }

    // ---- saved per world (his mine tag)
    public static CompoundTag save(ClaudeEntity g) {
        Net n = net(g); CompoundTag t = new CompoundTag(); ListTag ps = new ListTag(), es = new ListTag();
        for (Portal p : n.portals) { CompoundTag c = new CompoundTag(); c.putInt("id", p.id); c.putString("dim", p.dim); c.putLong("at", p.at.asLong()); c.putString("state", p.state); c.putString("by", p.by); c.putLong("seen", p.seen); c.putInt("link", p.link); c.putBoolean("walked", p.linkWalked); c.putString("name", p.name); ps.add(c); }
        for (Edge e : n.edges) { CompoundTag c = new CompoundTag(); c.putInt("a", e.a); c.putInt("b", e.b); c.putString("kind", e.kind); c.putInt("y", e.y); c.putInt("len", e.length); c.putBoolean("cov", e.covered); c.putBoolean("lit", e.lit); es.add(c); }
        t.put("portals", ps); t.put("edges", es); t.putInt("next", n.next); t.putInt("arrival", n.lastArrival); return t;
    }
    public static void load(ClaudeEntity g, CompoundTag t) {
        Net n = net(g); n.portals.clear(); n.edges.clear();
        for (Tag x : t.getList("portals", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; Portal p = new Portal(); p.id = c.getInt("id"); p.dim = c.getString("dim"); p.at = BlockPos.of(c.getLong("at")); p.state = c.getString("state"); p.by = c.getString("by"); p.seen = c.getLong("seen"); p.link = c.getInt("link"); p.linkWalked = c.getBoolean("walked"); p.name = c.getString("name"); n.portals.add(p); }
        for (Tag x : t.getList("edges", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; Edge e = new Edge(); e.a = c.getInt("a"); e.b = c.getInt("b"); e.kind = c.getString("kind"); e.y = c.getInt("y"); e.length = c.getInt("len"); e.covered = c.getBoolean("cov"); e.lit = c.getBoolean("lit"); n.edges.add(e); }
        n.next = Math.max(1, t.getInt("next")); n.lastArrival = t.contains("arrival") ? t.getInt("arrival") : -1;
        ClaudeMod.LOGGER.info("[Claude portal] {} portal(s), {} way(s) back from his saved state (PortalMap.load)", n.portals.size(), n.edges.size());
    }
    /** The stopgap's two saved spots folded in once (a world saved before this). */
    public static void adopt(ClaudeEntity g, BlockPos owPortal, BlockPos netherPortal) {
        if (owPortal != null) note(g, OW, owPortal, "him", "lit");
        if (netherPortal != null) note(g, NETHER, netherPortal, "unknown", "lit");
    }
}
