# Architecture

## One app, one optional neighbour

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
└──────────────────────────────────│───────────┘
                                   │ optional
                         ┌─────────▼─────────┐
                         │ Termux            │
                         │ proot + rlimits   │
                         │ separate UID      │
                         └───────────────────┘
```

## Layers and what may import what

| Layer | May import |
| --- | --- |
| `ui` | agent core, capabilities |
| agent core (Python) | tool interfaces only |
| `tools` | Android APIs, capability interfaces |
| `sandbox` | nothing app-specific |
| `termux` | **only** `TermuxSandbox` may import this |

That last row is the rule the whole design rests on. If a second feature
starts reaching into `com.androidharness.termux`, the app has quietly
become Termux-dependent again, which is the thing this project exists to
avoid.

## Why the capability interface comes before the capability

`CodeSandbox` is defined in Phase 0, before any implementation exists,
because an interface written after its first implementation tends to
describe that implementation rather than the need. The predecessor's
`Backend` protocol was written this way and made replacing ADB with an
installed app a one-line change at the composition root.

## Trust boundaries

Three, in increasing order of distrust:

1. **The owner.** Trusted. Approves risky actions.
2. **The model.** Proposes; never executes. Risk is declared by the tool,
   never inferred from what the model says it is doing.
3. **Content the model has read** — email, web pages, screen text. Hostile
   by default. Once a tool returning untrusted content has succeeded, the
   run is tainted and further sensitive reads need approval.

Model-written code is category 3 twice over: written by the model, often
from content it has read. That is why it runs in a different Android user
rather than in this process.

## What changes from the predecessor

| | ANDROID-AGENT | Android Harness |
| --- | --- | --- |
| Runtime | Termux | This app |
| Device APIs | Termux:API wrappers | Native Android |
| Staying alive | Battery whitelisting, phantom-process fights | Foreground service |
| Scheduling | Termux job scheduler | WorkManager |
| Screen automation | ADB, or a second APK with a socket bridge | An Accessibility Service in this app |
| Sandbox | proot in Termux | proot in Termux, through an interface |
| Approvals | In memory, lost on restart | Persisted |
| Audit log | Grew without bound | Rotated |

The last two are known defects being carried forward as fixes, not
features.
