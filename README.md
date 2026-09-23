# scan-me — Nearby File Sharing App (code name `drop`)

An offline, AirDrop-style file sharing app for Android 12+ and desktop (Windows, macOS, Linux).
Devices find each other over Bluetooth Low Energy or a QR scan ("scan to send"); files travel over a
direct 5 GHz Wi-Fi link with no internet, no SIM, no account and no mobile data. Anyone without the app
can receive in a browser. Owner: Constrivo Group.

## Where to start

| Read | For |
| --- | --- |
| [`docs/implementation-plan.md`](docs/implementation-plan.md) | The build plan: work packages, order, estimates, open decisions, spec changes, progress log |
| [`docs/README.md`](docs/README.md) | Index of the handoff pack and glossary |
| [`docs/PRD.md`](docs/PRD.md) | Product requirements and success metrics |
| [`docs/features.md`](docs/features.md) | Every feature with ID, priority and acceptance criteria |
| [`docs/design.md`](docs/design.md) | Screens, motion, copy, accessibility |
| [`docs/architecture.md`](docs/architecture.md) | Modules, transport ladder, beacon, handshake, transfer protocol, data model, security |
| [`docs/handoff.md`](docs/handoff.md) | Decisions, conventions, environment, week-1 plan, definition of done |
| [`docs/roadmap.md`](docs/roadmap.md) | Phases and the week-by-week Phase 1 plan |
| [`docs/testing.md`](docs/testing.md) | Device lab, benchmark procedure, scenario matrix |

Suggested order for a new engineer: this file, then the plan, then `PRD.md`, `design.md`, `architecture.md`, `handoff.md`.

## Picking up a work package

1. Find the next open work package in the progress log at the end of `docs/implementation-plan.md`.
2. Read its section in the plan and the pack sections it names.
3. Resolve the open decisions and spec changes it lists first.
4. One work package per branch and pull request; feature IDs (for example `F-E2`) in the PR title and test names.
5. Push only when the build and tests are green.
