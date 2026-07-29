# Artifact Contracts

The normative machine schema is implemented by `scripts/check_modernization_artifacts.py`. Human-readable YAML below uses the same field names as the JSON bundle. Every policy, registry, Rule payload, bundle, signature payload, and digest input is strict UTF-8 JSON: duplicate members at any depth, `NaN`, `Infinity`, `-Infinity`, and parsed or serialized non-finite numbers are forbidden. Canonical serialization uses sorted keys, compact separators, ASCII escaping, and `allow_nan=false`. Every schema-version field requires the exact JSON integer shown by its contract; boolean and floating-point numeric aliases are rejected.

## Policy Bootstrap

Every immutable read starts with `.agents/payment-modernization-policy.json` at the declared commit:

```yaml
schemaVersion: 2
targetRepositoryId: payment-web-platform
canonicalRepositoryPath: /Users/mac/Documents/demo/payment-web-platform
rulebookPaths: []
ruleCardPaths: []
judgePaths: []
trustedReviewers:
  - reviewerId: stable-independent-reviewer-id
    reviewerRole: business-security
    keyId: stable-key-id
    signatureAlgorithm: Ed25519
    publicKey: base64-encoded-32-byte-public-key
```

The fixed policy path is the bootstrap trust location. `targetRepositoryId` is the portable identity; `canonicalRepositoryPath` is the project baseline locator. CI may map that identity to another checkout with `--repository-root`, but a bundle cannot choose a different identity. Rulebook and Judge registries are sorted, unique repository-relative paths and include the policy itself plus mandatory governance inputs. `ruleCardPaths` is a subset of `rulebookPaths`.

Reviewer keys are taken from an external `--trusted-policy-commit`, never merely from a bundle-selected base. The current registry is intentionally empty, so approvals fail closed until a separately authorized key-bootstrap process exists. Key rotation and rule retirement are not yet supported and therefore fail closed.

The evaluated `judgePaths` must include `.agents/payment-modernization-judge-registry.json`. Its exact JSON schema is `{schemaVersion: 1, checks: [...]}`; every check has only `checkId`, registered Judge `path`, exact `command`, and non-empty `ruleIds`. An approved Rule's `judgeTests` values must resolve to checks that name that Rule ID.

## Capability Slice

```yaml
turnId: immutable-orchestrator-turn-id
sliceId: stable-id
path: reimagine | transform
sourceSnapshots:
  - sourceSnapshotId: stable-source-id
    repositoryPath: /absolute/canonical/direct-child-git-repository
    sourceCommitSha: full-40-character-sha
    evidencePaths: [repository-relative-regular-blob]
    readMethod: validated-git-object | validated-git-archive | isolated-clone
targetRepositoryId: payment-web-platform
targetRepositoryPath: /Users/mac/Documents/demo/payment-web-platform
targetBaseSha: full-40-character-sha
rulebookManifest:
  label: human-readable-display-only
  paths: []
  rulebookDigest: sha256:full-64-character-digest
judgeManifest:
  label: human-readable-display-only
  paths: []
  judgeDigest: sha256:full-64-character-digest
taskIdentityKey: sha256:full-64-character-digest
nonGitEvidence:
  - absolutePath: /absolute/human-approved-evidence-path
    sha256: full-64-character-sha256
    purpose: why-this-file-is-required
actors: []
inputs: []
outputs: []
ruleIds: []
dependencies: []
ownedPaths: []
forbiddenChanges: []
entryCriteria: []
exitCriteria: []
judgeCommands: [immutable-judge-check-id]
```

All listed fields are required; collections other than `judgeCommands` may be empty. A closed slice must declare at least one unique Judge registry check ID; draft discovery may keep it empty. A source `repositoryPath` must be a canonical, owned direct child of the trusted legacy workspace, not the workspace itself, `_worktrees`, a nested worktree, or a symlink. Every `evidencePath` must resolve at `sourceCommitSha` through Git modes `040000` and a final regular blob mode `100644`/`100755`; `120000`, `160000`, missing, ambiguous, oversized, or non-blob evidence fails closed. Git replace objects and pathspec interpretation are disabled.

Non-Git evidence is optional. Every entry requires prior human authorization and an external resolver that returns the preauthorized bytes; filesystem existence alone is not authorization. A missing resolver or digest mismatch fails closed.

`taskIdentityKey` is the SHA-256 of namespaced canonical JSON containing the complete normative slice: both baseline manifests without their display-only labels, every source/non-Git entry, every scope and acceptance list, the stable target ID, and `targetBaseSha`. It excludes `targetRepositoryPath`, the display labels, itself, and the not-yet-created output commit.

## Evaluated Snapshot and Bundle

```yaml
lifecycleStatus: draft | closed
capabilitySlice: { ... }
evaluatedSnapshot:
  targetCommitSha: full-40-character-output-sha
  rulebookManifest:
    label: human-readable-display-only
    paths: []
    rulebookDigest: sha256:full-64-character-digest
  judgeManifest:
    label: human-readable-display-only
    paths: []
    judgeDigest: sha256:full-64-character-digest
  evaluatedVersionKey: sha256:full-64-character-digest
ruleCards: []
reviewResults: []
queueItems: []
queueHistoryEvidence: []
```

Positional preflight may validate `draft`. A tracked canonical artifact must be `closed`, and closure requires exactly two independent, cryptographically distinct, valid signed PASS reviews of the evaluated snapshot. Every declared `capabilitySlice.judgeCommands` ID must resolve in the evaluated immutable Judge registry, and each PASS reviewer must sign exactly one matching execution with the registry command, target SHA, `exitCode: 0`, and result digest. This applies even when all Rule Cards are candidates or the slice has no Rule Card. Rule-approval reviews count when they satisfy the same snapshot and independence requirements. Draft bundles belong outside the canonical artifact root.

`targetBaseSha` must be an ancestor of `targetCommitSha`. Baseline manifests resolve the baseline policy registry at the former; evaluated manifests resolve the evaluated policy registry at the latter. Both digests use the versioned, length-framed, sorted path-and-byte algorithm. Labels never affect identity.

`evaluatedVersionKey` uses namespaced canonical JSON over `taskIdentityKey`, `targetCommitSha`, evaluated `rulebookDigest`, and evaluated `judgeDigest`. A bundle's Rule Cards are the registered subset named by that slice, and their IDs must exactly equal `capabilitySlice.ruleIds`; a bundle does not copy every global Rule Card.

## Rule Payload and Approval Envelope

The Rule payload is a standalone JSON object tracked at a path in `policy.ruleCardPaths`:

```yaml
ruleId: DOMAIN-NNN
status: candidate | approved
statement: concise-normative-rule
scope: []
given: []
when: []
then: []
counterexamples: []
evidence:
  - kind: git
    sourceSnapshotId: stable-source-id
    evidencePath: repository-relative-path
    location: line-or-section
  - kind: non-git
    source: /absolute/human-approved-evidence-path
    sha256: full-64-character-sha256
    location: line-or-section
  - kind: decision
    source: docs/adr/NNNN-decision.md
    targetBaseSha: full-40-character-sha
    location: line-or-section
confidence: high | medium
judgeTests: []
```

Evidence is an exact tagged union. Git evidence must match a declared source snapshot and one of its already validated paths. Non-Git evidence must match the Capability Slice's preauthorized path and digest. Decision evidence must be a baseline Rulebook path, bind `targetBaseSha`, and resolve as a safe Git blob. Requested-approved rules require non-empty evidence and Judge tests.

A bundle wraps the exact Rule payload from its evaluated commit:

```yaml
rulePath: rules/DOMAIN-NNN.json
rulePayload: { ...exact immutable payload... }
# The following fields exist only when rulePayload.status is approved:
approvalCommit: full-40-character-reviewed-commit
approvedBy: [independent-reviewer-b, independent-reviewer-c]
approvalReviewRefs: [review-result-b, review-result-c]
```

`status: approved` is a request, not effective approval. Approval is a detached two-commit flow:

1. Commit B records the immutable requested-approved payload. Its repository gate intentionally fails while proof is absent.
2. Exactly two independent, distinct trusted reviewers sign PASS results that bind B, the evaluated manifests, and the exact Rule payload digest.
3. A single-parent descendant commit C records only regular JSON signed bundles and envelopes under `.agents/payment-modernization/artifacts/`, without changing any other tree path.
4. The repository gate at C requires B to be its ancestor, the current payload to equal B, and exactly one unique valid envelope. Identical envelope references across capability bundles are deduplicated; conflicting envelopes fail.

Never place an envelope naming B inside B, and never squash, rebase, or amend B after signing. Effective approved state and registered governance paths are append-only from the trusted policy anchor. Downgrade, deletion, key replacement, or retirement fails until a trusted protocol is added.

## Review Result

```yaml
reviewResultId: stable-review-result-id
taskIdentityKey: sha256:full-64-character-digest
evaluatedVersionKey: sha256:full-64-character-digest
reviewerId: independent-reviewer-id
reviewerRole: business-security | implementation-adversary | other-explicit-role
reviewIdempotencyKey: sha256:full-64-character-digest
targetCommitSha: full-40-character-sha
rulebookDigest: sha256:full-64-character-evaluated-digest
judgeDigest: sha256:full-64-character-evaluated-digest
queueDigest: sha256:canonical-queue-items-digest
startCommitSha: full-40-character-sha
endCommitSha: full-40-character-sha
snapshotValid: true
verdict: PASS | FAIL
findings:
  - findingId: stable-finding-id
    severity: BLOCKER | SHOULD_FIX
    status: open | implementing | reviewing | closed | human-decision
    repositoryRelativePath: repository/relative/file
    line: 1
    symbol: reachable-symbol
    controlFlow: [at-least-one-step]
    trigger: reproducible-input
    impact: observable-outcome
    evidence: [at-least-one-item]
    verification: reproducible-procedure
    targetCommitSha: full-40-character-sha
    evaluatedVersionKey: sha256:full-64-character-digest
    rulebookDigest: sha256:full-64-character-digest
    judgeDigest: sha256:full-64-character-digest
    resolution: unresolved | fixed | rejected | deferred
commandsRun:
  - checkId: immutable-judge-check-id
    command: exact-command-from-judge-registry
    targetCommitSha: full-40-character-sha
    exitCode: 0
    resultDigest: sha256:full-64-character-result-digest
limitations: []
keyId: trusted-key-id
signatureAlgorithm: Ed25519
reviewPurpose: implementation | rule-approval
approvalSubjects:
  - rulePath: rules/DOMAIN-NNN.json
    ruleId: DOMAIN-NNN
    rulePayloadDigest: sha256:full-64-character-digest
signature: base64-ed25519-signature
```

The signature covers canonical JSON of every Review Result field except `signature`, including purpose, exact-schema findings, Judge executions, limitations, approval subjects, and `queueDigest`. Unknown finding/command fields or enum values fail. Open/implementing/reviewing findings use `resolution: unresolved`; closed uses `fixed` or `rejected`; human-decision uses `deferred`. An implementation review has no approval subjects; a rule-approval review has at least one. `startCommitSha`, `endCommitSha`, and `targetCommitSha` must be identical. A PASS cannot contain an unresolved BLOCKER or a non-zero command result. Every closed-slice Judge check and each approved Rule check must have exactly one signed matching command with `exitCode: 0` in both reviews.

Every `commandsRun` entry that names an evaluated Judge registry check must use that registry's exact command. An unregistered execution is accepted only when its `checkId` is referenced by a `build` or `test` Queue source in the same bundle; the valid Review signature then binds both the execution and the current `queueDigest`. Unregistered executions with no such Queue source fail closed.

`reviewIdempotencyKey` is namespaced canonical JSON over `evaluatedVersionKey`, `reviewerId`, and `reviewerRole`. Duplicate keys in one bundle fail, while two distinct reviewer identities can review the same evaluated version.

## Queue Item

```yaml
queueItemSchemaVersion: 2
fingerprint: stable-root-cause-id
sliceId: stable-id
evaluatedVersionKey: sha256:full-64-character-digest
severity: BLOCKER | SHOULD_FIX
failureSource:
  type: rule
  ruleId: DOMAIN-NNN
# For type judge | review, replace ruleId with:
# checkId: stable-check-or-finding-id
# For type build | test, use:
# type: build
# checkId: stable-build-or-test-id
# originExecution:
#   checkId: stable-build-or-test-id
#   command: exact-original-failing-command
#   targetCommitSha: full-40-character-failure-commit
#   exitCode: 1
#   resultDigest: sha256:full-64-character-original-result-digest
trigger: reproducible-input
controlFlow: [at-least-one-step]
evidence: [at-least-one-item]
impact: concrete-outcome
verification: reproducible-command-or-procedure
status: open | implementing | reviewing | closed | human-decision
resolution: unresolved | fixed | flaky | rejected | deferred
failedReviewRounds: 0
initialStateHistory:
  - status: open
    evaluatedVersionKey: sha256:full-64-character-origin-version
    failedReviewRounds: 0
dependencies: []
```

`queueItemSchemaVersion: 2` is mandatory. Queue Items that predate the explicit version or omit `initialStateHistory` fail closed. This Queue schema upgrade does not change Review Result canonicalization or the existing `payment-modernization-queue-v2` digest namespace: the complete new Queue Item bytes enter the existing `queueDigest`, and that digest remains part of both canonical signed Review payloads.

The Queue Item field set is exact: every field shown above, including `dependencies`, is required and unknown fields are rejected. `queueItemSchemaVersion` is the JSON integer `2`; the numerically equal JSON number `2.0` is not the same schema value. Nested `evidence` and `dependencies` remain strict JSON arrays and participate byte-for-byte in canonical identity. Queue comparison serializes strict canonical JSON rather than using host-language equality, so JSON integer `1`, boolean `true`, and number `1.0` are three different values at every nesting depth.

`failureSource` is an exact tagged union. A `rule` source contains `type` and `ruleId`; that ID must resolve both to a unique Rule Card in the evaluated policy registry and to the exact Rule Card included by the current Capability Slice. A globally registered Rule from another slice and an unregistered Rule both fail closed. `judge` and `review` sources contain only `type` and `checkId`. A Judge source must resolve in the evaluated Judge registry and to at least one exact-command execution in a fully valid signed Review Result.

A `build` or `test` source contains `type`, `checkId`, and `originExecution`. `originExecution` uses the exact command-execution schema, repeats the same `checkId`, records the original command and failure commit, requires a non-zero `exitCode`, and content-addresses the original result. For a closed build/test Queue Item, that failure commit must be a **strict ancestor** of the evaluated snapshot; equality is rejected, so a failure and successful verification at the same commit cannot close the item. The object is part of the immutable Queue root and therefore of the signed `queueDigest`; later versions cannot reinterpret that `checkId` by changing the command, exit code, or result digest. Current signed verification executions for that source must use the original command. Build/test sources still must not reuse a Judge registry ID and must resolve to at least one execution in a fully valid Review Result whose signature binds the current `queueDigest`. Because v2 has no separate typed build/test gate registry, free-text criteria and unsigned CI names do not satisfy this source contract.

Every Queue Item carries an explicit signed `resolution`. `open`, `implementing`, and `reviewing` require `unresolved`; `human-decision` requires `deferred`; `closed` requires exactly one of `fixed`, `flaky`, or `rejected`. `flaky` is limited to build/test sources. `fixed` claims a later commit repaired the failure, while `flaky` and `rejected` are explicit non-fix adjudications and must never be reported as fixed. A resolution cannot change while status stays unchanged. Review-sourced Queue Items must use the same resolution as their signed finding.

Judge/build/test `checkId` values are unique across Queue Items; repeated independent reviewer attestations are allowed only when the command is identical. Review `findingId` values are unique across the whole bundle. Every valid Review finding with a status other than `closed` must have exactly one Queue Item whose unique review `checkId` is `review:<findingId>` and whose severity, status, resolution, trigger, control flow, evidence, impact, and verification exactly match the signed finding; every review Queue Item must map back to a real finding, so orphan, duplicate, or missing mappings fail closed.

`initialStateHistory` is the immutable bootstrap transcript for the first persisted state of one fingerprint. It must start at `open` with `failedReviewRounds: 0`, contain only real transitions allowed by the Queue state machine, use a transcript-wide unique `evaluatedVersionKey` for every state, and end at the state first observed in repository history. Uniqueness is not merely adjacent: `A -> B -> A` is a replay and fails. Each historical key means the exact evaluated snapshot under which that transition was judged; it is verified by recomputing the defined `evaluatedVersionKey` from the retained task identity, target commit, and evaluated Rulebook/Judge digests and matching the signed review evidence. A key is never an arbitrary nonce or display label. The complete transcript is signed through `queueDigest` and can never be shortened, appended to, or rewritten after that first observation.

`queueHistoryEvidence` is the retained proof for every non-current key in that transcript. It is an optional bundle field only for backward compatibility with Queue Items whose `initialStateHistory` contains exactly the current key. Once a transcript contains any non-current key, omission is invalid; legacy 64-hex placeholders are not grandfathered or allowlisted. Each list entry uses the exact schema below:

```yaml
evaluatedSnapshot:
  targetCommitSha: full-40-character-historical-target-sha
  rulebookManifest: { ... }
  judgeManifest: { ... }
  evaluatedVersionKey: sha256:full-64-character-historical-key
queueItems: []
reviewResults: []
```

The checker resolves each historical target from immutable Git objects, requires it to descend from the retained slice base and strictly precede the current target, recomputes both manifests at that tree, and derives the key from the current bundle's retained `taskIdentityKey`. The entry's non-empty Queue snapshot must preserve the current item's immutable root, bind its historical key and slice, and exactly reproduce the transcript's `status`, `resolution`, and `failedReviewRounds`. Exactly two independent trusted `PASS` implementation reviews must sign that historical snapshot, its Queue digest, and one successful evaluated Judge execution per declared capability check. Review IDs and idempotency keys remain unique across current and retained reviews.

Historical target commits follow transcript order as a strict ancestor chain. Each successor transition target is single-parent; a merge target cannot directly claim a state transition and is accepted only after a later single-parent, newly evaluated reconciliation. The same Queue state machine and status-compatible resolution rules apply to retained states. These checks complement the repository-level single-parent JSON envelope rule for the first canonical appearance and every later persisted change.

This bootstrap transcript resolves the canonical-root deadlock without weakening closure: the canonical root still accepts only `closed` bundles and still rejects every unresolved `BLOCKER`. A resolved BLOCKER that has never appeared in canonical history may first be persisted as `closed` only when its signed `initialStateHistory` replays the complete legal path from `open` through implementation and review to `closed`. Ordinary later states do not rewrite that transcript; they are replayed against real direct Git parents using the outer `status`, `evaluatedVersionKey`, and `failedReviewRounds`, with a new evaluated version and fresh independent signatures for every status change.

Queue `sliceId` and current `evaluatedVersionKey` bind the surrounding bundle. Repository history is append-only by fingerprint: immutable root-cause fields cannot change, entries cannot disappear, and status transitions are checked. A conflict in one fingerprint never suppresses validation of the merge's other Queue items. Divergent merge-parent states remain blocking until a subsequent single-parent commit actually changes the signed Queue state and supplies a newly evaluated reconciliation satisfying every parent transition; an unchanged code-only descendant cannot clear the conflict. Each `reviewing -> implementing` retry increments the signed counter; the third unsuccessful review must enter `human-decision`, and an authorized continuation resets it to zero. A closed state without the required signatures is invalid.

Repository replay evaluates every relevant commit against its real direct Git parent or parents. A state-changing commit cannot reuse any `evaluatedVersionKey` already observed for that fingerprint in the replayed transcript; an unchanged carried-forward state keeps its existing key. Restoring the final tree does not erase an invalid middle state: `A -> tampered B -> restored C` reports B's immutable commit SHA. The same type-strict canonical comparison applies across commits, merge parents, reconciliations, and duplicate Queue fingerprints across bundles; legal linear histories and explicitly signed single-parent fork reconciliations remain accepted.

Every first appearance or later change of a signed Queue fingerprint is activated only by a single-parent envelope commit. Its complete tree delta may contain only regular `*.json` files below `.agents/payment-modernization/artifacts/`, and the surrounding bundle's `evaluatedSnapshot.targetCommitSha` for each changed Queue Item must equal that activation commit's direct parent. This binds the reviewers to the actual pre-envelope gate tree: source code cannot be co-committed with Queue bootstrap/transition, and an older evaluated target cannot bless an unreviewed intermediate tree.

## Authoritative Validation

Use positional bundle validation only as local preflight:

```text
python3 -I scripts/check_modernization_artifacts.py path/to/bundle.json
```

CI and release gates must scan the canonical tracked artifact root from one immutable commit and an external trust anchor:

```text
python3 -I scripts/check_modernization_artifacts.py \
  --repository-root <runtime-target-checkout> \
  --commit <full-gate-commit-sha> \
  --trusted-policy-commit <protected-base-or-previous-main-sha>
```

The external policy anchor is mandatory; repository mode never falls back to a parent commit. The authoritative mode reads policy, manifests, Rule payloads, and bundles through Git objects, rejects symlinks/gitlinks, files over 5 MiB, shallow repositories, graft metadata, replace objects, and Git environment overrides that can redirect repository or graph state; Git metadata output must be one exact terminated line. It derives parents from raw commit headers, forces submodule differences and disables external diff drivers, proves evaluated commits are ancestors of the gate commit, and replays the complete merge history. Historical policy, parse, signature, or bundle failures remain blocking after deletion. The artifact root is closed-world: its required `README.md` plus regular `*.json` bundles are the only tracked entries, and every bundle must be closed. Across bundles and history, a repeated `reviewResultId` or `reviewIdempotencyKey` is accepted only when its complete signed canonical payload is identical; conflicting reuse fails. Bundles with source snapshots additionally require the trusted legacy containment root; a generic cloud runner without that evidence must fail closed.

## Traceability

Maintain these edges:

```text
trusted policy commit -> reviewer keys and stable target repository identity
sourceSnapshotId + repositoryPath + sourceCommitSha + evidencePath -> rule ID
human-approved non-Git evidence path + SHA-256 -> rule ID
decision/ADR at targetBaseSha -> rule ID
complete normative Capability Slice -> taskIdentityKey
targetBaseSha + baseline path/byte manifests -> baseline digests
targetCommitSha + evaluated path/byte manifests -> evaluated digests
taskIdentityKey + targetCommitSha + evaluated digests -> evaluatedVersionKey
evaluatedVersionKey + reviewerId + reviewerRole -> reviewIdempotencyKey
signed approval subjects -> immutable Rule payload digest
effective approval envelope -> later repository gate commit
Rule Queue source -> evaluated registered Rule Card -> current Capability Slice
build/test originExecution -> immutable Queue root -> signed queueDigest
initial open state -> signed initialStateHistory -> first persisted Queue state
evaluated direct-parent tree -> envelope-only Queue activation commit -> changed signed Queue state
queue item -> fixing commit -> independent review result
```
