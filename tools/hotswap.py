"""Push freshly compiled mod classes into the running live game (no restart).

Compiles, then redefines every class file that changed since the last push through the
JDWP debug port (127.0.0.1:5005) the live run opens. The live run uses the JetBrains
runtime with enhanced redefinition, so methods and fields may change too; a brand-new
class just loads from disk the first time it's used.
Usage: python tools/hotswap.py
"""
import os, subprocess, sys, socket

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASSES = os.path.join(ROOT, "build", "classes", "java", "main")
STAMP = os.path.join(ROOT, "build", "hotswap.stamp")
DEPLOYED = r"C:\Users\lipid\curseforge\minecraft\Instances\Claude in Minecraft\mods\claude-0.1.0.jar.curseforge"
JDB = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\jdb.exe"


def main():
    try:
        socket.create_connection(("127.0.0.1", 5005), timeout=2).close()
    except OSError:
        sys.exit("live game isn't running (nothing on port 5005): start it with 'Play Live.bat'")
    since = os.path.getmtime(STAMP) if os.path.exists(STAMP) else 0
    r = subprocess.run([os.path.join(ROOT, "gradlew.bat"), "compileJava", "--offline", "-q"], cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("compile failed:\n" + r.stdout[-3000:] + r.stderr[-3000:])
    changed = []
    for d, _, files in os.walk(CLASSES):
        for f in files:
            p = os.path.join(d, f)
            if f.endswith(".class") and os.path.getmtime(p) > since:
                changed.append((os.path.relpath(p, CLASSES)[:-6].replace(os.sep, "."), p))
    if not changed:
        print("nothing changed")
        return
    # The game loads the mod from the deployed jar, not build/classes: a class that jar doesn't have can't
    # "load fresh", so pushing the classes that use it crashes the game (Task$Fish, 2026-10-28). Restart instead.
    # ponytail: runLive loads build/classes (every class is there), so LIVE_CLASSES=1 skips the jar check
    if os.path.exists(DEPLOYED) and not os.environ.get("LIVE_CLASSES"):
        import zipfile
        have = {n[:-6].replace("/", ".") for n in zipfile.ZipFile(DEPLOYED).namelist() if n.endswith(".class")}
        new = [name for name, _ in changed if name not in have]
        if new:
            sys.exit("NOT PUSHED: new classes the running game can't load (" + ", ".join(new[:5]) + "): build the jar and restart")
    cmds = "".join(f"redefine {name} {path}\n" for name, path in changed) + "quit\n"
    jdb = subprocess.run([JDB, "-connect", "com.sun.jdi.SocketAttach:hostname=127.0.0.1,port=5005"],
                         input=cmds, capture_output=True, text=True, timeout=120)
    out = jdb.stdout + jdb.stderr
    open(os.path.join(ROOT, "build", "hotswap.log"), "w").write(cmds + "\n----\n" + out)
    lines = out.splitlines()
    notloaded = [l for l in lines if "not loaded" in l.lower() or "No class named" in l]
    keys = ("rror", "failed", "nsupported", "Exception", "not implemented", "schema change", "cannot")
    bad = [l for l in lines if any(k in l for k in keys) and l not in notloaded]
    if bad:
        print("NOT ALL CLASSES WENT IN, the game needs a restart (Play Live.bat). Problems:")
        for l in bad[:20]:
            print("  " + l)
        sys.exit(1)
    open(STAMP, "w").close()
    print(f"pushed {len(changed) - len(notloaded)} classes into the running game "
          f"({len(notloaded)} not loaded yet: they load fresh when first used)")


if __name__ == "__main__":
    main()
