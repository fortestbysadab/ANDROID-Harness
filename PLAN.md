# Android Harness — build plan

A standalone Android agent. Everything the old Termux-based agent could do,
in one installable app, with per-tool switches — and **one** optional
dependency on Termux, for sandboxed code execution only.

---

## The shape of the thing

```
┌──────────────────────────────────────────────┐
│  Android Harness (one APK)                   │
│                                              │
│  UI            settings, per-tool switches   │
│  Agent core    planner, policy, registry,    │
│                approvals, scheduling, audit  │
│  Tools         SMS, location, notifications, │
│                clipboard, media, email, …    │
│  Capabilities  ── CodeSandbox ──┐            │
└─────────────────────────────────│────────────┘
                                  │ optional
                        ┌─────────▼─────────┐
                        │ Termux            │
                        │ proot + rlimits   │
                        │ separate UID      │
                        └───────────────────┘
```

Termux is **a provider behind an interface**, not a foundation. The app is
fully functional without it, missing exactly one feature.

---

## Why Termux for the sandbox, and nothing else

Two reasons, and the second is the one that matters.

**1. It is irreplaceable.** A full Linux userland, `apt`, ~2,900 packages,
arbitrary native binaries and `proot`. A normal Android app cannot
reproduce that: Android's W^X rules forbid executing binaries from a
writable data directory, which is exactly why Termux itself can no longer
ship on Play Store.

**2. It runs under a different UID.** If model-written code escapes `proot`
inside Termux, it lands in *Termux's* world. It cannot read this app's API
keys, session database or approvals store. That is an OS-enforced boundary.

Compare the obvious alternative — embedding Python in-process with
Chaquopy and sandboxing there. Python would run in *our* process, holding
*our* secrets. An escape owns everything. Delegating to Termux is a
security **upgrade** over in-app isolation, not a compromise.

### The rule this has to pass

> A bridge to another app is worth its cost only when that app offers
> something you genuinely cannot replicate in your own.

The predecessor project built an Accessibility Service in a second APK
with a socket bridge and a pairing token, then deleted it — an
Accessibility Service can simply be a class in the same app, so the bridge
was pure overhead. Termux passes the test; that did not. Any future
proposal to talk to another app must pass the same test.

---

## The one rule that decides whether this works

**The app must be fully functional without Termux, minus exactly one
feature.**

The failure mode is creep. Termux is connected and working, so a second
feature quietly leans on it, then a third, and in six months the thing we
were escaping is back with extra steps.

Enforcement: anything that reaches `TermuxSandbox` outside the
`CodeSandbox` interface is a bug. A test asserts that no tool other than
the document tools can reach it.

---

## Phases

Each phase ends with something installable that works. No phase depends on
a later one being finished.

### Phase 0 — Prove the round trip ✅

The only question that matters right now: *can this app get a command run
in Termux and the output back?* If not, the architecture changes today
rather than in two months.

- [x] Repo, Gradle, CI building a debug APK with no local toolchain
- [x] `TermuxBridge` — `RUN_COMMAND` intent, results via `PendingIntent`
- [x] `TermuxStatus` — distinguish *not installed* / *permission ungranted*
      / *`allow-external-apps` off* / *wrong build*, each with its remedy
- [x] A diagnostics screen that runs `echo` and shows exactly what came back
- [x] **Confirmed working on the owner's device** — 2026-10-26.
      `ROUND TRIP WORKS`; `python`, `proot` and `aarch64` all reported.

Exit: a screenshot of stdout coming back from Termux. **Done.**

### Phase 1 — The sandbox capability

Split, because the file-handoff question is separable from "does
sandboxed execution work at all" and much fiddlier.

**1a — execution** ← **built, awaiting device confirmation**

- [x] `CodeSandbox` interface, defined before any implementation
- [x] `TermuxSandbox`: `proot`, rlimits, fresh workspace per run
- [x] Named failure codes with remedies — `sandbox_unavailable`,
      `sandbox_denied`, `sandbox_timeout`, `sandbox_script_failed`
- [x] An isolation check that is itself the proof: the script tries to
      read the owner's files and Termux's home and must find nothing
- [x] `ImportRuleTest` — Termux creep is now a failing test, not a habit
- [ ] Confirmed on the owner's device

Exit: Python runs under proot, and the isolation check reports everything
blocked or empty.

**1b — getting files back out**

Output currently returns as stdout, which is bounded by Binder's
transaction limit and cannot carry a PDF. Options, undecided: shared
storage with All-files access, a folder chosen once through SAF, or
chunked base64. Decide with a spike, not in advance.

**1c — a resident daemon**

Started once via `RUN_COMMAND`, so a document does not pay process-spawn
cost per run.

Exit: a Python script writes a PDF and the app reads it back.

### Phase 2 — Agent core

Ported from the predecessor largely unchanged; it is plain Python with no
Termux assumptions. Runs under Chaquopy.

- Planner, tool registry, runtime loop, sessions
- Policy engine: risk × actor → allow / approve / deny
- Approvals bound to a sha256 of tool + version + arguments
- Taint tracking for untrusted content
- Audit log — **with rotation this time**
- Durable approvals — **persisted this time**, not in-memory

Exit: a conversation with one safe tool, end to end, on device.

### Phase 3 — Native tools

Each is a Kotlin implementation behind a Python-visible `ToolSpec`. These
replace Termux:API, which was only ever a thin wrapper over the same
platform APIs.

Clipboard, notifications, battery, TTS, vibration, media, contacts, SMS,
telephony, location, camera, files.

Ordered easiest first so the harness is proven before the awkward ones.

Exit: parity with the predecessor's Termux:API tools.

### Phase 4 — Interface

- Chat screen
- **Per-tool switches** — the thing `.env` could never do well
- Permission state surfaced honestly: which tools are unavailable and why
- Termux connect / disconnect, with the real diagnosis when it fails

### Phase 5 — Screen automation

An Accessibility Service — this time just a class in this app. No second
APK, no socket, no pairing token.

### Phase 6 — Channels

Telegram, email. Unchanged in spirit from the predecessor.

---

## Decisions already made

| Question | Decision | Why |
| --- | --- | --- |
| Language for the core | Python under Chaquopy | The core is already written and is portable |
| Language for tools | Kotlin | Direct platform APIs, fewer layers than Termux:API |
| Sandbox | Termux via `CodeSandbox` | Irreplaceable, and separate UID |
| Distribution | Sideload, built in CI | Play Store forbids SMS/call-log/accessibility for this use |
| Background execution | Foreground service | The sanctioned route; more reliable than Termux ever was |
| Scheduling | WorkManager / AlarmManager | Survives reboot, OS-managed |
| UI toolkit | Views, not Compose | Fewer moving parts, faster CI, no version pitfalls |

## Deliberately not decided yet

- Whether the console stays web or becomes native
- Whether to keep Telegram once a real UI exists
- Second sandbox provider (WASM, `isolatedProcess`) — only if Termux proves
  too awkward in practice

---

## Known traps, recorded before they bite

- **Play Store Termux will not work.** It is a separate fork, rolled back
  ten releases, with `RUN_COMMAND` removed. F-Droid or GitHub only, and the
  signatures differ, so switching sources means uninstalling first.
- **`allow-external-apps=true` is a global switch.** It permits *every* app
  holding `RUN_COMMAND` to run commands in Termux. Termux's own docs warn
  against enabling it casually. Onboarding must say so plainly.
- **Package visibility.** Targeting SDK 30+ without a `<queries>` entry for
  `com.termux` makes the intent fail silently.
- **Termux needs "Display over other apps"** to be started in the
  background by another app on Android 10+.
- **Phantom process killer.** Android 12+ kills app-forked children. In-process
  Chaquopy avoids it; anything we fork does not.
- **Sideloaded accessibility is blocked** until the owner clears
  Android's "Restricted setting" dialog, which only appears after the
  toggle has been attempted once.

---

## Engineering rules, carried over

These were learned the hard way and are not up for rediscovery.

1. **Mutation-check every regression test.** A test that still passes with
   the fix deleted is worse than none. Several tests in the predecessor
   passed for the wrong reason and were caught only this way.
2. **Measure, do not infer.** Four plausible diagnoses of a location bug
   were each disproved by instrumentation.
3. **Verify documentation edits landed.** A silent `str.replace` miss made
   four doc changes quietly do nothing.
4. **Every failure gets a named code and a remedy the owner can act on.**
   "It didn't work" is not a result.
5. **The model proposes, the application executes.** Risk is declared by
   the tool, never inferred from model output.
6. **Spike before committing.** One throwaway day beats one wasted month.
