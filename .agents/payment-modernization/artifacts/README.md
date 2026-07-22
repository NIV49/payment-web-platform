# Payment Modernization Artifacts

Place machine-verifiable v2 artifact bundles in this directory as JSON files.
The repository checker recursively validates every `*.json` file here and
rejects symbolic links. This `README.md` and regular `*.json` files are the only
tracked entries allowed under the canonical artifact root.

Every bundle declares `lifecycleStatus` as either `draft` or `closed`. Explicit
positional preflight accepts drafts. The canonical repository gate accepts only
closed bundles, and each closed bundle must carry exactly two independent,
trusted, valid signed `PASS` reviews bound to the same evaluated snapshot.

Approval uses a detached two-commit flow to avoid a Git self-reference:

1. Commit B contains the immutable Rule payload with `status: approved`; the
   repository gate still fails because approval is pending.
2. Two trusted reviewers sign Rule-approval results that bind commit B, its
   evaluated manifests, and the exact Rule subject.
3. Descendant commit C records the signed bundle and approval envelope here.
   The gate accepts C only when the current registered payload still equals the
   payload reviewed at B and B is an ancestor of C.

Never put a bundle that names commit B inside commit B itself.

The authoritative repository gate must also receive an immutable trust root via
`--trusted-policy-commit <full-sha>`. Reviewer keys, repository identity, and
canonical location are taken from that anchored policy, not from the pull
request. A repository introducing this policy for the first time may anchor the
pre-policy parent only while `trustedReviewers` remains empty; reviewer bootstrap
or key rotation requires a separately trusted policy commit. The checker also
replays policy and artifact history from the anchor so an approved Rule cannot
be silently downgraded, removed, or made unapproved by deleting its bundle.

Bundles with non-empty `sourceSnapshots` additionally require a trusted runner
that can map the legacy workspace and resolve every declared source commit and
evidence path. Generic cloud CI intentionally fails closed when those source
repositories are unavailable. Use `--trusted-legacy-workspace` only for a
pre-authorized workspace whose repositories are direct, owned child clones;
never map it to an untrusted checkout supplied by a bundle.
