# Artifact Contracts

## Capability Slice

```yaml
sliceId: stable-id
path: reimagine | transform
sourceSnapshots:
  - sourceSnapshotId: stable-source-id
    repositoryPath: /absolute/canonical/repository-path
    sourceCommitSha: full-40-character-sha
    evidencePaths: []
    readMethod: git-show | git-archive | isolated-clone
targetRepositoryPath: /absolute/canonical/target-repository-path
targetBaseSha: full-40-character-sha
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

`repositoryPath` must identify a canonical Git repository, not its multi-repository parent or an `_worktrees` entry. Resolve Git evidence only from `sourceCommitSha` and the declared `evidencePaths`; do not use live-checkout content. Omit `nonGitEvidence` when it is not required. Every non-Git entry requires explicit human scope and a SHA-256 digest before inspection. A missing or unverifiable source object, path, digest, or `targetBaseSha` blocks the slice.

## Rule Card

```yaml
ruleId: DOMAIN-NNN
status: candidate | approved | rejected | superseded
statement: concise normative rule
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

Use only the fields for the selected evidence `kind`; do not represent missing identities with placeholder values. A `git` entry resolves through the matching Capability Slice `sourceSnapshotId`. A `decision` entry resolves from `targetBaseSha`.

Only approved rules can produce an automatic PASS.

## Queue Item

```yaml
fingerprint: stable-root-cause-id
sliceId: stable-id
versionKey: turnId:commitSha:rulebookVersion
severity: BLOCKER | SHOULD_FIX
ruleId: DOMAIN-NNN
trigger: reproducible input
controlFlow: []
evidence: []
impact: concrete outcome
verification: reproducible command or procedure
status: open | implementing | reviewing | closed | human-decision
dependencies: []
```

## Review Result

```yaml
reviewer: independent-reviewer-id
versionKey: turnId:commitSha:rulebookVersion
startCommitSha: full-sha
endCommitSha: full-sha
snapshotValid: true
findings: []
commandsRun: []
limitations: []
```

## Traceability

Maintain these edges:

```text
sourceSnapshotId + repositoryPath + sourceCommitSha + evidencePath -> rule ID
human-approved non-Git evidence path + SHA-256 -> rule ID
decision/ADR -> rule ID
rule ID -> capability slice
rule ID -> target code
rule ID -> Judge test
queue item -> fixing commit
fixing commit -> independent review result
```
