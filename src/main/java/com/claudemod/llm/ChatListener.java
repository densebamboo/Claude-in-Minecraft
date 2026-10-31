package com.claudemod.llm;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.claudemod.ClaudeMod;
import com.claudemod.Config;
import com.claudemod.director.ClaudeChat;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.ClaudeEntity;
import com.google.gson.JsonObject;

import net.minecraft.server.level.ServerPlayer;

/** Player chat -> Claude's reply, and whatever the reply says to do. */
public final class ChatListener {
    private static final Map<UUID, Long> NEXT_ALLOWED_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    private ChatListener() {}

    public static void tryRespond(ServerPlayer player, String rawMessage) {
        respond(player, rawMessage, false);
    }

    /** directed = /claude ask. Chat counts when it says his name, he is near, or they are answering him. */
    public static void respond(ServerPlayer player, String rawMessage, boolean directed) {
        com.claudemod.entity.life.Whereabouts.heardChat(player, rawMessage); // "i'm at 120 64 -30": where they are, told
        com.claudemod.director.Primitives.heard(player, rawMessage); // "the diamonds are at 10 -58 4": a spot he was told of (dig_at / place_at may aim there)
        var heard = ClaudeEntity.activeFor(player); // "it despawned": his own clock on his things answers that, exactly (gone: he agrees and stops; time left: he says how much)
        if (heard.isPresent()) com.claudemod.entity.life.ExactSpot.heardEnd(heard.get(), rawMessage); // ("lesson over": the lesson he's in ends)
        if (heard.isPresent() && com.claudemod.entity.life.Consent.heard(heard.get(), player, rawMessage)) return; // "yes break it": their go-ahead for the hold he just said
        if (heard.isPresent() && com.claudemod.entity.life.Clocks.heardDespawn(heard.get(), player, rawMessage) != null) return;
        if (heard.isPresent() && com.claudemod.entity.life.CopyMode.heard(heard.get(), player, rawMessage)) return; // "watch me", "now you", "your turn": showing him, his go, his turn
        if (heard.isPresent() && com.claudemod.entity.life.ExactSpot.heard(heard.get(), player, rawMessage)) return; // "stand right where im standing": their exact feet cell, now (with more asked, the cell is kept and the chat plans the rest)
        if (heard.isPresent() && com.claudemod.entity.life.WatchBuild.heard(heard.get(), player, rawMessage)) return; // "watch what i build", "done", "build that again here": his watching and his learned builds answer that
        if (heard.isPresent() && com.claudemod.entity.life.OwnEvents.heardLeftover(heard.get(), player, rawMessage)) return; // "you didnt grab all your diamonds": his own book answers that, and he goes back for it
        if (heard.isPresent() && com.claudemod.entity.life.FarSight.heard(heard.get(), player, rawMessage)) return; // "im looking at one": his own eyes on where they look; "do you see any fortresses?": a careful look first, the chat answers
        if (!ClaudeEntity.brainDriven()) return;
        var claude = ClaudeEntity.activeFor(player);
        if (claude.isEmpty()) return;
        // chat is global in Minecraft: whatever they type, he read it, however far off he is (the 64-block rule dropped "go back to mining" twice tonight while he was 70-95 blocks away)
        boolean toHim = true;
        if (!toHim) return;

        UUID id = player.getUUID();
        long wait = NEXT_ALLOWED_AT.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (wait > 0) {
            // answer once the cooldown ends (the latest message; he sees the rest in memory)
            if (PENDING.put(id, rawMessage) == null) {
                CompletableFuture.delayedExecutor(wait + 200, TimeUnit.MILLISECONDS).execute(() -> player.server.execute(() -> {
                    String latest = PENDING.remove(id);
                    if (latest != null && !player.hasDisconnected()) respond(player, latest, directed);
                }));
            }
            return;
        }
        NEXT_ALLOWED_AT.put(id, System.currentTimeMillis() + Config.CHAT_COOLDOWN_SECONDS.get() * 1000L);

        if (directed) ClaudeApi.remember(player, player.getGameProfile().getName() + " (to you)", rawMessage);
        String context = ClaudeChat.truthFor(player, rawMessage); // (the ask: "ready for the nether?" brings the expert's nether check)
        ClaudeApi.conversation(player, rawMessage, context, directed).whenComplete((reply, err) -> player.server.execute(() -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                ClaudeMod.LOGGER.warn("[Claude] reply failed: {}", cause.getMessage());
                return;
            }
            String text = reply.get("text").getAsString();
            if (text == null || text.isBlank() || text.trim().equals("...")) { // no words from the model (three in a row to "where you headed now claude"): his own status line answers instead
                String truth = ClaudeChat.truthFor(player);
                int i = truth.indexOf("WHAT YOUR BODY IS DOING RIGHT NOW: ");
                String doing = i < 0 ? "" : truth.substring(i + "WHAT YOUR BODY IS DOING RIGHT NOW: ".length());
                if (doing.indexOf('\n') >= 0) doing = doing.substring(0, doing.indexOf('\n'));
                ClaudeMod.LOGGER.info("[Claude] the reply to \"{}\" had no text: answering from his status instead", rawMessage);
                text = doing.isBlank() ? "here, carrying on" : doing;
            }
            handle(player, reply, text, rawMessage);
        }));
    }

    /**
     * The reply applied, then its line said: act() first (the steps queued, their errand expanded), then the line checked against
     * what really got queued and his state (ClaudeChat.replyTruth), then said. (15:50 "ok, i'm in the water now, mining the
     * obsidian from here" went out before the errand turned the ask into "give 10 of the 11 on me": nothing was mined.) Returns
     * the line said (null: nothing).
     */
    public static String handle(ServerPlayer player, JsonObject reply, String text, String rawMessage) {
        String line = text, said = null;
        var g0 = ClaudeEntity.activeFor(player);
        try {
            if (g0.isPresent()) { act(g0.get(), player, reply, rawMessage); line = ClaudeChat.moveTruth(g0.get(), player, ClaudeChat.replyTruth(g0.get(), player, text, reply, rawMessage), reply != null && reply.has("action") ? reply.get("action").getAsString() : "none"); } // (the checked line: possession claims only, rewrites that stay sentences, patch_reply_truth3)
        } finally { said = ClaudeChat.say(player, line, "chat: " + rawMessage); }
        return said;
    }

    /** Applies what the reply says to do: a job, standing instructions, the shared goal, follow/stay/come/fight. */
    private static void act(ClaudeEntity g, ServerPlayer player, JsonObject reply, String rawMessage) {
        String goal = reply.get("new_goal").getAsString();
        String action = reply.get("action").getAsString();
        com.claudemod.ClaudeMod.LOGGER.info("[Claude reply] action={} change={} goal=\"{}\" steps={}", action, reply.get("plan_change").getAsString(), goal, reply.getAsJsonArray("steps"));
        g.awaitingAnswerUntil = 0; // they said something back ("good", "sure"): that's the answer, carry on
        if (!goal.isBlank() && !action.equals("fight")) ClaudeLife.theirGoal(g, goal);
        if (reply.getAsJsonArray("standing").size() > 0) ClaudeLife.standing(g, reply.getAsJsonArray("standing"));
        switch (action) {
            case "follow" -> ClaudeLife.follow(g, player);
            case "stay" -> ClaudeLife.stay(g);
            case "come" -> { if (player.isSpectator() && !rawMessage.toLowerCase().matches(".*\\b(come|here|over here|to me|with me|follow)\\b.*")) com.claudemod.ClaudeMod.LOGGER.info("[Claude reply] \"come\" for a spectator who didn't ask him over (\"{}\"): not walking to them", rawMessage); else ClaudeLife.come(g, player); } // ("beat the ender dragon" came back as action=come: 98 blocks at night toward a spectator, a creeper on the way)
            case "fight" -> ClaudeLife.fightNear(g, player, goal);
            case "stop" -> ClaudeLife.theyStopped(g, rawMessage); // (stop clears the ask; his own work resumes, nothing made up about them)
            default -> { }
        }
        // "this is your mine" / "you're in it" / "the one I'm standing at": that staircase (where they are) is his mine now
        if (rawMessage.toLowerCase().matches(".*(this is your mine|that'?s your mine|you'?re in it|use this mine|this one is yours|the one i'?m (standing|at)).*")) {
            net.minecraft.core.BlockPos at = com.claudemod.entity.life.Whereabouts.aware(g, player) ? com.claudemod.director.ClaudeLife.theirSpot(g, player) : null; // (where he sees them, or just heard them)
            if (at != null) { g.adoptMineAt = at.immutable(); g.prefs.put("new_mine", "yes"); }
            else ClaudeChat.say(player, "which one? i can't see you from here, send me its coords", "adopt mine: can't see them");
        }
        // "break the chests that aren't in your storage": done, not discussed
        String low = rawMessage.toLowerCase();
        if (low.matches(".*(break|get rid of|pick up|remove|take).*chests?.*(aren'?t|not in|outside|other|extra|stray).*")
                || low.matches(".*(break|get rid of|pick up|remove|take).*(other|extra|stray|random|leftover).*chests?.*")) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude command] {}", ClaudeLife.breakStrayChests(g, player));
            return;
        }
        if (g.prefs.containsKey("storage_at") && low.matches(".*(fill|finish|complete|add|place|put|another|3rd|third|more).*(chest|row|layer|storage|stack|it in).*")
                && !low.matches(".*(break|get rid of|remove|take).*") && !low.matches(".*-?[0-9]+[ ,]+-?[0-9]+[ ,]+-?[0-9]+.*")) { // filling in his storage (not a new spot): done, not discussed
            com.claudemod.ClaudeMod.LOGGER.info("[Claude command] {}", ClaudeLife.fillStorageNow(g, player));
            return;
        }
        // "put your storage chests at x y z": that's where his storage goes, set up there now (not a chest dropped next to them)
        var storage = java.util.regex.Pattern.compile("(?:storage|chests?)[^0-9-]*?(-?[0-9]+)[ ,]+(-?[0-9]+)[ ,]+(-?[0-9]+)").matcher(rawMessage.toLowerCase());
        if (storage.find()) {
            ClaudeLife.setUpStorageNow(g, player, new net.minecraft.core.BlockPos(Integer.parseInt(storage.group(1)), Integer.parseInt(storage.group(2)), Integer.parseInt(storage.group(3))));
            return;
        }
        if (ClaudeLife.bedtimeWords(rawMessage) && !action.equals("stop")) { // "go to bed claude": the sleep step (the reply made it go_to home, his unfinished door, for 10 minutes)
            boolean has = false; for (var el : reply.getAsJsonArray("steps")) if (el.getAsJsonObject().get("skill").getAsString().equals("sleep")) has = true;
            if (!has) {
                com.claudemod.ClaudeMod.LOGGER.info("[Claude command] \"{}\" is bedtime: the sleep step (the reply had {})", rawMessage, reply.getAsJsonArray("steps"));
                ClaudeLife.command(g, player, ClaudeLife.sleepSteps(), goal.isBlank() ? "go to bed (their words: \"" + rawMessage + "\")" : goal, "replace");
                return;
            }
        }
        if (reply.getAsJsonArray("steps").size() > 0) {
            ClaudeLife.command(g, player, reply.getAsJsonArray("steps"), goal.isBlank() ? goal : goal + " (their words: \"" + rawMessage + "\")", reply.get("plan_change").getAsString()); // (their own words kept with the goal: "a stack of copper" lost "from your chest", and his own planning went back to mining it)
        } else if (action.equals("none") && rawMessage.toLowerCase().matches(".*\\b(help|save me|hurry)\\b.*")) {
            ClaudeLife.helpThem(g, player); // a cry for help is an order even if the reply forgot to make it one
        } else if (action.equals("none") && rawMessage.toLowerCase().trim().matches("(ok |okay |yeah |yes )?(continue|keep going|carry on|resume|go on|back to it|keep at it)[^a-z]*")
                && (!g.playerGoal.isBlank() && !g.playerGoal.startsWith("done") || !g.pausedGoal.isBlank())) {
            String job = !g.playerGoal.isBlank() && !g.playerGoal.startsWith("done") ? g.playerGoal : g.pausedGoal;
            g.pausedGoal = "";
            ClaudeLife.orderFromWords(g, player, job); // "continue": the job they gave him before, picked back up
        } else if (action.equals("none") && rawMessage.toLowerCase().trim().matches("(ok |okay |no |nah )?(stop|stop it|stop that|stop everything|cancel|cancel that|cancel it|nvm|never ?mind|nevermind)[^a-z]*")) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude command] \"{}\" is a plain stop and the reply gave no action: stopping (ChatListener.act)", rawMessage);
            ClaudeLife.theyStopped(g, rawMessage);
        } else if (action.equals("none") && !ClaudeLife.giveIfAsked(g, player, rawMessage) && looksLikeOrder(rawMessage)) {
            // an order ("go back to mining") but the reply only said "ok": drop what he's doing and plan from their words
            ClaudeLife.orderFromWords(g, player, goal.isBlank() ? rawMessage : goal + " (their words: \"" + rawMessage + "\")");
        }
    }

    public static boolean looksLikeOrder(String raw) {
        String m = raw.toLowerCase().trim();
        if (m.endsWith("?")) return false;
        return m.matches(".*\\b(go|get|make|mine|mining|dig|chop|cut|build|kill|beat|defeat|slay|hunt|fight|bring|craft|find|put|place|light|keep|collect|smelt|cook|gather|start|go back|head|explore|gear|prepare|prep|clear|take|grab|farm|plant|harvest|store|give|hand)\\b.*"); // (\\b: a word boundary; the old \b was a backspace, so this never matched)
    }
}
