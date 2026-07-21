# Artifact Contracts

## Capability Slice

```yaml
sliceId: stable-id
path: reimagine | transform
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
  - source: path-or-decision
    location: line-or-section
confidence: high | medium
judgeTests: []
```

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
legacy evidence -> rule ID
decision/ADR -> rule ID
rule ID -> capability slice
rule ID -> target code
rule ID -> Judge test
queue item -> fixing commit
fixing commit -> independent review result
```
