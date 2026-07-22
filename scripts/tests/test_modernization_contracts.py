from __future__ import annotations

import importlib.util
import base64
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


REPOSITORY = Path(__file__).resolve().parents[2]
DEFAULT_TRUST_ANCHOR = object()


def load_module(name: str, relative_path: str):
    path = REPOSITORY / relative_path
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


artifacts = load_module(
    "check_modernization_artifacts", "scripts/check_modernization_artifacts.py"
)
evidence = load_module(
    "check_modernization_evidence", "scripts/check_modernization_evidence.py"
)


def git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def commit_all(repository: Path, message: str) -> str:
    git(repository, "add", "-A")
    git(repository, "commit", "-m", message)
    return git(repository, "rev-parse", "HEAD")


class ImmutableEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.repository = self.root / "source"
        self.repository.mkdir()
        git(self.repository, "init", "-q")
        git(self.repository, "config", "user.email", "test@example.invalid")
        git(self.repository, "config", "user.name", "Contract Test")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_rejects_tracked_symlink_before_reading_outside_sentinel(self) -> None:
        sentinel = self.root / "outside-sentinel.txt"
        sentinel.write_text("HOST_ONLY_SENTINEL", encoding="utf-8")
        (self.repository / "evidence-link").symlink_to(sentinel)
        commit = commit_all(self.repository, "track symlink")

        with self.assertRaisesRegex(
            evidence.EvidenceError, "120000|symbolic link"
        ) as error:
            evidence.read_git_evidence(self.repository, commit, "evidence-link")

        self.assertNotIn("HOST_ONLY_SENTINEL", str(error.exception))

    def test_rejects_gitlink_before_treating_it_as_evidence(self) -> None:
        (self.repository / "seed.txt").write_text("seed", encoding="utf-8")
        seed_commit = commit_all(self.repository, "seed")
        git(
            self.repository,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{seed_commit},external-module",
        )
        git(self.repository, "commit", "-m", "track gitlink")
        commit = git(self.repository, "rev-parse", "HEAD")

        with self.assertRaisesRegex(evidence.EvidenceError, "160000|gitlink"):
            evidence.read_git_evidence(self.repository, commit, "external-module")

    def test_rejects_materialized_path_that_escapes_via_symlink(self) -> None:
        (self.repository / "rules").mkdir()
        (self.repository / "rules/policy.md").write_text("approved", encoding="utf-8")
        commit = commit_all(self.repository, "add policy")

        materialized = self.root / "materialized"
        materialized.mkdir()
        sentinel = self.root / "outside-policy.md"
        sentinel.write_text("HOST_ONLY_SENTINEL", encoding="utf-8")
        (materialized / "rules").mkdir()
        (materialized / "rules/policy.md").symlink_to(sentinel)

        with self.assertRaisesRegex(
            evidence.EvidenceError, "symbolic link|materialization root"
        ) as error:
            evidence.validate_materialized_evidence(
                self.repository, commit, materialized, "rules/policy.md"
            )

        self.assertNotIn("HOST_ONLY_SENTINEL", str(error.exception))

    def test_target_rules_are_read_from_target_base_not_live_checkout(self) -> None:
        (self.repository / "docs").mkdir()
        rules = self.repository / "docs/rules.md"
        rules.write_text("RULES_AT_BASE_A", encoding="utf-8")
        base_a = commit_all(self.repository, "base A")
        rules.write_text("MUTABLE_LIVE_CHECKOUT_B", encoding="utf-8")
        commit_all(self.repository, "live checkout B")

        resolved = evidence.read_target_rules(
            self.repository, base_a, ["docs/rules.md"]
        )

        self.assertEqual(resolved, {"docs/rules.md": b"RULES_AT_BASE_A"})

    def test_declared_commit_cannot_be_overridden_by_a_live_replace_ref(self) -> None:
        evidence_file = self.repository / "evidence.txt"
        evidence_file.write_text("SAFE_AT_DECLARED_COMMIT", encoding="utf-8")
        declared_commit = commit_all(self.repository, "declared evidence")
        evidence_file.write_text("MUTABLE_REPLACEMENT", encoding="utf-8")
        replacement_commit = commit_all(self.repository, "replacement evidence")
        git(self.repository, "replace", declared_commit, replacement_commit)

        resolved = evidence.read_git_evidence(
            self.repository, declared_commit, "evidence.txt"
        )

        self.assertEqual(resolved, b"SAFE_AT_DECLARED_COMMIT")

    def test_commit_parent_validation_rejects_grafted_history(self) -> None:
        (self.repository / "evidence.txt").write_text("base", encoding="utf-8")
        commit_all(self.repository, "base evidence")
        (self.repository / "evidence.txt").write_text("child", encoding="utf-8")
        child = commit_all(self.repository, "child evidence")
        grafts = self.repository / ".git/info/grafts"
        grafts.parent.mkdir(parents=True, exist_ok=True)
        grafts.write_text(f"{child}\n", encoding="ascii")

        with self.assertRaisesRegex(evidence.EvidenceError, "graft"):
            evidence.commit_parents(self.repository, child)

    def test_commit_parent_validation_rejects_alternate_graft_environment(
        self,
    ) -> None:
        (self.repository / "evidence.txt").write_text("base", encoding="utf-8")
        base = commit_all(self.repository, "base evidence")
        (self.repository / "evidence.txt").write_text("child", encoding="utf-8")
        child = commit_all(self.repository, "child evidence")
        alternate_grafts = self.root / "alternate-grafts\n"
        alternate_grafts.write_text(f"{child} {base}\n", encoding="ascii")

        with mock.patch.dict(
            os.environ,
            {"GIT_GRAFT_FILE": str(alternate_grafts)},
            clear=False,
        ):
            with self.assertRaisesRegex(evidence.EvidenceError, "environment|graft"):
                evidence.commit_parents(self.repository, child)

    def test_commit_parent_validation_rejects_shallow_history(self) -> None:
        (self.repository / "evidence.txt").write_text("base", encoding="utf-8")
        commit_all(self.repository, "base evidence")
        (self.repository / "evidence.txt").write_text("child", encoding="utf-8")
        commit_all(self.repository, "child evidence")
        shallow_repository = self.root / "shallow"
        subprocess.run(
            [
                "git",
                "clone",
                "--quiet",
                "--depth=1",
                self.repository.resolve().as_uri(),
                str(shallow_repository),
            ],
            check=True,
            capture_output=True,
        )
        shallow_head = git(shallow_repository, "rev-parse", "HEAD")

        with self.assertRaisesRegex(evidence.EvidenceError, "shallow"):
            evidence.commit_parents(shallow_repository, shallow_head)

    def test_path_history_includes_treesame_side_branch_commits(self) -> None:
        path = self.repository / "governance.json"
        path.write_text('{"state":"base"}\n', encoding="utf-8")
        base = commit_all(self.repository, "base")
        git(self.repository, "checkout", "-q", "-b", "side")
        path.write_text('{"state":"side"}\n', encoding="utf-8")
        side = commit_all(self.repository, "side governance state")
        git(self.repository, "checkout", "-q", "-b", "mainline", base)
        git(self.repository, "merge", "--no-ff", "-s", "ours", "side", "-m", "merge side history")
        merged = git(self.repository, "rev-parse", "HEAD")

        history = evidence.commits_touching_paths(
            self.repository, base, merged, ["governance.json"]
        )

        self.assertIn(side, history)

    def test_changed_paths_cannot_hide_gitlink_oid_change_with_submodule_ignore(
        self,
    ) -> None:
        (self.repository / "README.md").write_text("anchor", encoding="utf-8")
        anchor = commit_all(self.repository, "anchor")
        (self.repository / ".gitmodules").write_text(
            '[submodule "provider"]\n'
            "  path = modules/provider\n"
            "  url = https://example.invalid/provider.git\n"
            "  ignore = all\n",
            encoding="utf-8",
        )
        git(
            self.repository,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{anchor},modules/provider",
        )
        git(self.repository, "add", ".gitmodules")
        git(self.repository, "commit", "-m", "register ignored gitlink")
        base = git(self.repository, "rev-parse", "HEAD")
        git(
            self.repository,
            "update-index",
            "--cacheinfo",
            f"160000,{base},modules/provider",
        )
        git(self.repository, "commit", "-m", "change ignored gitlink oid")
        target = git(self.repository, "rev-parse", "HEAD")

        self.assertIn(
            "modules/provider",
            evidence.changed_paths_for_commit(self.repository, target),
        )

    def test_rejects_git_blob_and_materialized_file_over_size_limit(self) -> None:
        oversized = b"x" * (evidence.DEFAULT_MAX_EVIDENCE_BYTES + 1)
        (self.repository / "oversized.bin").write_bytes(oversized)
        commit = commit_all(self.repository, "oversized evidence")

        with self.assertRaisesRegex(evidence.EvidenceError, "size limit"):
            evidence.read_git_evidence(self.repository, commit, "oversized.bin")

        materialized = self.root / "oversized-materialized"
        materialized.mkdir()
        (materialized / "oversized.bin").write_bytes(oversized)
        with self.assertRaisesRegex(evidence.EvidenceError, "size limit"):
            evidence.validate_materialized_evidence(
                self.repository, commit, materialized, "oversized.bin"
            )

    def test_materialization_root_identity_change_fails_closed(self) -> None:
        (self.repository / "policy.md").write_text("safe", encoding="utf-8")
        commit = commit_all(self.repository, "root identity policy")
        materialized = self.root / "root-identity"
        materialized.mkdir()
        (materialized / "policy.md").write_text("safe", encoding="utf-8")
        original = self.root / "root-identity-original"

        real_open = evidence.os.open
        swapped = False

        def swap_root_before_open(path, flags, *args, **kwargs):
            nonlocal swapped
            if Path(path) == materialized and not swapped:
                swapped = True
                materialized.rename(original)
                materialized.mkdir()
                (materialized / "policy.md").write_text("safe", encoding="utf-8")
            return real_open(path, flags, *args, **kwargs)

        with mock.patch.object(evidence.os, "open", side_effect=swap_root_before_open):
            with self.assertRaisesRegex(evidence.EvidenceError, "root.*changed"):
                evidence.validate_materialized_evidence(
                    self.repository, commit, materialized, "policy.md"
                )

    def test_artifact_cli_recomputes_rule_and_judge_digests_from_target_base(
        self,
    ) -> None:
        (self.repository / ".agents").mkdir()
        (self.repository / "rules").mkdir()
        (self.repository / "judge").mkdir()
        policy = {
            "schemaVersion": 2,
            "targetRepositoryId": "synthetic-target",
            "canonicalRepositoryPath": str(self.repository.resolve()),
            "rulebookPaths": [
                ".agents/payment-modernization-policy.json",
                "AGENTS.md",
                "rules/IAM-001.yaml",
            ],
            "ruleCardPaths": [],
            "judgePaths": ["judge/IAM-001.test"],
            "trustedReviewers": [],
        }
        self.repository.joinpath(
            ".agents/payment-modernization-policy.json"
        ).write_text(json.dumps(policy, sort_keys=True), encoding="utf-8")
        self.repository.joinpath("AGENTS.md").write_text(
            "# Synthetic repository policy\n", encoding="utf-8"
        )
        self.repository.joinpath("rules/IAM-001.yaml").write_text(
            "statement: deny\n", encoding="utf-8"
        )
        self.repository.joinpath("judge/IAM-001.test").write_text(
            "assert denied\n", encoding="utf-8"
        )
        base = commit_all(self.repository, "add immutable policy")
        rulebook_digest = artifacts.content_bundle_digest(
            {
                ".agents/payment-modernization-policy.json": json.dumps(
                    policy, sort_keys=True
                ).encode("utf-8"),
                "AGENTS.md": b"# Synthetic repository policy\n",
                "rules/IAM-001.yaml": b"statement: deny\n",
            }
        )
        judge_digest = artifacts.content_bundle_digest(
            {"judge/IAM-001.test": b"assert denied\n"}
        )
        rulebook_manifest = {
            "label": "display-only",
            "paths": list(policy["rulebookPaths"]),
            "rulebookDigest": rulebook_digest,
        }
        judge_manifest = {
            "label": "display-only",
            "paths": ["judge/IAM-001.test"],
            "judgeDigest": judge_digest,
        }
        task_key = artifacts.task_identity_key(
            turn_id="turn-1",
            slice_id="iam-login",
            target_base_sha=base,
            source_snapshots=[],
            rulebook_digest=rulebook_digest,
            judge_digest=judge_digest,
            non_git_evidence=[],
            target_repository_id="synthetic-target",
            rulebook_manifest=rulebook_manifest,
            judge_manifest=judge_manifest,
        )
        capability = {
            "turnId": "turn-1",
            "sliceId": "iam-login",
            "path": "reimagine",
            "sourceSnapshots": [],
            "targetRepositoryPath": str(self.repository.resolve()),
            "targetRepositoryId": "synthetic-target",
            "targetBaseSha": base,
            "rulebookManifest": rulebook_manifest,
            "judgeManifest": judge_manifest,
            "taskIdentityKey": task_key,
            "nonGitEvidence": [],
            "ruleIds": [],
            "actors": [],
            "inputs": [],
            "outputs": [],
            "dependencies": [],
            "ownedPaths": [],
            "forbiddenChanges": [],
            "entryCriteria": [],
            "exitCriteria": [],
            "judgeCommands": [],
        }
        evaluated_key = artifacts.evaluated_version_key(
            task_key, base, rulebook_digest, judge_digest
        )
        evaluated = {
            "targetCommitSha": base,
            "rulebookManifest": dict(capability["rulebookManifest"]),
            "judgeManifest": dict(capability["judgeManifest"]),
            "evaluatedVersionKey": evaluated_key,
        }
        bundle_path = self.root / "bundle.json"
        bundle_path.write_text(
            json.dumps(
                {
                    "lifecycleStatus": "draft",
                    "capabilitySlice": capability,
                    "evaluatedSnapshot": evaluated,
                    "ruleCards": [],
                    "reviewResults": [],
                    "queueItems": [],
                }
            ),
            encoding="utf-8",
        )
        self.repository.joinpath("rules/IAM-001.yaml").write_text(
            "statement: mutable live allow\n", encoding="utf-8"
        )
        commit_all(self.repository, "advance live checkout")

        valid = subprocess.run(
            [
                sys.executable,
                str(REPOSITORY / "scripts/check_modernization_artifacts.py"),
                str(bundle_path),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(valid.returncode, 0, valid.stderr)

        forged_digest = artifacts.content_bundle_digest(
            {"rules/IAM-001.yaml": b"statement: mutable live allow\n"}
        )
        capability["rulebookManifest"]["rulebookDigest"] = forged_digest
        capability["taskIdentityKey"] = artifacts.task_identity_key(
            turn_id="turn-1",
            slice_id="iam-login",
            target_base_sha=base,
            source_snapshots=[],
            rulebook_digest=forged_digest,
            judge_digest=judge_digest,
            non_git_evidence=[],
            target_repository_id="synthetic-target",
            rulebook_manifest=capability["rulebookManifest"],
            judge_manifest=capability["judgeManifest"],
        )
        bundle_path.write_text(
            json.dumps(
                {
                    "lifecycleStatus": "draft",
                    "capabilitySlice": capability,
                    "evaluatedSnapshot": evaluated,
                    "ruleCards": [],
                    "reviewResults": [],
                    "queueItems": [],
                }
            ),
            encoding="utf-8",
        )

        invalid = subprocess.run(
            [
                sys.executable,
                str(REPOSITORY / "scripts/check_modernization_artifacts.py"),
                str(bundle_path),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(invalid.returncode, 1)
        self.assertIn("rulebookDigest", invalid.stderr)


class ModernizationArtifactContractTest(unittest.TestCase):
    def make_review(
        self,
        *,
        review_id: str,
        task_identity_key: str,
        evaluated_version_key: str,
        reviewer_id: str,
        reviewer_role: str,
        commit: str,
        rulebook_digest: str,
        judge_digest: str,
    ) -> dict[str, object]:
        return {
            "reviewResultId": review_id,
            "taskIdentityKey": task_identity_key,
            "evaluatedVersionKey": evaluated_version_key,
            "reviewerId": reviewer_id,
            "reviewerRole": reviewer_role,
            "reviewIdempotencyKey": artifacts.review_idempotency_key(
                evaluated_version_key, reviewer_id, reviewer_role
            ),
            "targetCommitSha": commit,
            "rulebookDigest": rulebook_digest,
            "judgeDigest": judge_digest,
            "startCommitSha": commit,
            "endCommitSha": commit,
            "snapshotValid": True,
            "verdict": "PASS",
            "findings": [],
        }

    def test_separates_preimplementation_task_identity_from_evaluated_version(
        self,
    ) -> None:
        base_a = "a" * 40
        output_b = "b" * 40
        rulebook_digest = artifacts.content_bundle_digest(
            {"rules/IAM-001.yaml": b"statement: deny by default\n"}
        )
        judge_digest = artifacts.content_bundle_digest(
            {"judge/IAM-001.test": b"assert denied\n"}
        )
        task_key = artifacts.task_identity_key(
            turn_id="turn-1",
            slice_id="iam-login",
            target_base_sha=base_a,
            source_snapshots=[],
            rulebook_digest=rulebook_digest,
            judge_digest=judge_digest,
        )
        evaluated_key = artifacts.evaluated_version_key(
            task_key, output_b, rulebook_digest, judge_digest
        )
        review_b = self.make_review(
            review_id="review-b",
            task_identity_key=task_key,
            evaluated_version_key=evaluated_key,
            reviewer_id="reviewer-b",
            reviewer_role="business-security",
            commit=output_b,
            rulebook_digest=rulebook_digest,
            judge_digest=judge_digest,
        )
        review_c = self.make_review(
            review_id="review-c",
            task_identity_key=task_key,
            evaluated_version_key=evaluated_key,
            reviewer_id="reviewer-c",
            reviewer_role="implementation-adversary",
            commit=output_b,
            rulebook_digest=rulebook_digest,
            judge_digest=judge_digest,
        )

        self.assertNotEqual(task_key, evaluated_key)
        self.assertNotEqual(
            review_b["reviewIdempotencyKey"], review_c["reviewIdempotencyKey"]
        )
        self.assertTrue(artifacts.validate_review_result(review_b))
        self.assertTrue(artifacts.validate_review_result(review_c))

    def test_approved_rule_requires_two_independent_pass_reviews(self) -> None:
        commit = "b" * 40
        rulebook_digest = artifacts.content_bundle_digest({"rule": b"candidate"})
        judge_digest = artifacts.content_bundle_digest({"judge": b"test"})
        task_key = artifacts.task_identity_key(
            turn_id="turn-1",
            slice_id="permissions",
            target_base_sha="a" * 40,
            source_snapshots=[],
            rulebook_digest=rulebook_digest,
            judge_digest=judge_digest,
        )
        version_key = artifacts.evaluated_version_key(
            task_key, commit, rulebook_digest, judge_digest
        )
        rule = {
            "ruleId": "IAM-001",
            "status": "approved",
            "statement": "Deny unscoped access.",
            "approvalCommit": commit,
            "approvedBy": ["reviewer-b", "reviewer-c"],
            "approvalReviewRefs": ["review-b", "review-c"],
        }

        self.assertTrue(artifacts.validate_rule_card(rule, {}))

        reviews = {
            "review-b": self.make_review(
                review_id="review-b",
                task_identity_key=task_key,
                evaluated_version_key=version_key,
                reviewer_id="reviewer-b",
                reviewer_role="business-security",
                commit=commit,
                rulebook_digest=rulebook_digest,
                judge_digest=judge_digest,
            ),
            "review-c": self.make_review(
                review_id="review-c",
                task_identity_key=task_key,
                evaluated_version_key=version_key,
                reviewer_id="reviewer-c",
                reviewer_role="implementation-adversary",
                commit=commit,
                rulebook_digest=rulebook_digest,
                judge_digest=judge_digest,
            ),
        }

        self.assertTrue(artifacts.validate_rule_card(rule, reviews))

    def test_rulebook_and_judge_content_not_mutable_labels_bind_version_key(
        self,
    ) -> None:
        label = "2026.07"
        digest_one = artifacts.content_bundle_digest(
            {"rulebook.yaml": b"label: 2026.07\nrule: deny\n"}
        )
        digest_two = artifacts.content_bundle_digest(
            {"rulebook.yaml": b"label: 2026.07\nrule: allow\n"}
        )
        judge_digest = artifacts.content_bundle_digest({"judge.py": b"assert deny"})
        task_key = "sha256:" + "1" * 64

        self.assertEqual(label, label)
        self.assertNotEqual(digest_one, digest_two)
        self.assertNotEqual(
            artifacts.evaluated_version_key(
                task_key, "b" * 40, digest_one, judge_digest
            ),
            artifacts.evaluated_version_key(
                task_key, "b" * 40, digest_two, judge_digest
            ),
        )

        common = {
            "turn_id": "turn-1",
            "slice_id": "iam-login",
            "target_base_sha": "a" * 40,
            "source_snapshots": [],
            "rulebook_digest": digest_one,
            "judge_digest": judge_digest,
        }
        label_one_key = artifacts.task_identity_key(
            **common,
            rulebook_manifest={
                "label": "display-one",
                "paths": ["rulebook.yaml"],
                "rulebookDigest": digest_one,
            },
            judge_manifest={
                "label": "judge-one",
                "paths": ["judge.py"],
                "judgeDigest": judge_digest,
            },
        )
        label_two_key = artifacts.task_identity_key(
            **common,
            rulebook_manifest={
                "label": "display-two",
                "paths": ["rulebook.yaml"],
                "rulebookDigest": digest_one,
            },
            judge_manifest={
                "label": "judge-two",
                "paths": ["judge.py"],
                "judgeDigest": judge_digest,
            },
        )
        self.assertEqual(label_one_key, label_two_key)

    def test_queue_items_support_rule_judge_build_test_and_review_failures(
        self,
    ) -> None:
        common = {
            "fingerprint": "stable-root-cause",
            "sliceId": "iam-login",
            "evaluatedVersionKey": "sha256:" + "1" * 64,
            "severity": "BLOCKER",
            "trigger": "reproduce",
            "controlFlow": ["one", "two"],
            "evidence": ["synthetic evidence"],
            "impact": "denied incorrectly",
            "verification": "python3 -m unittest",
            "status": "open",
            "failedReviewRounds": 0,
        }
        sources = [
            {"type": "rule", "ruleId": "IAM-001"},
            {"type": "judge", "checkId": "judge:iam:deny"},
            {"type": "build", "checkId": "maven:verify"},
            {"type": "test", "checkId": "test:iam-login"},
            {"type": "review", "checkId": "review:B-001"},
        ]

        for source in sources:
            with self.subTest(source=source):
                item = {**common, "failureSource": source}
                self.assertEqual(artifacts.validate_queue_item(item), [])

        invalid = {**common, "failureSource": {"type": "build"}}
        self.assertTrue(artifacts.validate_queue_item(invalid))

    def test_queue_history_is_append_only_and_requires_new_signed_version(self) -> None:
        root = {
            "fingerprint": "stable-root-cause",
            "sliceId": "iam-login",
            "evaluatedVersionKey": "sha256:" + "1" * 64,
            "severity": "SHOULD_FIX",
            "failureSource": {"type": "review", "checkId": "review:S-1"},
            "trigger": "reproduce",
            "controlFlow": ["one", "two"],
            "evidence": ["synthetic evidence"],
            "impact": "observable",
            "verification": "python -I -m unittest",
            "status": "open",
            "failedReviewRounds": 0,
        }
        previous = {"stable-root-cause": root}

        deletion = artifacts.validate_queue_history_step(previous, {})
        self.assertTrue(any("deleted" in error for error in deletion), deletion)

        forged_close = dict(root, status="closed")
        close_errors = artifacts.validate_queue_history_step(
            previous, {"stable-root-cause": forged_close}
        )
        self.assertTrue(any("invalid status transition" in error for error in close_errors))
        self.assertTrue(any("new evaluatedVersionKey" in error for error in close_errors))

        valid = dict(
            root,
            status="implementing",
            evaluatedVersionKey="sha256:" + "2" * 64,
        )
        self.assertEqual(
            [], artifacts.validate_queue_history_step(previous, {"stable-root-cause": valid})
        )

        rewritten = dict(valid, impact="author rewrote the root cause")
        rewrite_errors = artifacts.validate_queue_history_step(
            previous, {"stable-root-cause": rewritten}
        )
        self.assertTrue(any("immutable root" in error for error in rewrite_errors))

    def test_third_unsuccessful_review_requires_human_decision(self) -> None:
        fingerprint = "three-round-review"
        root = {
            "fingerprint": fingerprint,
            "sliceId": "iam-login",
            "evaluatedVersionKey": "sha256:" + "1" * 64,
            "severity": "SHOULD_FIX",
            "failureSource": {"type": "review", "checkId": "review:S-3"},
            "trigger": "reproduce",
            "controlFlow": ["one", "two"],
            "evidence": ["synthetic evidence"],
            "impact": "observable",
            "verification": "python -I -m unittest",
            "status": "open",
            "failedReviewRounds": 0,
        }

        states = [root]
        for status, failed_rounds, digit in (
            ("implementing", 0, "2"),
            ("reviewing", 0, "3"),
            ("implementing", 1, "4"),
            ("reviewing", 1, "5"),
            ("implementing", 2, "6"),
            ("reviewing", 2, "7"),
        ):
            current = dict(
                states[-1],
                status=status,
                failedReviewRounds=failed_rounds,
                evaluatedVersionKey="sha256:" + digit * 64,
            )
            self.assertEqual(
                [],
                artifacts.validate_queue_history_step(
                    {fingerprint: states[-1]}, {fingerprint: current}
                ),
            )
            states.append(current)

        forbidden_retry = dict(
            states[-1],
            status="implementing",
            failedReviewRounds=3,
            evaluatedVersionKey="sha256:" + "8" * 64,
        )
        errors = artifacts.validate_queue_history_step(
            {fingerprint: states[-1]}, {fingerprint: forbidden_retry}
        )
        self.assertTrue(any("third unsuccessful review" in error for error in errors), errors)

        human_decision = dict(forbidden_retry, status="human-decision")
        self.assertEqual(
            [],
            artifacts.validate_queue_history_step(
                {fingerprint: states[-1]}, {fingerprint: human_decision}
            ),
        )

    def test_divergent_queue_merge_requires_a_new_signed_reconciliation(self) -> None:
        fingerprint = "merge-queue-state"
        root = {
            "fingerprint": fingerprint,
            "sliceId": "iam-login",
            "evaluatedVersionKey": "sha256:" + "1" * 64,
            "severity": "SHOULD_FIX",
            "failureSource": {"type": "review", "checkId": "review:S-merge"},
            "trigger": "reproduce",
            "controlFlow": ["one", "two"],
            "evidence": ["synthetic evidence"],
            "impact": "observable",
            "verification": "python -I -m unittest",
            "status": "implementing",
            "failedReviewRounds": 0,
        }
        parent_one = dict(root, evaluatedVersionKey="sha256:" + "2" * 64)
        parent_two = dict(
            root,
            status="human-decision",
            evaluatedVersionKey="sha256:" + "3" * 64,
        )

        conflicts = artifacts.queue_parent_conflicts(
            [{fingerprint: parent_one}, {fingerprint: parent_two}]
        )

        self.assertEqual({fingerprint}, set(conflicts))
        self.assertTrue(
            artifacts.validate_queue_reconciliation(
                fingerprint,
                conflicts[fingerprint],
                parent_two,
                parent_two,
            )
        )

        reconciled = dict(
            parent_two,
            evaluatedVersionKey="sha256:" + "4" * 64,
        )
        self.assertEqual(
            [],
            artifacts.validate_queue_reconciliation(
                fingerprint,
                conflicts[fingerprint],
                reconciled,
                parent_two,
            ),
        )

    def test_queue_merge_still_validates_every_non_conflicting_fingerprint(self) -> None:
        common = {
            "fingerprint": "common-queue-state",
            "sliceId": "iam-login",
            "evaluatedVersionKey": "sha256:" + "1" * 64,
            "severity": "SHOULD_FIX",
            "failureSource": {"type": "review", "checkId": "review:S-common"},
            "trigger": "reproduce",
            "controlFlow": ["one", "two"],
            "evidence": ["synthetic evidence"],
            "impact": "observable",
            "verification": "python -I -m unittest",
            "status": "open",
            "failedReviewRounds": 0,
        }
        divergent_one = dict(
            common,
            fingerprint="divergent-queue-state",
            status="implementing",
            evaluatedVersionKey="sha256:" + "2" * 64,
        )
        divergent_two = dict(
            divergent_one,
            status="human-decision",
            evaluatedVersionKey="sha256:" + "3" * 64,
        )
        parent_states = [
            {common["fingerprint"]: common, divergent_one["fingerprint"]: divergent_one},
            {common["fingerprint"]: common, divergent_two["fingerprint"]: divergent_two},
        ]
        current_state = {divergent_two["fingerprint"]: divergent_two}

        conflicts, errors = artifacts.validate_queue_merge_step(
            parent_states, current_state
        )

        self.assertEqual({"divergent-queue-state"}, set(conflicts))
        self.assertTrue(any("common-queue-state was deleted" in error for error in errors), errors)

    def test_skill_contract_documents_the_machine_enforced_model(self) -> None:
        skill = (
            REPOSITORY / ".agents/skills/payment-modernization/SKILL.md"
        ).read_text(encoding="utf-8")
        contracts = (
            REPOSITORY
            / ".agents/skills/payment-modernization/references/artifact-contracts.md"
        ).read_text(encoding="utf-8")
        judge_gates = (
            REPOSITORY
            / ".agents/skills/payment-modernization/references/judge-gates.md"
        ).read_text(encoding="utf-8")

        for phrase in (
            "taskIdentityKey",
            "evaluatedVersionKey",
            "rulebookDigest",
            "judgeDigest",
            "reviewIdempotencyKey",
        ):
            self.assertIn(phrase, contracts)
        self.assertIn("targetBaseSha", skill)
        self.assertIn("mode `120000`", skill)
        self.assertIn("mode `160000`", skill)
        self.assertIn("two independent", contracts)
        self.assertIn("reviewerId", judge_gates)
        self.assertIn("reviewerRole", judge_gates)
        self.assertNotIn("target commit and Rulebook version", judge_gates)
        self.assertIn("target commit, `evaluatedVersionKey`", judge_gates)

    def test_judge_charter_uses_split_identity_and_typed_failure_contracts(
        self,
    ) -> None:
        charter = (REPOSITORY / "docs/judge-charter.md").read_text(encoding="utf-8")

        self.assertNotIn("turnId + commitSha + rulebookVersion", charter)
        self.assertIn("taskIdentityKey", charter)
        self.assertIn("evaluatedVersionKey", charter)
        self.assertIn("reviewIdempotencyKey", charter)
        self.assertIn("ruleId` 或带类型的 `checkId", charter)
        self.assertIn("两名独立审查者", charter)


class ModernizationContractV2RedTest(unittest.TestCase):
    """Regression probes for the hardened, content-addressed v2 contract."""

    def test_source_snapshot_schema_is_exact_and_non_git_is_fail_closed(self) -> None:
        invalid_snapshots = [
            {
                "sourceSnapshotId": "legacy",
                "repositoryPath": "/tmp/repository",
                "sourceCommitSha": "A" * 40,
                "evidencePaths": ["policy.md"],
                "readMethod": "raw-git-show",
                "unexpected": True,
            },
            {
                "sourceSnapshotId": "legacy",
                "repositoryPath": "/tmp/repository",
                "sourceCommitSha": "a" * 40,
                "evidencePaths": ["policy.md"],
                "readMethod": "validated-git-object",
            },
        ]
        self.assertTrue(artifacts.validate_source_snapshots(invalid_snapshots))

        non_git = [
            {
                "absolutePath": "/tmp/preauthorized.bin",
                "sha256": "1" * 64,
                "purpose": "human-approved fixture",
            }
        ]
        self.assertTrue(artifacts.validate_non_git_evidence(non_git, resolver=None))
        approved_bytes = b"externally preauthorized"
        non_git[0]["sha256"] = hashlib.sha256(approved_bytes).hexdigest()
        self.assertEqual(
            artifacts.validate_non_git_evidence(
                non_git, resolver=lambda _entry: approved_bytes
            ),
            [],
        )

    def test_source_repository_must_be_direct_owned_child_with_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            workspace = Path(raw_root) / "legacy"
            workspace.mkdir()
            repository = workspace / "service-a"
            repository.mkdir()
            git(repository, "init", "-q")
            git(repository, "config", "user.email", "test@example.invalid")
            git(repository, "config", "user.name", "Contract Test")
            (repository / "intent.md").write_text("intent", encoding="utf-8")
            commit = commit_all(repository, "intent")
            snapshot = {
                "sourceSnapshotId": "service-a",
                "repositoryPath": str(repository.resolve()),
                "sourceCommitSha": commit,
                "evidencePaths": ["intent.md"],
                "readMethod": "validated-git-object",
            }

            self.assertEqual(
                artifacts.validate_source_snapshots(
                    [snapshot], trusted_legacy_workspace=workspace
                ),
                [],
            )
            no_evidence = {**snapshot, "evidencePaths": []}
            self.assertTrue(
                artifacts.validate_source_snapshots(
                    [no_evidence], trusted_legacy_workspace=workspace
                )
            )
            nested = workspace / "nested" / "service-a"
            nested.parent.mkdir()
            nested.symlink_to(repository, target_is_directory=True)
            outside = {**snapshot, "repositoryPath": str(nested)}
            self.assertTrue(
                artifacts.validate_source_snapshots(
                    [outside], trusted_legacy_workspace=workspace
                )
            )

    def test_review_signature_payload_is_canonical_and_verifiable(self) -> None:
        private_key = Ed25519PrivateKey.generate()
        public_key = base64.b64encode(
            private_key.public_key().public_bytes(
                encoding=serialization.Encoding.Raw,
                format=serialization.PublicFormat.Raw,
            )
        ).decode("ascii")
        review = {
            "reviewResultId": "review-b",
            "reviewerId": "reviewer-b",
            "reviewerRole": "business-security",
            "keyId": "key-b",
            "signatureAlgorithm": "Ed25519",
            "verdict": "PASS",
        }
        review["signature"] = base64.b64encode(
            private_key.sign(artifacts.canonical_review_payload(review))
        ).decode("ascii")
        reviewer = {
            "reviewerId": "reviewer-b",
            "reviewerRole": "business-security",
            "keyId": "key-b",
            "signatureAlgorithm": "Ed25519",
            "publicKey": public_key,
        }

        self.assertEqual(artifacts.verify_review_signature(review, reviewer), [])
        forged = {**review, "verdict": "FAIL"}
        self.assertTrue(artifacts.verify_review_signature(forged, reviewer))

    def test_rule_evidence_tagged_unions_resolve_or_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            source = root / "legacy-service"
            source.mkdir()
            git(source, "init", "-q")
            git(source, "config", "user.email", "test@example.invalid")
            git(source, "config", "user.name", "Contract Test")
            source.joinpath("intent.md").write_text("intent", encoding="utf-8")
            source_commit = commit_all(source, "source intent")
            target = root / "target"
            target.mkdir()
            git(target, "init", "-q")
            git(target, "config", "user.email", "test@example.invalid")
            git(target, "config", "user.name", "Contract Test")
            target.joinpath("AGENTS.md").write_text("policy", encoding="utf-8")
            target_commit = commit_all(target, "target policy")
            non_git_digest = hashlib.sha256(b"approved external").hexdigest()
            capability = {
                "targetBaseSha": target_commit,
                "sourceSnapshots": [
                    {
                        "sourceSnapshotId": "legacy-service",
                        "repositoryPath": str(source.resolve()),
                        "sourceCommitSha": source_commit,
                        "evidencePaths": ["intent.md"],
                        "readMethod": "validated-git-object",
                    }
                ],
                "nonGitEvidence": [
                    {
                        "absolutePath": "/approved/external.bin",
                        "sha256": non_git_digest,
                        "purpose": "synthetic",
                    }
                ],
            }
            policy = {"rulebookPaths": ["AGENTS.md"]}
            valid = [
                {
                    "kind": "git",
                    "sourceSnapshotId": "legacy-service",
                    "evidencePath": "intent.md",
                    "location": "section 1",
                },
                {
                    "kind": "non-git",
                    "source": "/approved/external.bin",
                    "sha256": non_git_digest,
                    "location": "record 1",
                },
                {
                    "kind": "decision",
                    "source": "AGENTS.md",
                    "targetBaseSha": target_commit,
                    "location": "policy",
                },
            ]
            self.assertEqual(
                artifacts.validate_rule_evidence(
                    valid,
                    capability=capability,
                    baseline_policy=policy,
                    target_repository=target,
                ),
                [],
            )
            invalid = [
                {
                    "kind": "git",
                    "sourceSnapshotId": "missing",
                    "evidencePath": "intent.md",
                    "location": "section 1",
                },
                {
                    "kind": "git",
                    "sourceSnapshotId": "legacy-service",
                    "evidencePath": "../../outside",
                    "location": "section 1",
                },
                {
                    "kind": "non-git",
                    "source": "/approved/external.bin",
                    "sha256": "1" * 64,
                    "location": "record 1",
                },
                {
                    "kind": "decision",
                    "source": "AGENTS.md",
                    "targetBaseSha": "f" * 40,
                    "location": "policy",
                },
            ]
            errors = artifacts.validate_rule_evidence(
                invalid,
                capability=capability,
                baseline_policy=policy,
                target_repository=target,
            )
            self.assertTrue(any("does not resolve" in error for error in errors))
            self.assertTrue(any("canonical" in error for error in errors))
            self.assertTrue(any("preauthorized" in error for error in errors))
            self.assertTrue(any("targetBaseSha" in error for error in errors))

    def test_default_artifact_root_is_fixed(self) -> None:
        self.assertEqual(
            artifacts.DEFAULT_ARTIFACT_ROOT.as_posix(),
            ".agents/payment-modernization/artifacts",
        )

    def test_materialized_reader_walks_from_directory_descriptors(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            repository = root / "repository"
            repository.mkdir()
            git(repository, "init", "-q")
            git(repository, "config", "user.email", "test@example.invalid")
            git(repository, "config", "user.name", "Contract Test")
            (repository / "rules").mkdir()
            (repository / "rules/policy.md").write_text("safe", encoding="utf-8")
            commit = commit_all(repository, "policy")

            materialized = root / "materialized"
            (materialized / "rules").mkdir(parents=True)
            (materialized / "rules/policy.md").write_text("safe", encoding="utf-8")
            outside = root / "outside"
            outside.mkdir()
            (outside / "policy.md").write_text("HOST_ONLY_SENTINEL", encoding="utf-8")

            real_open = evidence.os.open
            raced = False
            escaped_absolute_open = False

            def racing_open(path, flags, *args, **kwargs):
                nonlocal raced, escaped_absolute_open
                rendered = os.fspath(path)
                if rendered.endswith("policy.md") and not raced:
                    raced = True
                    original = materialized / "rules-original"
                    (materialized / "rules").rename(original)
                    (materialized / "rules").symlink_to(
                        outside, target_is_directory=True
                    )
                    if kwargs.get("dir_fd") is None:
                        escaped_absolute_open = True
                return real_open(path, flags, *args, **kwargs)

            with mock.patch.object(evidence.os, "open", side_effect=racing_open):
                resolved = evidence.validate_materialized_evidence(
                    repository, commit, materialized, "rules/policy.md"
                )

            self.assertEqual(resolved, b"safe")
            self.assertFalse(escaped_absolute_open)

    def test_policy_file_and_artifact_root_are_present(self) -> None:
        self.assertTrue(
            (REPOSITORY / ".agents/payment-modernization-policy.json").is_file()
        )

    def test_dependency_set_is_fully_pinned(self) -> None:
        requirements = (
            REPOSITORY / "scripts/requirements-documentation.txt"
        ).read_text(encoding="utf-8")
        for requirement in (
            "markdown-it-py==4.2.0",
            "mdurl==0.1.2",
            "PyYAML==6.0.3",
            "cryptography==49.0.0",
            "cffi==2.1.0",
            "pycparser==3.0",
        ):
            self.assertIn(requirement, requirements)
        blocks = [block for block in requirements.split("\n") if "==" in block]
        self.assertEqual(6, len(blocks))
        self.assertGreaterEqual(requirements.count("--hash=sha256:"), 6)

    def test_project_policy_paths_materialize_as_regular_git_blobs(self) -> None:
        policy = json.loads(
            (REPOSITORY / ".agents/payment-modernization-policy.json").read_text(
                encoding="utf-8"
            )
        )
        artifact_readme = ".agents/payment-modernization/artifacts/README.md"
        self.assertIn(artifact_readme, policy["judgePaths"])
        with tempfile.TemporaryDirectory() as raw_root:
            snapshot = Path(raw_root) / "snapshot"
            snapshot.mkdir()
            git(snapshot, "init", "-q")
            git(snapshot, "config", "user.email", "test@example.invalid")
            git(snapshot, "config", "user.name", "Contract Test")
            for relative_path in policy["rulebookPaths"] + policy["judgePaths"]:
                source = REPOSITORY / relative_path
                self.assertTrue(source.is_file(), relative_path)
                self.assertFalse(source.is_symlink(), relative_path)
                destination = snapshot / relative_path
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
            commit = commit_all(snapshot, "materialize proposed policy")
            for relative_path in policy["rulebookPaths"] + policy["judgePaths"]:
                self.assertTrue(
                    evidence.read_git_evidence(snapshot, commit, relative_path)
                )
        self.assertTrue(
            (REPOSITORY / ".agents/payment-modernization/artifacts/README.md").is_file()
        )

    def test_codeowners_covers_policy_judges_all_workflows_and_bootstrap_runbook(self) -> None:
        policy = json.loads(
            (REPOSITORY / ".agents/payment-modernization-policy.json").read_text(
                encoding="utf-8"
            )
        )
        rules: list[tuple[str, tuple[str, ...]]] = []
        for line in (REPOSITORY / ".github/CODEOWNERS").read_text(
            encoding="utf-8"
        ).splitlines():
            fields = line.strip().split()
            if fields and not fields[0].startswith("#"):
                rules.append((fields[0], tuple(fields[1:])))
        targets = [
            *policy["rulebookPaths"],
            *policy["judgePaths"],
            *(
                str(path.relative_to(REPOSITORY))
                for path in (REPOSITORY / ".github/workflows").glob("*")
                if path.is_file()
            ),
            "docs/governance/codeowners-bootstrap.md",
        ]
        for target in targets:
            with self.subTest(target=target):
                owners: tuple[str, ...] = ()
                for pattern, candidate in rules:
                    if artifacts._codeowners_pattern_matches(pattern, target):
                        owners = candidate
                self.assertIn("@NIV49", owners)

        bootstrap = (
            REPOSITORY / "docs/governance/codeowners-bootstrap.md"
        ).read_text(encoding="utf-8")
        self.assertIn("不能依赖该文件自我批准或自我保护", bootstrap)
        self.assertIn("GitHub API", bootstrap)

    def test_codeowners_parser_honors_inline_comments_and_rejects_bad_patterns(
        self,
    ) -> None:
        invalid_content = (
            "/.github/CODEOWNERS # @NIV49\n"
            "/[.]github/CODEOWNERS @NIV49\n"
            "/.github**CODEOWNERS @NIV49\n"
        )

        rules, errors = artifacts.parse_codeowners_rules(invalid_content)

        self.assertEqual([("/.github/CODEOWNERS", ())], rules)
        self.assertEqual(2, len(errors), errors)
        self.assertTrue(any("unsupported pattern" in error for error in errors), errors)
        self.assertFalse(
            artifacts._codeowners_pattern_matches(
                "/.github**CODEOWNERS",
                ".github/CODEOWNERS",
            )
        )

        valid_rules, valid_errors = artifacts.parse_codeowners_rules(
            "/.github/CODEOWNERS @NIV49\n"
        )
        self.assertEqual([], valid_errors)
        self.assertEqual(
            [("/.github/CODEOWNERS", ("@NIV49",))],
            valid_rules,
        )

    def test_artifact_discovery_rejects_symlinked_json(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            artifacts_root = root / "artifacts"
            artifacts_root.mkdir()
            outside = root / "outside.json"
            outside.write_text("{}", encoding="utf-8")
            (artifacts_root / "escaped.json").symlink_to(outside)

            with self.assertRaisesRegex(artifacts.ContractError, "symbolic link"):
                artifacts.discover_artifact_bundles(artifacts_root)


class HardenedBundleFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.repository = self.root / "target"
        self.repository.mkdir()
        git(self.repository, "init", "-q")
        git(self.repository, "config", "user.email", "test@example.invalid")
        git(self.repository, "config", "user.name", "Contract Test")
        git(self.repository, "commit", "--allow-empty", "-q", "-m", "external bootstrap anchor")
        self.empty_policy_anchor = git(self.repository, "rev-parse", "HEAD")
        self.legacy_workspace = self.root / "legacy"
        self.legacy_workspace.mkdir()
        self.source_repository = self.legacy_workspace / "identity-service"
        self.source_repository.mkdir()
        git(self.source_repository, "init", "-q")
        git(
            self.source_repository,
            "config",
            "user.email",
            "test@example.invalid",
        )
        git(self.source_repository, "config", "user.name", "Contract Test")
        self.source_repository.joinpath("intent.md").write_text(
            "legacy intent", encoding="utf-8"
        )
        source_commit = commit_all(self.source_repository, "legacy intent")
        self.source_snapshots = [
            {
                "sourceSnapshotId": "identity-service",
                "repositoryPath": str(self.source_repository.resolve()),
                "sourceCommitSha": source_commit,
                "evidencePaths": ["intent.md"],
                "readMethod": "validated-git-object",
            }
        ]
        self.private_keys = {
            "key-b": Ed25519PrivateKey.generate(),
            "key-c": Ed25519PrivateKey.generate(),
        }
        self.reviewers = [
            self.reviewer("reviewer-b", "business-security", "key-b"),
            self.reviewer("reviewer-c", "implementation-adversary", "key-c"),
        ]
        self.policy = {
            "schemaVersion": 2,
            "targetRepositoryId": "synthetic-target",
            "canonicalRepositoryPath": "/canonical/macos/payment-web-platform",
            "rulebookPaths": [
                ".agents/payment-modernization-policy.json",
                "AGENTS.md",
                "rules/IAM-001.json",
            ],
            "ruleCardPaths": ["rules/IAM-001.json"],
            "judgePaths": [
                ".agents/payment-modernization-judge-registry.json",
                ".agents/payment-modernization/artifacts/README.md",
                "judge/IAM-001.test",
            ],
            "trustedReviewers": self.reviewers,
        }
        self.rule_payload = {
            "ruleId": "IAM-001",
            "status": "approved",
            "statement": "Deny unscoped access.",
            "scope": ["identity"],
            "given": ["an authenticated principal"],
            "when": ["scope is absent"],
            "then": ["deny access"],
            "counterexamples": [],
            "evidence": [
                {
                    "kind": "git",
                    "sourceSnapshotId": "identity-service",
                    "evidencePath": "intent.md",
                    "location": "legacy intent",
                }
            ],
            "confidence": "high",
            "judgeTests": ["IAM-001"],
        }
        self.write_policy(self.policy)
        self.repository.joinpath("AGENTS.md").write_text(
            "# Synthetic repository policy\n", encoding="utf-8"
        )
        self.repository.joinpath("rules").mkdir()
        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        self.repository.joinpath("judge").mkdir()
        self.repository.joinpath("judge/IAM-001.test").write_text(
            "assert denied\n", encoding="utf-8"
        )
        self.repository.joinpath(
            ".agents/payment-modernization-judge-registry.json"
        ).write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "checks": [
                        {
                            "checkId": "IAM-001",
                            "path": "judge/IAM-001.test",
                            "command": "python -I judge/IAM-001.test",
                            "ruleIds": ["IAM-001"],
                        }
                    ],
                },
                sort_keys=True,
            ),
            encoding="utf-8",
        )
        artifact_root = self.repository / ".agents/payment-modernization/artifacts"
        artifact_root.mkdir(parents=True)
        artifact_root.joinpath("README.md").write_text(
            "# Synthetic artifact root\n", encoding="utf-8"
        )
        anchor_policy = dict(self.policy)
        anchor_policy["trustedReviewers"] = []
        self.write_policy(anchor_policy)
        self.trusted_anchor = commit_all(self.repository, "external empty trust anchor")
        self.rule_payload["status"] = "approved"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        self.write_policy(self.policy)
        self.base = commit_all(self.repository, "baseline policy")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def reviewer(self, reviewer_id: str, role: str, key_id: str) -> dict[str, str]:
        public_key = (
            self.private_keys[key_id]
            .public_key()
            .public_bytes(
                encoding=serialization.Encoding.Raw,
                format=serialization.PublicFormat.Raw,
            )
        )
        return {
            "reviewerId": reviewer_id,
            "reviewerRole": role,
            "keyId": key_id,
            "signatureAlgorithm": "Ed25519",
            "publicKey": base64.b64encode(public_key).decode("ascii"),
        }

    def write_policy(self, policy: dict[str, object]) -> None:
        path = self.repository / ".agents/payment-modernization-policy.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(policy, sort_keys=True), encoding="utf-8")

    def manifest(
        self, commit: str, paths: list[str], digest_field: str
    ) -> dict[str, object]:
        contents = {
            path: evidence.read_git_evidence(self.repository, commit, path)
            for path in paths
        }
        return {
            "label": "display-only",
            "paths": paths,
            digest_field: artifacts.content_bundle_digest(contents),
        }

    def sign_review(self, review: dict[str, object], key_id: str) -> None:
        review["signature"] = base64.b64encode(
            self.private_keys[key_id].sign(artifacts.canonical_review_payload(review))
        ).decode("ascii")

    def validate(self, bundle: dict[str, object]) -> list[str]:
        return artifacts.validate_bundle(
            bundle,
            target_repository_override=self.repository,
            trusted_legacy_workspace=self.legacy_workspace,
        )

    def validate_repository(
        self,
        commit: str,
        *,
        trusted_policy_commit: str | None | object = DEFAULT_TRUST_ANCHOR,
    ) -> list[str]:
        anchor = (
            self.base
            if trusted_policy_commit is DEFAULT_TRUST_ANCHOR
            else trusted_policy_commit
        )
        return artifacts.validate_repository_artifacts(
            self.repository,
            commit,
            trusted_legacy_workspace=self.legacy_workspace,
            trusted_policy_commit=anchor,
        )

    def bind_queue_and_resign(self, bundle: dict[str, object]) -> None:
        digest = artifacts.queue_items_digest(bundle["queueItems"])
        for review in bundle["reviewResults"]:  # type: ignore[union-attr]
            review["queueDigest"] = digest
            self.sign_review(review, review["keyId"])

    def make_bundle(self, target: str | None = None) -> dict[str, object]:
        target = target or self.base
        base_rulebook = self.manifest(
            self.base,
            self.policy["rulebookPaths"],
            "rulebookDigest",  # type: ignore[arg-type]
        )
        base_judge = self.manifest(
            self.base,
            self.policy["judgePaths"],
            "judgeDigest",  # type: ignore[arg-type]
        )
        evaluated_policy = json.loads(
            evidence.read_git_evidence(
                self.repository,
                target,
                ".agents/payment-modernization-policy.json",
            )
        )
        evaluated_rulebook = self.manifest(
            target, evaluated_policy["rulebookPaths"], "rulebookDigest"
        )
        evaluated_judge = self.manifest(
            target, evaluated_policy["judgePaths"], "judgeDigest"
        )
        task_key = artifacts.task_identity_key(
            turn_id="turn-1",
            slice_id="iam-login",
            target_base_sha=self.base,
            source_snapshots=self.source_snapshots,
            rulebook_digest=base_rulebook["rulebookDigest"],  # type: ignore[arg-type]
            judge_digest=base_judge["judgeDigest"],  # type: ignore[arg-type]
            non_git_evidence=[],
            target_repository_id="synthetic-target",
            rulebook_manifest=base_rulebook,
            judge_manifest=base_judge,
            rule_ids=["IAM-001"],
            judge_commands=["IAM-001"],
        )
        evaluated_key = artifacts.evaluated_version_key(
            task_key,
            target,
            evaluated_rulebook["rulebookDigest"],  # type: ignore[arg-type]
            evaluated_judge["judgeDigest"],  # type: ignore[arg-type]
        )
        capability = {
            "turnId": "turn-1",
            "sliceId": "iam-login",
            "path": "reimagine",
            "sourceSnapshots": self.source_snapshots,
            "targetRepositoryPath": "/canonical/macos/payment-web-platform",
            "targetRepositoryId": "synthetic-target",
            "targetBaseSha": self.base,
            "rulebookManifest": base_rulebook,
            "judgeManifest": base_judge,
            "taskIdentityKey": task_key,
            "nonGitEvidence": [],
            "ruleIds": ["IAM-001"],
            "actors": [],
            "inputs": [],
            "outputs": [],
            "dependencies": [],
            "ownedPaths": [],
            "forbiddenChanges": [],
            "entryCriteria": [],
            "exitCriteria": [],
            "judgeCommands": ["IAM-001"],
        }
        evaluated_snapshot = {
            "targetCommitSha": target,
            "rulebookManifest": evaluated_rulebook,
            "judgeManifest": evaluated_judge,
            "evaluatedVersionKey": evaluated_key,
        }

        reviews = []
        queue_items: list[object] = []
        queue_digest = artifacts.queue_items_digest(queue_items)
        for review_id, reviewer_id, role, key_id in (
            ("review-b", "reviewer-b", "business-security", "key-b"),
            ("review-c", "reviewer-c", "implementation-adversary", "key-c"),
        ):
            review: dict[str, object] = {
                "reviewResultId": review_id,
                "taskIdentityKey": task_key,
                "evaluatedVersionKey": evaluated_key,
                "reviewerId": reviewer_id,
                "reviewerRole": role,
                "reviewIdempotencyKey": artifacts.review_idempotency_key(
                    evaluated_key, reviewer_id, role
                ),
                "targetCommitSha": target,
                "rulebookDigest": evaluated_rulebook["rulebookDigest"],
                "judgeDigest": evaluated_judge["judgeDigest"],
                "queueDigest": queue_digest,
                "startCommitSha": target,
                "endCommitSha": target,
                "snapshotValid": True,
                "verdict": "PASS",
                "findings": [],
                "commandsRun": [
                    {
                        "checkId": "IAM-001",
                        "command": "python -I judge/IAM-001.test",
                        "targetCommitSha": target,
                        "exitCode": 0,
                        "resultDigest": "sha256:" + "0" * 64,
                    }
                ],
                "limitations": [],
                "keyId": key_id,
                "signatureAlgorithm": "Ed25519",
                "reviewPurpose": "rule-approval",
                "approvalSubjects": [
                    {
                        "rulePath": "rules/IAM-001.json",
                        "ruleId": "IAM-001",
                        "rulePayloadDigest": artifacts.rule_payload_digest(
                            self.rule_payload
                        ),
                    }
                ],
            }
            self.sign_review(review, key_id)
            reviews.append(review)

        rule_card: dict[str, object] = {
            "rulePath": "rules/IAM-001.json",
            "rulePayload": self.rule_payload,
        }
        if self.rule_payload.get("status") == "approved":
            rule_card.update(
                {
                    "approvalCommit": target,
                    "approvedBy": ["reviewer-b", "reviewer-c"],
                    "approvalReviewRefs": ["review-b", "review-c"],
                }
            )
        return {
            "lifecycleStatus": "closed",
            "capabilitySlice": capability,
            "evaluatedSnapshot": evaluated_snapshot,
            "ruleCards": [rule_card],
            "reviewResults": reviews,
            "queueItems": queue_items,
        }

    def make_candidate_bundle(self) -> tuple[dict[str, object], str]:
        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        target = commit_all(self.repository, "candidate rule payload")
        return self.make_bundle(target), target

    def test_valid_bundle_binds_policy_snapshot_payload_and_signed_reviews(
        self,
    ) -> None:
        self.assertEqual(self.validate(self.make_bundle()), [])

    def test_draft_preflight_passes_but_canonical_root_requires_closed(self) -> None:
        bundle, _target = self.make_candidate_bundle()
        bundle["lifecycleStatus"] = "draft"
        bundle["reviewResults"] = []

        self.assertEqual(self.validate(bundle), [])

        artifact_path = (
            self.repository / ".agents/payment-modernization/artifacts/draft.json"
        )
        artifact_path.write_text(json.dumps(bundle, sort_keys=True), encoding="utf-8")
        gate_commit = commit_all(self.repository, "track a draft bundle")
        errors = self.validate_repository(gate_commit)
        self.assertTrue(
            any(
                "canonical artifact bundle must be closed" in error for error in errors
            ),
            errors,
        )

    def test_closed_bundle_requires_exactly_two_independent_pass_reviews(self) -> None:
        bundle, _target = self.make_candidate_bundle()
        reviews = list(bundle["reviewResults"])  # type: ignore[arg-type]

        bundle["reviewResults"] = []
        zero_errors = self.validate(bundle)
        self.assertTrue(
            any("exactly two independent" in error for error in zero_errors),
            zero_errors,
        )

        bundle["reviewResults"] = reviews[:1]
        one_errors = self.validate(bundle)
        self.assertTrue(
            any("exactly two independent" in error for error in one_errors),
            one_errors,
        )

        bundle["reviewResults"] = reviews
        self.assertEqual(self.validate(bundle), [])

    def test_closed_bundle_rejects_an_unresolved_blocker_queue_item(self) -> None:
        bundle = self.make_bundle()
        evaluated_version_key = bundle["evaluatedSnapshot"][  # type: ignore[index]
            "evaluatedVersionKey"
        ]
        blocker = {
            "fingerprint": "stable-unresolved-blocker",
            "sliceId": "iam-login",
            "evaluatedVersionKey": evaluated_version_key,
            "failureSource": {"type": "judge", "checkId": "judge:B-1"},
            "severity": "BLOCKER",
            "trigger": "reproduce",
            "controlFlow": ["one", "two"],
            "evidence": ["synthetic evidence"],
            "impact": "unsafe closure",
            "verification": "python3 -m unittest",
            "status": "open",
            "failedReviewRounds": 0,
        }
        bundle["queueItems"] = [blocker]
        self.bind_queue_and_resign(bundle)

        errors = self.validate(bundle)

        self.assertTrue(
            any("unresolved BLOCKER Queue Item" in error for error in errors), errors
        )

        bundle["lifecycleStatus"] = "draft"
        self.assertEqual(self.validate(bundle), [])

    def test_closed_bundle_requires_queue_for_an_unresolved_should_fix_finding(
        self,
    ) -> None:
        bundle = self.make_bundle()
        review = bundle["reviewResults"][0]  # type: ignore[index]
        finding_id = "S-QUEUE-001"
        finding = {
            "findingId": finding_id,
            "severity": "SHOULD_FIX",
            "status": "open",
            "repositoryRelativePath": "modules/identity/Policy.java",
            "line": 7,
            "symbol": "authorize",
            "controlFlow": ["request", "authorize"],
            "trigger": "scoped request",
            "impact": "verified reliability gap",
            "evidence": ["synthetic review evidence"],
            "verification": "python -I -m unittest",
            "targetCommitSha": review["targetCommitSha"],
            "evaluatedVersionKey": review["evaluatedVersionKey"],
            "rulebookDigest": review["rulebookDigest"],
            "judgeDigest": review["judgeDigest"],
            "resolution": "unresolved",
        }
        review["findings"] = [finding]
        self.sign_review(review, "key-b")

        missing_errors = self.validate(bundle)

        self.assertTrue(
            any("unresolved Review finding" in error for error in missing_errors),
            missing_errors,
        )

        queue_item = {
            "fingerprint": "stable-should-fix-root-cause",
            "sliceId": "iam-login",
            "evaluatedVersionKey": review["evaluatedVersionKey"],
            "failureSource": {
                "type": "review",
                "checkId": f"review:{finding_id}",
            },
            "severity": "SHOULD_FIX",
            "trigger": finding["trigger"],
            "controlFlow": finding["controlFlow"],
            "evidence": finding["evidence"],
            "impact": finding["impact"],
            "verification": finding["verification"],
            "status": "open",
            "failedReviewRounds": 0,
        }
        bundle["queueItems"] = [queue_item]
        self.bind_queue_and_resign(bundle)

        self.assertEqual([], self.validate(bundle))

        second_review = bundle["reviewResults"][1]  # type: ignore[index]
        second_review["findings"] = [dict(finding)]
        self.bind_queue_and_resign(bundle)

        duplicate_finding_errors = self.validate(bundle)

        self.assertTrue(
            any("findingId must be unique" in error for error in duplicate_finding_errors),
            duplicate_finding_errors,
        )

    def test_review_queue_item_cannot_exist_without_a_matching_finding(self) -> None:
        bundle = self.make_bundle()
        review = bundle["reviewResults"][0]  # type: ignore[index]
        bundle["queueItems"] = [
            {
                "fingerprint": "orphan-review-queue-item",
                "sliceId": "iam-login",
                "evaluatedVersionKey": review["evaluatedVersionKey"],
                "failureSource": {
                    "type": "review",
                    "checkId": "review:S-ORPHAN",
                },
                "severity": "SHOULD_FIX",
                "trigger": "synthetic orphan",
                "controlFlow": ["review", "queue"],
                "evidence": ["synthetic review evidence"],
                "impact": "untraceable work item",
                "verification": "python -I -m unittest",
                "status": "open",
                "failedReviewRounds": 0,
            }
        ]
        self.bind_queue_and_resign(bundle)

        errors = self.validate(bundle)

        self.assertTrue(
            any("does not map to an unresolved Review finding" in error for error in errors),
            errors,
        )

    def test_review_finding_queue_mapping_rejects_mismatch_and_duplicates(self) -> None:
        bundle = self.make_bundle()
        review = bundle["reviewResults"][0]  # type: ignore[index]
        finding_id = "S-QUEUE-AMBIGUOUS"
        finding = {
            "findingId": finding_id,
            "severity": "SHOULD_FIX",
            "status": "open",
            "repositoryRelativePath": "modules/identity/Policy.java",
            "line": 9,
            "symbol": "authorize",
            "controlFlow": ["request", "authorize"],
            "trigger": "scoped request",
            "impact": "verified reliability gap",
            "evidence": ["synthetic review evidence"],
            "verification": "python -I -m unittest",
            "targetCommitSha": review["targetCommitSha"],
            "evaluatedVersionKey": review["evaluatedVersionKey"],
            "rulebookDigest": review["rulebookDigest"],
            "judgeDigest": review["judgeDigest"],
            "resolution": "unresolved",
        }
        review["findings"] = [finding]
        self.sign_review(review, "key-b")
        queue_item = {
            "fingerprint": "stable-review-root-cause-one",
            "sliceId": "iam-login",
            "evaluatedVersionKey": review["evaluatedVersionKey"],
            "failureSource": {
                "type": "review",
                "checkId": f"review:{finding_id}",
            },
            "severity": "SHOULD_FIX",
            "trigger": finding["trigger"],
            "controlFlow": finding["controlFlow"],
            "evidence": finding["evidence"],
            "impact": finding["impact"],
            "verification": finding["verification"],
            "status": "closed",
            "failedReviewRounds": 0,
        }
        bundle["queueItems"] = [queue_item]
        self.bind_queue_and_resign(bundle)

        mismatch_errors = self.validate(bundle)

        self.assertTrue(
            any("status does not match" in error for error in mismatch_errors),
            mismatch_errors,
        )

        queue_item["status"] = "open"
        duplicate = dict(queue_item)
        duplicate["fingerprint"] = "stable-review-root-cause-two"
        bundle["queueItems"] = [queue_item, duplicate]
        self.bind_queue_and_resign(bundle)

        duplicate_errors = self.validate(bundle)

        self.assertTrue(
            any("exactly one Queue Item" in error for error in duplicate_errors),
            duplicate_errors,
        )

    def test_canonical_artifact_root_requires_readme_and_rejects_other_files(
        self,
    ) -> None:
        artifact_root = self.repository / ".agents/payment-modernization/artifacts"
        artifact_root.joinpath("README.md").unlink()
        missing_readme_commit = commit_all(self.repository, "remove artifact readme")
        missing_errors = self.validate_repository(missing_readme_commit)
        self.assertTrue(
            any(
                "artifact root must contain README.md" in error
                for error in missing_errors
            ),
            missing_errors,
        )

        artifact_root.joinpath("README.md").write_text(
            "# Synthetic artifact root\n", encoding="utf-8"
        )
        artifact_root.joinpath("extra.yaml").write_text(
            "pass" + "word: synthetic-only\n", encoding="utf-8"
        )
        extra_commit = commit_all(self.repository, "track non-json artifact")
        extra_errors = self.validate_repository(extra_commit)
        self.assertTrue(
            any("non-JSON tracked file" in error for error in extra_errors),
            extra_errors,
        )

    def test_runtime_repository_mapping_accepts_policy_identity_not_host_path(
        self,
    ) -> None:
        bundle = self.make_bundle()
        self.assertFalse(
            Path(bundle["capabilitySlice"]["targetRepositoryPath"]).exists()
        )  # type: ignore[index]
        self.assertEqual(self.validate(bundle), [])

    def test_every_normative_capability_field_is_bound_by_task_identity(self) -> None:
        list_fields = (
            "actors",
            "inputs",
            "outputs",
            "ruleIds",
            "dependencies",
            "ownedPaths",
            "forbiddenChanges",
            "entryCriteria",
            "exitCriteria",
            "judgeCommands",
        )
        for field in list_fields:
            with self.subTest(field=field):
                bundle = json.loads(json.dumps(self.make_bundle()))
                bundle["capabilitySlice"][field].append("tampered")
                errors = self.validate(bundle)
                self.assertTrue(
                    any("taskIdentityKey" in error for error in errors), errors
                )
        bundle = json.loads(json.dumps(self.make_bundle()))
        bundle["capabilitySlice"]["path"] = "transform"
        errors = self.validate(bundle)
        self.assertTrue(any("taskIdentityKey" in error for error in errors), errors)

    def test_approved_registered_rule_requires_exactly_one_tracked_signed_bundle(
        self,
    ) -> None:
        missing = self.validate_repository(self.base)
        self.assertTrue(
            any(
                "exactly one unique valid signed approval" in error for error in missing
            )
        )

        artifact_path = (
            self.repository / ".agents/payment-modernization/artifacts/approval.json"
        )
        artifact_path.parent.mkdir(parents=True, exist_ok=True)
        artifact_path.write_text(
            json.dumps(self.make_bundle(), sort_keys=True), encoding="utf-8"
        )
        gate_commit = commit_all(self.repository, "record signed approval bundle")

        self.assertEqual(self.validate_repository(gate_commit), [])

        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        downgrade_commit = commit_all(self.repository, "unsigned rule downgrade")
        downgrade_errors = self.validate_repository(downgrade_commit)
        self.assertTrue(
            any("cannot be downgraded" in error for error in downgrade_errors)
        )

        git(
            self.repository,
            "checkout",
            "-q",
            "-b",
            "downgrade-and-delete",
            gate_commit,
        )
        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        artifact_path.unlink()
        deleted_bundle_commit = commit_all(
            self.repository, "downgrade and delete approval history"
        )
        deleted_bundle_errors = self.validate_repository(deleted_bundle_commit)
        self.assertTrue(
            any("cannot be downgraded" in error for error in deleted_bundle_errors)
        )

        git(self.repository, "checkout", "-q", "-b", "remove-rule", gate_commit)
        artifact_path.unlink()
        removed_policy = dict(self.policy)
        removed_policy["rulebookPaths"] = [
            ".agents/payment-modernization-policy.json",
            "AGENTS.md",
        ]
        removed_policy["ruleCardPaths"] = []
        self.write_policy(removed_policy)
        removal_commit = commit_all(self.repository, "unsigned rule removal")
        removal_errors = self.validate_repository(removal_commit)
        self.assertTrue(any("cannot be removed" in error for error in removal_errors))

    def test_duplicate_approval_reference_is_deduplicated_but_conflict_fails(
        self,
    ) -> None:
        artifact_root = self.repository / ".agents/payment-modernization/artifacts"
        artifact_root.mkdir(parents=True, exist_ok=True)
        bundle = self.make_bundle()
        serialized = json.dumps(bundle, sort_keys=True)
        artifact_root.joinpath("approval-one.json").write_text(
            serialized, encoding="utf-8"
        )
        artifact_root.joinpath("approval-two.json").write_text(
            serialized, encoding="utf-8"
        )
        duplicate_gate = commit_all(self.repository, "duplicate approval references")
        self.assertEqual(
            self.validate_repository(duplicate_gate),
            [],
        )

        conflict = self.make_bundle()
        alternate_refs: list[str] = []
        for review in conflict["reviewResults"]:  # type: ignore[union-attr]
            review["reviewResultId"] = f"{review['reviewResultId']}-alternate"
            alternate_refs.append(review["reviewResultId"])
            self.sign_review(review, review["keyId"])
        conflict["ruleCards"][0]["approvalReviewRefs"] = alternate_refs  # type: ignore[index]
        artifact_root.joinpath("approval-conflict.json").write_text(
            json.dumps(conflict, sort_keys=True), encoding="utf-8"
        )
        conflict_gate = commit_all(self.repository, "conflicting approval envelope")

        errors = self.validate_repository(conflict_gate)

        self.assertTrue(
            any("exactly one unique valid signed approval" in error for error in errors)
        )

    def test_cross_bundle_review_identity_allows_exact_copy_but_rejects_conflict(
        self,
    ) -> None:
        artifact_root = self.repository / ".agents/payment-modernization/artifacts"
        bundle = self.make_bundle()
        serialized = json.dumps(bundle, sort_keys=True)
        artifact_root.joinpath("review-copy-one.json").write_text(
            serialized, encoding="utf-8"
        )
        artifact_root.joinpath("review-copy-two.json").write_text(
            serialized, encoding="utf-8"
        )
        copy_commit = commit_all(self.repository, "duplicate exact review payload")
        self.assertEqual(self.validate_repository(copy_commit), [])

        conflict = json.loads(serialized)
        conflicting_review = conflict["reviewResults"][0]
        conflicting_review["commandsRun"] = ["different-but-signed-command"]
        self.sign_review(conflicting_review, conflicting_review["keyId"])
        artifact_root.joinpath("review-copy-two.json").write_text(
            json.dumps(conflict, sort_keys=True), encoding="utf-8"
        )
        conflict_commit = commit_all(self.repository, "conflicting review identity")

        errors = self.validate_repository(conflict_commit)
        self.assertTrue(
            any("conflicting canonical signed payload" in error for error in errors),
            errors,
        )

    def test_external_policy_anchor_rejects_self_injected_reviewer_keys(self) -> None:
        artifact_path = (
            self.repository / ".agents/payment-modernization/artifacts/approval.json"
        )
        artifact_path.parent.mkdir(parents=True, exist_ok=True)
        artifact_path.write_text(
            json.dumps(self.make_bundle(), sort_keys=True), encoding="utf-8"
        )
        gate_commit = commit_all(self.repository, "self-signed approval attempt")

        anchored_errors = self.validate_repository(
            gate_commit, trusted_policy_commit=self.trusted_anchor
        )
        self.assertTrue(
            any("trusted policy anchor" in error for error in anchored_errors)
        )
        self.assertEqual(
            self.validate_repository(gate_commit, trusted_policy_commit=self.base),
            [],
        )

        changed_policy = dict(self.policy)
        changed_policy["trustedReviewers"] = []
        self.write_policy(changed_policy)
        changed_commit = commit_all(self.repository, "untrusted key registry change")
        changed_errors = self.validate_repository(
            changed_commit, trusted_policy_commit=self.base
        )
        self.assertTrue(any("trustedReviewers" in error for error in changed_errors))

    def test_candidate_rule_needs_no_bundle_and_commit_scan_ignores_live_mutation(
        self,
    ) -> None:
        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        candidate_commit = commit_all(self.repository, "candidate registry")
        self.rule_payload["status"] = "approved"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )

        self.assertEqual(
            self.validate_repository(candidate_commit),
            [],
        )

    def test_commit_artifact_discovery_rejects_tracked_symlink(self) -> None:
        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        artifact_root = self.repository / ".agents/payment-modernization/artifacts"
        artifact_root.mkdir(parents=True, exist_ok=True)
        outside = self.root / "outside.json"
        outside.write_text("{}", encoding="utf-8")
        (artifact_root / "escaped.json").symlink_to(outside)
        commit = commit_all(self.repository, "tracked artifact symlink")

        errors = self.validate_repository(commit)

        self.assertTrue(any("forbidden link mode" in error for error in errors))

    def test_signed_bundle_target_must_be_ancestor_of_gate_commit(self) -> None:
        self.repository.joinpath("unrelated.txt").write_text(
            "orphan target", encoding="utf-8"
        )
        target = commit_all(self.repository, "orphan evaluated target")
        bundle = self.make_bundle(target)
        git(self.repository, "checkout", "-q", "-b", "gate", self.base)
        artifact_path = (
            self.repository / ".agents/payment-modernization/artifacts/orphan.json"
        )
        artifact_path.parent.mkdir(parents=True, exist_ok=True)
        artifact_path.write_text(json.dumps(bundle, sort_keys=True), encoding="utf-8")
        gate_commit = commit_all(self.repository, "gate without target ancestry")

        errors = self.validate_repository(gate_commit)

        self.assertTrue(any("ancestor of the gate commit" in error for error in errors))

    def test_cross_bundle_review_and_duplicate_idempotency_are_rejected(self) -> None:
        bundle = self.make_bundle()
        review = bundle["reviewResults"][0]  # type: ignore[index]
        review["taskIdentityKey"] = "sha256:" + "f" * 64
        self.sign_review(review, "key-b")
        duplicate = dict(review)
        duplicate["reviewResultId"] = "review-b-copy"
        self.sign_review(duplicate, "key-b")
        bundle["reviewResults"].append(duplicate)  # type: ignore[union-attr]

        errors = self.validate(bundle)

        self.assertTrue(any("taskIdentityKey" in error for error in errors))
        self.assertTrue(
            any("duplicate reviewIdempotencyKey" in error for error in errors)
        )

    def test_forged_signature_and_untrusted_reviewer_are_rejected(self) -> None:
        forged = self.make_bundle()
        forged_review = forged["reviewResults"][0]  # type: ignore[index]
        forged_review["verdict"] = "FAIL"
        errors = self.validate(forged)
        self.assertTrue(any("signature" in error for error in errors))

        untrusted = self.make_bundle()
        review = untrusted["reviewResults"][0]  # type: ignore[index]
        review["reviewerId"] = "invented-reviewer"
        self.sign_review(review, "key-b")
        errors = self.validate(untrusted)
        self.assertTrue(any("trusted reviewer" in error for error in errors))

    def test_evaluated_registry_cannot_remove_baseline_paths(self) -> None:
        changed = dict(self.policy)
        changed["rulebookPaths"] = [
            ".agents/payment-modernization-policy.json",
            "AGENTS.md",
        ]
        changed["ruleCardPaths"] = []
        self.write_policy(changed)
        target = commit_all(self.repository, "remove baseline rule path")

        errors = self.validate(self.make_bundle(target))

        self.assertTrue(any("remove baseline" in error for error in errors))

    def test_rule_payload_and_approval_commit_must_match_target_snapshot(self) -> None:
        mismatch = self.make_bundle()
        mismatch["ruleCards"][0]["rulePayload"]["statement"] = "Allow all."  # type: ignore[index]
        errors = self.validate(mismatch)
        self.assertTrue(any("rulePayload" in error for error in errors))

        wrong_commit = self.make_bundle()
        wrong_commit["ruleCards"][0]["approvalCommit"] = "f" * 40  # type: ignore[index]
        errors = self.validate(wrong_commit)
        self.assertTrue(any("approvalCommit" in error for error in errors))

    def test_unconfigured_trust_store_fails_approved_rule_closed(self) -> None:
        empty_trust_policy = dict(self.policy)
        empty_trust_policy["trustedReviewers"] = []
        self.write_policy(empty_trust_policy)
        target = commit_all(self.repository, "remove review trust")
        self.policy = empty_trust_policy
        self.base = target
        bundle = self.make_bundle()
        errors = self.validate(bundle)
        self.assertTrue(any("trusted reviewer" in error for error in errors))

    def test_same_public_key_cannot_impersonate_two_reviewers(self) -> None:
        duplicate_key_policy = json.loads(json.dumps(self.policy))
        duplicate_key_policy["trustedReviewers"][1]["publicKey"] = (  # type: ignore[index]
            duplicate_key_policy["trustedReviewers"][0]["publicKey"]  # type: ignore[index]
        )
        self.write_policy(duplicate_key_policy)
        target = commit_all(self.repository, "duplicate reviewer key")

        errors = self.validate(self.make_bundle(target))

        self.assertTrue(any("public key" in error for error in errors))

    def test_pass_review_cannot_hide_an_unresolved_blocker(self) -> None:
        bundle = self.make_bundle()
        review = bundle["reviewResults"][0]  # type: ignore[index]
        review["findings"] = [
            {"severity": "BLOCKER", "status": "open", "findingId": "B-1"}
        ]
        self.sign_review(review, "key-b")

        errors = self.validate(bundle)

        self.assertTrue(any("unresolved BLOCKER" in error for error in errors))

    def test_approved_rule_requires_at_least_one_judge_test(self) -> None:
        self.rule_payload["judgeTests"] = []
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        target = commit_all(self.repository, "remove judge coverage")

        errors = self.validate(self.make_bundle(target))

        self.assertTrue(any("judgeTests" in error for error in errors))

        self.rule_payload["judgeTests"] = [""]
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        target = commit_all(self.repository, "blank judge coverage")
        errors = self.validate(self.make_bundle(target))
        self.assertTrue(any("unique non-empty" in error for error in errors))

    def test_implementation_or_other_rule_review_cannot_approve_this_rule(self) -> None:
        bundle = self.make_bundle()
        first = bundle["reviewResults"][0]  # type: ignore[index]
        first["reviewPurpose"] = "implementation"
        first["approvalSubjects"] = []
        self.sign_review(first, "key-b")
        second = bundle["reviewResults"][1]  # type: ignore[index]
        second["approvalSubjects"] = [
            {
                "rulePath": "rules/OTHER-001.json",
                "ruleId": "OTHER-001",
                "rulePayloadDigest": "sha256:" + "1" * 64,
            }
        ]
        self.sign_review(second, "key-c")

        errors = self.validate(bundle)

        self.assertTrue(any("rule-approval" in error for error in errors))
        self.assertTrue(any("approval subject" in error for error in errors))

    def test_rule_registry_requires_exact_unique_bundle_coverage(self) -> None:
        missing = self.make_bundle()
        missing["ruleCards"] = []
        errors = self.validate(missing)
        self.assertTrue(any("ruleIds" in error for error in errors))

        duplicate = self.make_bundle()
        duplicate["ruleCards"].append(dict(duplicate["ruleCards"][0]))  # type: ignore[union-attr,index]
        errors = self.validate(duplicate)
        self.assertTrue(
            any("duplicate Rule Card rulePath" in error for error in errors)
        )

    def test_candidate_is_default_and_untrusted_terminal_status_is_rejected(
        self,
    ) -> None:
        self.rule_payload["status"] = "candidate"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        candidate_target = commit_all(self.repository, "candidate rule")
        candidate = self.make_bundle(candidate_target)
        self.assertEqual(self.validate(candidate), [])

        self.rule_payload["status"] = "rejected"
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        unsupported_target = commit_all(self.repository, "unsupported terminal state")
        unsupported = self.make_bundle(unsupported_target)
        errors = self.validate(unsupported)
        self.assertTrue(any("unsupported" in error for error in errors))

    def test_immutable_approved_status_cannot_be_downgraded_by_dropping_envelope(
        self,
    ) -> None:
        bundle = self.make_bundle()
        card = bundle["ruleCards"][0]  # type: ignore[index]
        card.pop("approvalCommit")
        card.pop("approvedBy")
        card.pop("approvalReviewRefs")
        bundle["reviewResults"] = []

        errors = self.validate(bundle)

        self.assertTrue(any("approval-envelope" in error for error in errors))

    def test_rule_payload_status_is_mandatory(self) -> None:
        self.rule_payload.pop("status")
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        target = commit_all(self.repository, "missing rule status")
        bundle = self.make_bundle(target)

        errors = self.validate(bundle)

        self.assertTrue(any("normative schema" in error for error in errors))

    def test_repository_scan_requires_an_explicit_trusted_policy_anchor(self) -> None:
        errors = self.validate_repository(self.base, trusted_policy_commit=None)

        self.assertTrue(any("explicit trusted policy anchor" in error for error in errors), errors)

    def test_empty_external_anchor_can_bootstrap_an_empty_reviewer_policy(self) -> None:
        errors = self.validate_repository(
            self.trusted_anchor,
            trusted_policy_commit=self.empty_policy_anchor,
        )

        self.assertEqual([], errors)

    def test_review_findings_use_a_complete_strict_schema(self) -> None:
        bundle = self.make_bundle()
        review = bundle["reviewResults"][0]  # type: ignore[index]
        review["findings"] = [
            {"findingId": "S-1", "severity": "SHOULD_FIX", "status": "open"}
        ]
        self.sign_review(review, "key-b")

        errors = self.validate(bundle)

        self.assertTrue(any("finding" in error and "exact schema" in error for error in errors), errors)

        valid = self.make_bundle()
        valid_review = valid["reviewResults"][0]  # type: ignore[index]
        valid_review["findings"] = [
            {
                "findingId": "S-1",
                "severity": "SHOULD_FIX",
                "status": "closed",
                "repositoryRelativePath": "modules/identity/Policy.java",
                "line": 7,
                "symbol": "authorize",
                "controlFlow": ["request", "authorize"],
                "trigger": "scoped request",
                "impact": "verified improvement",
                "evidence": ["synthetic test"],
                "verification": "python -I -m unittest",
                "targetCommitSha": valid_review["targetCommitSha"],
                "evaluatedVersionKey": valid_review["evaluatedVersionKey"],
                "rulebookDigest": valid_review["rulebookDigest"],
                "judgeDigest": valid_review["judgeDigest"],
                "resolution": "fixed",
            }
        ]
        self.sign_review(valid_review, "key-b")
        self.assertEqual([], self.validate(valid))

        valid_review["findings"][0]["severity"] = "WARNING"
        valid_review["findings"][0]["unexpected"] = True
        self.sign_review(valid_review, "key-b")
        invalid_errors = self.validate(valid)
        self.assertTrue(any("exact schema" in error for error in invalid_errors), invalid_errors)

    def test_approved_rule_judge_tests_resolve_to_signed_successful_execution(self) -> None:
        self.rule_payload["judgeTests"] = ["UNKNOWN-CHECK"]
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        target = commit_all(self.repository, "unknown judge coverage")

        errors = self.validate(self.make_bundle(target))

        self.assertTrue(any("Judge registry" in error for error in errors), errors)

        self.rule_payload["judgeTests"] = ["IAM-001"]
        self.repository.joinpath("rules/IAM-001.json").write_text(
            json.dumps(self.rule_payload, sort_keys=True), encoding="utf-8"
        )
        known_target = commit_all(self.repository, "known judge with failed execution")
        valid = self.make_bundle(known_target)
        failed_execution = valid["reviewResults"][0]  # type: ignore[index]
        failed_execution["commandsRun"][0]["exitCode"] = 1
        self.sign_review(failed_execution, "key-b")
        failed_errors = self.validate(valid)
        self.assertTrue(any("signed successful execution" in error for error in failed_errors), failed_errors)

    def test_closed_candidate_slice_requires_declared_signed_judge_execution(self) -> None:
        bundle, _target = self.make_candidate_bundle()
        missing_commands_bundle = json.loads(json.dumps(bundle))
        missing_commands_bundle["capabilitySlice"]["judgeCommands"] = []

        missing_commands = self.validate(missing_commands_bundle)

        self.assertTrue(
            any("judgeCommands" in error and "non-empty" in error for error in missing_commands),
            missing_commands,
        )

        for review in bundle["reviewResults"]:  # type: ignore[union-attr]
            review["commandsRun"] = []
            self.sign_review(review, review["keyId"])

        missing_executions = self.validate(bundle)

        self.assertTrue(
            any("signed successful Judge execution" in error for error in missing_executions),
            missing_executions,
        )

    def test_queue_state_is_bound_by_the_signed_review_payload(self) -> None:
        bundle = self.make_bundle()
        bundle["queueItems"] = [
            {
                "fingerprint": "signed-queue-state",
                "sliceId": "iam-login",
                "evaluatedVersionKey": bundle["evaluatedSnapshot"]["evaluatedVersionKey"],  # type: ignore[index]
                "failureSource": {"type": "review", "checkId": "review:S-1"},
                "severity": "SHOULD_FIX",
                "trigger": "reproduce",
                "controlFlow": ["one", "two"],
                "evidence": ["synthetic evidence"],
                "impact": "unsafe closure",
                "verification": "python -I -m unittest",
                "status": "closed",
                "failedReviewRounds": 0,
            }
        ]

        errors = self.validate(bundle)

        self.assertTrue(any("queueDigest" in error for error in errors), errors)

    def test_approval_activation_commit_rejects_unreviewed_tree_changes(self) -> None:
        artifact_path = self.repository / ".agents/payment-modernization/artifacts/approval.json"
        artifact_path.write_text(json.dumps(self.make_bundle(), sort_keys=True), encoding="utf-8")
        self.repository.joinpath("unreviewed-business-change.txt").write_text(
            "not covered by the approval envelope\n", encoding="utf-8"
        )
        gate_commit = commit_all(self.repository, "approval plus unrelated change")

        errors = self.validate_repository(gate_commit, trusted_policy_commit=self.base)

        self.assertTrue(any("activation commit" in error for error in errors), errors)

    def test_sibling_approval_activation_is_checked_against_its_real_parent(self) -> None:
        serialized = json.dumps(self.make_bundle(), sort_keys=True)
        artifact_relative = ".agents/payment-modernization/artifacts/approval.json"

        git(self.repository, "checkout", "-q", "-b", "clean-approval", self.base)
        clean_artifact = self.repository / artifact_relative
        clean_artifact.write_text(serialized, encoding="utf-8")
        commit_all(self.repository, "clean approval activation")

        git(self.repository, "checkout", "-q", "-b", "dirty-approval", self.base)
        dirty_artifact = self.repository / artifact_relative
        dirty_artifact.write_text(serialized, encoding="utf-8")
        self.repository.joinpath("unreviewed-sibling-change.txt").write_text(
            "must not inherit the clean sibling approval\n", encoding="utf-8"
        )
        dirty_commit = commit_all(self.repository, "dirty sibling activation")
        git(
            self.repository,
            "merge",
            "--no-ff",
            "-q",
            "-m",
            "merge sibling approvals",
            "clean-approval",
        )
        merge_commit = git(self.repository, "rev-parse", "HEAD")

        errors = self.validate_repository(
            merge_commit, trusted_policy_commit=self.base
        )

        self.assertTrue(
            any(
                dirty_commit in error and "outside canonical JSON envelopes" in error
                for error in errors
            ),
            errors,
        )

    def test_historical_artifact_errors_cannot_be_deleted_and_forgotten(self) -> None:
        artifact_path = self.repository / ".agents/payment-modernization/artifacts/approval.json"
        artifact_path.write_text(json.dumps(self.make_bundle(), sort_keys=True), encoding="utf-8")
        commit_all(self.repository, "valid approval")
        broken_path = self.repository / ".agents/payment-modernization/artifacts/broken.json"
        broken_path.write_text('{"broken":', encoding="utf-8")
        broken_commit = commit_all(self.repository, "broken historical artifact")
        broken_path.unlink()
        final_commit = commit_all(self.repository, "delete broken artifact")

        errors = self.validate_repository(final_commit, trusted_policy_commit=self.base)

        self.assertTrue(any(broken_commit in error for error in errors), errors)

    def test_strict_json_rejects_duplicate_members_and_non_finite_numbers(self) -> None:
        bundle_path = self.root / "duplicate.json"
        bundle_path.write_text('{"lifecycleStatus":"draft","lifecycleStatus":"closed"}', encoding="utf-8")
        with self.assertRaisesRegex(artifacts.ContractError, "duplicate JSON object member"):
            artifacts.load_bundle_file(bundle_path)

        with self.assertRaises(artifacts.ContractError):
            artifacts._canonical_digest("strict", {"value": math.nan})
        with self.assertRaises(artifacts.ContractError):
            artifacts.canonical_review_payload({"value": -math.inf})
        for raw in (
            '{"outer":{"value":1,"value":2}}',
            '{"value":NaN}',
            '{"value":Infinity}',
            '{"value":-Infinity}',
            '{"value":1e10000}',
        ):
            with self.subTest(raw=raw), self.assertRaises(artifacts.ContractError):
                artifacts._strict_json_loads(raw)


if __name__ == "__main__":
    unittest.main()
