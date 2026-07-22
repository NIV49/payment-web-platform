from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-doc-decisions.py"
SPEC = importlib.util.spec_from_file_location("check_doc_decisions", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

DECISION_DOCUMENTS = MODULE.DECISION_DOCUMENTS
parse_markers = MODULE.parse_markers
validate_decision = MODULE.validate_decision
validate_reference = MODULE.validate_reference


class DocumentationDecisionValidationTest(unittest.TestCase):
    decision_id = "IAM-GLOBAL-USER-MULTI-TENANT"

    def write_document(
        self, repository: Path, relative_path: str, content: str
    ) -> None:
        target = repository / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def validate_marker(self, marker: str) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            relative_path = "docs/decision-context.md"
            self.write_document(repository, relative_path, marker + "\n")
            return validate_decision(
                repository,
                self.decision_id,
                (relative_path,),
            )

    def test_rejects_duplicate_marker_attributes(self) -> None:
        errors = self.validate_marker(
            "<!-- decision-status "
            f"id={self.decision_id} status=accepted status=pending "
            "ref=docs/adr/0008-membership.md ref=none -->"
        )

        self.assertTrue(any("duplicate attribute" in error for error in errors), errors)

    def test_rejects_unknown_marker_attributes(self) -> None:
        errors = self.validate_marker(
            "<!-- decision-status "
            f"id={self.decision_id} status=pending ref=none owner=architecture -->"
        )

        self.assertTrue(any("unknown attribute" in error for error in errors), errors)

    def test_rejects_unparsed_marker_content(self) -> None:
        errors = self.validate_marker(
            "<!-- decision-status "
            f"id={self.decision_id} status=pending ref=none malformed -->"
        )

        self.assertTrue(any("malformed attribute" in error for error in errors), errors)

    def test_ignores_a_decision_marker_inside_a_fenced_example(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            document = Path(directory) / "example.md"
            document.write_text(
                "```markdown\n"
                "<!-- decision-status "
                f"id={self.decision_id} status=accepted "
                "ref=docs/adr/0008-membership.md -->\n"
                "```\n",
                encoding="utf-8",
            )

            markers, errors = parse_markers(document)

            self.assertEqual([], errors)
            self.assertEqual([], markers)

    def test_ignores_a_decision_marker_inside_a_block_quote(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            document = Path(directory) / "example.md"
            document.write_text(
                "> <!-- decision-status "
                f"id={self.decision_id} status=accepted "
                "ref=docs/adr/0008-membership.md -->\n",
                encoding="utf-8",
            )

            markers, errors = parse_markers(document)

            self.assertEqual([], errors)
            self.assertEqual([], markers)

    def test_rejects_noncanonical_markers_outside_code_examples(self) -> None:
        marker = (
            f"<!-- decision-status id={self.decision_id} status=pending ref=none -->"
        )
        cases = {
            "inline paragraph": f"policy text {marker}\n",
            "list item": f"- {marker}\n",
            "preformatted html": f"<pre>\n{marker}\n</pre>\n",
            "details html": f"<details>\n{marker}\n</details>\n",
        }

        for label, content in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                document = Path(directory) / "invalid.md"
                document.write_text(content, encoding="utf-8")

                markers, errors = parse_markers(document)

                self.assertEqual([], markers)
                self.assertTrue(
                    any("noncanonical decision-status" in error for error in errors),
                    errors,
                )

    def test_rejects_a_multiline_conflicting_decision_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            relative_path = "docs/decision-context.md"
            self.write_document(
                repository,
                relative_path,
                "<!-- decision-status "
                f"id={self.decision_id} status=pending ref=none -->\n\n"
                "<!-- decision-status\n"
                f"id={self.decision_id}\n"
                "status=accepted\n"
                "ref=docs/adr/0008-membership.md\n"
                "-->\n",
            )

            errors = validate_decision(
                repository,
                self.decision_id,
                (relative_path,),
            )

            self.assertTrue(
                any("expected exactly one marker" in error for error in errors),
                errors,
            )

    def test_rejects_an_unclosed_conflicting_decision_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            relative_path = "docs/decision-context.md"
            self.write_document(
                repository,
                relative_path,
                "<!-- decision-status "
                f"id={self.decision_id} status=pending ref=none -->\n\n"
                "<!-- decision-status\n"
                f"id={self.decision_id}\n"
                "status=accepted\n",
            )

            errors = validate_decision(
                repository,
                self.decision_id,
                (relative_path,),
            )

            self.assertTrue(
                any("malformed decision-status comment" in error for error in errors),
                errors,
            )

    def test_rejects_an_accepted_reference_outside_the_adr_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            reference = "docs/not-an-adr.md"
            self.write_document(
                repository,
                reference,
                f"# Not an ADR\n\nStatus: accepted.\n\nDecision-ID: {self.decision_id}\n",
            )

            errors = validate_reference(
                repository,
                self.decision_id,
                "accepted",
                reference,
            )

            self.assertTrue(
                any("canonical ADR path" in error for error in errors), errors
            )

    def test_rejects_an_unrelated_accepted_adr(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            reference = "docs/adr/0008-membership.md"
            self.write_document(
                repository,
                reference,
                "# Unrelated ADR\n\nStatus: accepted.\n\n"
                "Decision-ID: SOME-OTHER-DECISION\n",
            )

            errors = validate_reference(
                repository,
                self.decision_id,
                "accepted",
                reference,
            )

            self.assertTrue(any("Decision-ID" in error for error in errors), errors)

    def test_accepts_a_matching_accepted_adr(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            reference = "docs/adr/0008-membership.md"
            self.write_document(
                repository,
                reference,
                "# Membership ADR\n\nStatus: accepted.\n\n"
                f"Decision-ID: {self.decision_id}\n",
            )

            self.assertEqual(
                [],
                validate_reference(
                    repository,
                    self.decision_id,
                    "accepted",
                    reference,
                ),
            )

    def test_rejects_adr_metadata_that_only_appears_in_a_fenced_example(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            reference = "docs/adr/0008-membership.md"
            self.write_document(
                repository,
                reference,
                "# Membership ADR\n\n"
                "```text\n"
                "Status: accepted.\n\n"
                f"Decision-ID: {self.decision_id}\n"
                "```\n",
            )

            errors = validate_reference(
                repository,
                self.decision_id,
                "accepted",
                reference,
            )

            self.assertTrue(
                any("not an accepted ADR" in error for error in errors), errors
            )

    def test_registers_readme_as_a_normative_decision_carrier(self) -> None:
        self.assertIn(
            "README.md",
            DECISION_DOCUMENTS[self.decision_id],
        )


if __name__ == "__main__":
    unittest.main()
