# Rejecting a pair is not mining progress

Live Nov 4 00:29:03-00:29:05: both directions off row 1145 log "would run at the cave he died by". Nevertheless `bpAtLevelY` reports a new pair, queues a walk and selects the same row again; the circle detector eventually drops mining.

Attempt each eligible candidate and accept it only if it actually adds blueprint rows. Otherwise log the refusal and check the next candidate; if all pairs fail, use the existing push/new-level-tunnel fallbacks. Danger and nook guards stay intact.

Stage 571 failed before the fix (reported a pair with no added rows). After the fix it passes: a rejected row leads to real additional planned work, including the existing new-tunnel fallback when no pair fits. This does not itself implement the user's newer requirement to tunnel through water by sealing it as he advances; that is separate work.
