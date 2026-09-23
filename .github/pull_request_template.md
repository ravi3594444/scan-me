## Feature IDs

<!-- From docs/features.md, e.g. F-E2, F-E10. Put them in the PR title too. -->

## What changed

## Acceptance criteria touched

<!-- Copy each criterion from docs/features.md and mark how it is covered. -->

- [ ] Criterion — covered by unit / golden / integration test
- [ ] Criterion — needs the device lab (say which scenario in docs/testing.md §4)

## Spec changes

<!-- Edits to docs/architecture.md made in this PR, with the "Changed:" note. Write "None" if none. -->

## Checks

- [ ] `./gradlew build` passes locally
- [ ] New protocol messages have golden CBOR bytes checked in
- [ ] Bench CSV row attached if this touches `core/transfer`, `core/ladder` or a radio (handoff §7)
- [ ] User-facing strings are externalised
