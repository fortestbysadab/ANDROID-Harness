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
- [x] Confirmed on the owner's device — 2026-10-26, realme RMP2402:
      Python 3.14.6 ran in the sandbox; `home: empty`, `sdcard: empty`,
      `storage: empty`, `workspace writable: True`

Exit: Python runs under proot, and the isolation check reports everything
blocked or empty. **Met.**

**1b — getting files back out**

Output currently returns as stdout, which is bounded by Binder's
transaction limit and cannot carry a PDF.

**Decided: shared storage with all-files access.** The three candidates
were shared storage, a folder chosen once through SAF, and chunked
base64. SAF hands back a content URI, and Termux writes to a POSIX path,
so there is no way to point one at the other. Chunking inherits the exact
limit it was meant to escape. Shared storage also happens to be where the
owner wants finished documents anyway — a PDF only visible inside this
app is not much of a document — so it was going to be written there
regardless of how it travelled.

Rejected on evidence: `/sdcard/Android/data/com.androidharness/files/`
would have avoided the broad permission, but Android 11+ blocks cross-app
access to `/Android/data`, so Termux cannot write there at all. Plain
MediaStore reads are out too: a PDF is not covered by `READ_MEDIA_*`.

Egress stays mediated. The script never sees shared storage — `/sdcard`
and `/storage` are still empty directories inside proot. The wrapper
copies out only the filenames the caller declared in advance, *after*
proot exits, and prints its report after the exit marker where a script
cannot forge it. A run that produces nothing leaves no directory behind.

- [x] Wrapper copies declared outputs to `/storage/emulated/0/AndroidHarness/runs/<run id>`
- [x] `WrapperReport` parses the post-exit-marker metadata; forged lines ignored
- [x] `OutputNames` refuses anything that could traverse or split the shell loop
- [x] `SharedStorage` reads the files back, re-checking the path it was handed
- [x] All-files access requested honestly, with a button and a status line
- [x] Unit tests, including the forgery attempts
- [x] Wrapper exercised end to end on Linux against a stand-in `proot`
- [x] Confirmed on the owner's device — 2026-10-26, realme RMP2402:
      `file: report.txt (62 bytes)` with the contents read back intact

Exit: a Python script writes a file in the sandbox and the app reads it
back. **Met.** Needs **all-files access** granted to this app, and
**`termux-setup-storage`** run once in Termux — without it the wrapper
reports `__HARNESS_NO_STORAGE__` and the app says so.

**1c — a resident daemon — MEASURED, AND DROPPED**

Measured on the owner's device, 2026-10-26, realme RMP2402, median of
five runs:

| layer | median | the cost it isolates |
| --- | --- | --- |
| `echo` in Termux | 53 ms | Termux IPC |
| wrapper with `sh` | 243 ms | **+190 ms** for proot |
| wrapper with `python` | 348 ms | **+105 ms** for the interpreter |

**The phase as planned would have been a waste.** A resident Termux
session removes the 53 ms of IPC and nothing else - 15% of a run - while
adding a polled file channel on shared storage, a MAC scheme to stop
other apps injecting jobs, and a liveness problem when Android kills the
session. The expensive part, proot at 190 ms, is exactly what it would
not have touched.

And 348 ms is not a problem worth solving. A run sits behind a model
call measured in seconds; shaving a third of a second off it is
invisible. **Measured before building, so the cost of being wrong was
one button instead of a subsystem.**

If it ever does matter, the measurement says what to build instead: keep
*proot and the interpreter* warm - one long-lived Python process inside
one long-lived proot container, taking jobs - not a resident shell. That
would address 295 ms of the 348. Two findings already stand for whenever
that day comes:

- **No named pipes.** The channel must be shared storage, because this
  app cannot write into Termux's private directory, and `mkfifo` is
  refused on FUSE-emulated storage. Polling, therefore, with a latency
  floor of its own.
- **A daemon taking jobs from shared storage is reachable by any app
  with storage access** - free arbitrary code execution in Termux. Jobs
  would need a MAC keyed by a secret passed at startup and never written
  to disk.

### Phase 2 — Agent core

Ported from the predecessor largely unchanged; it is plain Python with no
Termux assumptions. Runs under Chaquopy.

**2a — prove the embedding first.** The same discipline as Phase 0: no
code is ported until CPython is shown to start in this app and bring a
usable standard library with it. The probe checks the three things the
core cannot do without - the interpreter itself, the compiled stdlib
modules `sqlite3` and `hashlib` that a repackaged stdlib tends to lose,
and a writable private directory, since durable approvals were a known
defect in the predecessor and the fix is a database on disk.

- [x] Chaquopy on the build, Python 3.13, arm64 and x86_64 only
- [x] `harness_core` package and a probe that reports rather than greets
- [x] Interpreter start timed separately from the call
- [x] Confirmed on the owner's device — 2026-10-26, realme RMP2402:
      CPython 3.13.16 on aarch64, SQLite 3.53.4, private directory
      writable; interpreter start **556 ms**, probe call **3 ms**

Exit: the app starts its own interpreter, imports the stdlib, and writes
a file to its private directory. **Met.**

The 556 ms is a one-time cost per process, and it is paid lazily on the
first use. Before the core sits on the path of an incoming message, that
start has to move to app launch, on a background thread, so the first
request does not wait for it.

**2b — the safety spine.** Ported first, because everything else is
allowed to exist only once this is in place.

- [x] `tools.py` — `Risk`, `ToolContext`, `ToolSpec`, schema validation,
      `returns_untrusted_content`. Across unchanged; it was always plain
      Python.
- [x] `policy.py` — `DefaultPolicy` and `UnattendedPolicy`. Unchanged.
- [x] `approvals.py` — **rewritten, not ported.** See below.
- [x] 15 tests, all seven mutants killed
- [x] Core tests run in CI on the runner's Python
- [x] Confirmed on the owner's device — 2026-10-26: changed action
      refused, approval survived a restart, second attempt refused

**2c — the gate.** `gate.py` is the only way a tool call can happen:

    known tool? -> arguments valid? -> policy -> approval -> run it

No step is skippable, because there is no other entry point to a
handler. Three properties come from the predecessor's runtime, lifted
out of the planner so they can be tested without a model in the loop:
risk is declared by the tool rather than inferred from model output;
approval binds to the exact arguments; and taint spreads forward and is
never cleared, so a sensitive read after an email has been read needs
confirming even though the owner started the run.

`audit.py` keeps its interface and loses its defect: the JSONL sink
appended for ever, which on a phone is a storage leak the owner cannot
explain. It now rotates by size with a fixed number of generations, so
the footprint has a statable ceiling of `max_bytes x (keep + 1)`. The
redaction rule is unchanged and deliberate — the caller passes redacted
fields, because a sink that guesses at secrets will eventually guess
wrong and write a key to disk.

- [x] `tools.py` gains `ToolRegistry`; the model cannot add to it
- [x] `gate.py` with 17 tests
- [x] `audit.py` rotating, with 11 tests
- [x] 9 mutants, 8 killed, 1 survivor fixed by deleting dead code
- [ ] Confirmed on the owner's device

**Why the approval store was rewritten.** The predecessor kept approvals
in a dictionary. Android restarts background processes constantly, so
every pending approval died with the process, and the owner tapping
"yes" was told it had expired — indistinguishable from a real timeout.
It is now a SQLite table in the app's private directory, with three
properties each held up by a test and checked by deleting the code that
provides it:

| property | how it is enforced |
| --- | --- |
| authorises one exact action | a sha256 over tool, version and arguments, re-computed at spend time rather than taken from the caller |
| can be spent once | a conditional `UPDATE`; two racing callers cannot both see `rowcount == 1` |
| expires | `expires_at` in the same `WHERE` clause |

Refusals carry named codes — `approval_action_changed` is a very
different event from `approval_expired`, and collapsing them would hide
the only one that indicates an attack.

**Note on having two Pythons.** They are not redundant. The embedded one
runs the agent and holds the keys; the Termux one runs model-written
code under a different Android user. Merging them would put untrusted
code in the same process as the secrets, which is the exact arrangement
this architecture exists to avoid.

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
