# Urgent rest and food before optional cleanup

Live Nov 4 01:13:36-01:13:54: `TempLedger.tidy` sends him toward cobblestone at 548,13,90, producing 53-54-walk plans. The night rule, at health 20 and hunger 6, drops those walks for his bed at 549,16,54. Cleanup then starts again, pulling him away from the bed. Both walks accumulate watchdog failures. He eventually sleeps at 01:13:57; this does not make the repeating priority conflict correct.

Both automatic cleanup entry points now yield while sleeping, holding for the night, critically hurt (6 HP or less), or urgently hungry (6 food or less). The ledger still records vanished blocks and outstanding work. A single transition log explains the hold; once urgent needs clear, normal cleanup resumes. No failure counters or permanent write-offs are added.

Stage 575 reproduced the idle cleanup hijack before the change. Afterward it passes: idle cleanup and step-start cleanup both yield, then cleanup resumes when fed by day. Stage 570 also passes, retaining the corrected missing-tool retry behavior. Stage 199 validates the existing temporary-block take-down behavior separately. The whole repository gate was not rerun.

The live bot was observed fed again (health 20, hunger 20, 10 iron ingots) and working before deployment of this fix. That observation is not credited to this change. Deployment requires redefining only `TempLedger` from the test copy; no live compile or world repair. Hot reload is temporary until the commit is integrated into the live source.
