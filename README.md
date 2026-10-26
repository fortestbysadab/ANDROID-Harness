# Android Harness

A standalone Android agent: one installable app, per-tool switches, and
**one** optional dependency — Termux, used only as a sandbox for running
model-written code.

Successor to [ANDROID-AGENT](https://github.com/fortestbysadab/ANDROID-AGENT),
which ran entirely inside Termux.

> **Status: Phase 0.** Proving that this app can run a command in Termux
> and read the output back. Nothing else is built yet, on purpose — if the
> round trip does not work, the architecture changes now rather than in two
> months. See [PLAN.md](PLAN.md).

## Why Termux is still here, for one thing only

Model-written code has to run *somewhere* isolated. Termux is the right
place for two reasons:

- **It is irreplaceable.** A full Linux userland with `proot` and ~2,900
  packages. Android's W^X rules forbid a normal app from executing binaries
  out of its own writable storage — which is exactly why Termux itself can
  no longer ship on Play Store.
- **It runs under a different Android user.** Code that escapes `proot`
  lands in Termux's world, not ours. It cannot read this app's API keys or
  session database. Embedding Python in-process instead would put model
  code inside the same process as the secrets.

Everything else — SMS, location, notifications, clipboard, scheduling,
screen automation — is a native Android API in this app. No Termux:API.

**The rule:** the app is fully functional without Termux, missing exactly
one feature. Anything reaching `TermuxBridge` outside the `CodeSandbox`
interface is a bug.

## Build it

No laptop needed. **Actions → Build harness APK → Run workflow**, then
download the `harness-debug-apk` artifact.

## Try the round trip

1. Install Termux from **F-Droid or GitHub**. The Play Store build will not
   work: it is a separate fork with `RUN_COMMAND` removed. The app detects
   this and says so.
2. Install the harness APK and open it.
3. Grant **Run commands in Termux environment** — Android Settings → Apps →
   Android Harness → Permissions → Additional permissions.
4. In Termux:
   ```sh
   echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```
   Note this permits *any* app holding the `RUN_COMMAND` permission to run
   commands in Termux. Grant that permission sparingly.
5. Tap **Run echo in Termux**.

`ROUND TRIP WORKS` means Phase 0 is done.

## Layout

```
app/      the Android app (Kotlin)
core/     the agent core (Python, Phase 2)
docs/     architecture and decisions
PLAN.md   phases, decisions, and the traps found so far
```
