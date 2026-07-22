# Artifact Contracts

The normative machine schema is implemented by `scripts/check_modernization_artifacts.py`. Human-readable YAML below uses the same field names as the JSON bundle.

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
judgeCommands: []
```

All listed fields are required; use an empty array where a collection has no entries. A source `repositoryPath` must be a canonical, owned direct child of the trusted legacy workspace, not the workspace itself, `_worktrees`, a nested worktree, or a symlink. Every `evidencePath` must resolve at `sourceCommitSha` through Git modes `040000` and a final regular blob mode `100644`/`100755`; `120000`, `160000`, missing, ambiguous, oversized, or non-blob evidence fails closed. Git replace objects and pathspec interpretation are disabled.

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
```

Positional preflight may validate `draft`. A tracked canonical artifact must be `closed`, and closure requires exactly two independent, cryptographically distinct, valid signed PASS reviews of the evaluated snapshot. Rule-approval reviews count when they satisfy the same snapshot and independence requirements. Draft bundles belong outside the canonical artifact root.

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
3. Descendant commit C records the signed bundle and envelope under `.agents/payment-modernization/artifacts/` without changing the payload.
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
startCommitSha: full-40-character-sha
endCommitSha: full-40-character-sha
snapshotValid: true
verdict: PASS | FAIL
findings: []
commandsRun: []
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

The signature covers canonical JSON of every Review Result field except `signature`, including purpose, findings, commands, limitations, and approval subjects. An implementation review has no approval subjects; a rule-approval review has at least one. `startCommitSha`, `endCommitSha`, and `targetCommitSha` must be identical. A PASS cannot contain an unresolved BLOCKER.

`reviewIdempotencyKey` is namespaced canonical JSON over `evaluatedVersionKey`, `reviewerId`, and `reviewerRole`. Duplicate keys in one bundle fail, while two distinct reviewer identities can review the same evaluated version.

## Queue Item

```yaml
fingerprint: stable-root-cause-id
sliceId: stable-id
evaluatedVersionKey: sha256:full-64-character-digest
severity: BLOCKER | SHOULD_FIX
failureSource:
  type: rule
  ruleId: DOMAIN-NNN
# For type judge | build | test | review, replace ruleId with:
# checkId: stable-check-or-finding-id
trigger: reproducible-input
controlFlow: [at-least-one-step]
evidence: [at-least-one-item]
impact: concrete-outcome
verification: reproducible-command-or-procedure
status: open | implementing | reviewing | closed | human-decision
dependencies: []
```

`failureSource` is an exact tagged union. A `rule` source contains `type` and `ruleId`; `judge`, `build`, `test`, and `review` contain `type` and `checkId`. Queue `sliceId` and `evaluatedVersionKey` must bind the surrounding bundle.

## Authoritative Validation

Use positional bundle validation only as local preflight:

```text
python3 scripts/check_modernization_artifacts.py path/to/bundle.json
```

CI and release gates must scan the canonical tracked artifact root from one immutable commit and an external trust anchor:

```text
python3 scripts/check_modernization_artifacts.py \
  --repository-root <runtime-target-checkout> \
  --commit <full-gate-commit-sha> \
  --trusted-policy-commit <protected-base-or-previous-main-sha>
```

The authoritative mode reads policy, manifests, Rule payloads, and bundles through Git objects, rejects symlinks/gitlinks and files over 5 MiB, disables replace objects, proves evaluated commits are ancestors of the gate commit, and preserves effective approvals across policy/artifact history. The artifact root is closed-world: its required `README.md` plus regular `*.json` bundles are the only tracked entries, and every bundle must be closed. Across bundles, a repeated `reviewResultId` or `reviewIdempotencyKey` is accepted only when its complete signed canonical payload is identical; conflicting reuse fails. Bundles with source snapshots additionally require the trusted legacy containment root; a generic cloud runner without that evidence must fail closed.

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
queue item -> fixing commit -> independent review result
```
