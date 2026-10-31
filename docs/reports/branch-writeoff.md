# UNTESTED: finishing a branch skips a cell the watchdog wrote off (Day to Day item 49)

Branch `bp/branch-writeoff`. **Not merged:** no stage covers it yet.

## Cause

- **Live, 05:12 World 15:** the watchdog climbed its whole ladder on the deepslate at 25 -57 184, wrote it off and told the user.
- **Then strip_mine picked it again:** "finishing my branch at -4, -58, 184 (never dug out)" picked the same cell 4 more times. That block in `bpStripMine`, at both of its sites, didn't read the Attempts record.

## What changed

- **The branch now ends at a written-off cell.** If the first undug row's feet or head cell is on record in Attempts as REACH or BREAK within the last 10 minutes, the branch is marked done there (`branchDone:` counter) and it's logged: `his branch at X ends at Y: its next cell is written off`.

## Why it isn't merged

- **The project rule:** untested work stays on its branch.
- **Why no stage yet:** a stage needs his mine at the real ore heights (world y about 16 or lower), with the face at the ore's level, which sits outside the test platform's y range. The full gate was not run.

## Still open

- **Item 50:** why a plain deepslate block wouldn't break with a diamond pick. Not proven.
