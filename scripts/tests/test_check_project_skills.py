from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from check_project_skills import validate_repository  # noqa: E402


class ProjectSkillValidationTest(unittest.TestCase):
    def create_skill(
        self,
        repository: Path,
        *,
        folder_name: str = "sample-skill",
        frontmatter_name: str = "sample-skill",
        default_prompt: str = "Use $sample-skill to execute a governed task.",
        reference_target: str = "references/rules.md",
    ) -> Path:
        skill = repository / ".agents" / "skills" / folder_name
        (skill / "agents").mkdir(parents=True)
        (skill / "references").mkdir()
        (skill / "SKILL.md").write_text(
            "\n".join(
                (
                    "---",
                    f'name: "{frontmatter_name}"',
                    'description: "Execute a governed sample workflow when validation is required."',
                    "---",
                    "",
                    "# Sample Skill",
                    "",
                    f"Read [the rules]({reference_target}).",
                    "",
                )
            ),
            encoding="utf-8",
        )
        (skill / "references" / "rules.md").write_text(
            "# Rules\n",
            encoding="utf-8",
        )
        (skill / "agents" / "openai.yaml").write_text(
            "\n".join(
                (
                    "interface:",
                    '  display_name: "Sample Skill"',
                    '  short_description: "Validate a governed sample workflow"',
                    f'  default_prompt: "{default_prompt}"',
                    "",
                    "policy:",
                    "  allow_implicit_invocation: true",
                    "",
                )
            ),
            encoding="utf-8",
        )
        return skill

    def test_accepts_a_complete_project_skill(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.create_skill(repository)

            self.assertEqual([], validate_repository(repository))

    def test_rejects_a_frontmatter_name_that_differs_from_folder(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.create_skill(repository, frontmatter_name="different-name")

            errors = validate_repository(repository)

            self.assertTrue(
                any("must match its folder" in error for error in errors),
                errors,
            )

    def test_rejects_a_missing_local_reference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.create_skill(repository, reference_target="references/missing.md")

            errors = validate_repository(repository)

            self.assertTrue(
                any("missing local link target" in error for error in errors),
                errors,
            )

    def test_rejects_default_prompt_without_the_skill_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.create_skill(
                repository,
                default_prompt="Execute a governed task.",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("must mention $sample-skill" in error for error in errors),
                errors,
            )

    def test_rejects_unquoted_openai_interface_strings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            skill = self.create_skill(repository)
            metadata = skill / "agents" / "openai.yaml"
            metadata.write_text(
                metadata.read_text(encoding="utf-8").replace(
                    'display_name: "Sample Skill"',
                    "display_name: Sample Skill",
                ),
                encoding="utf-8",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any(
                    "interface strings must be double-quoted" in error
                    for error in errors
                ),
                errors,
            )

    def test_rejects_an_empty_skill_body(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            skill = self.create_skill(repository)
            (skill / "SKILL.md").write_text(
                "\n".join(
                    (
                        "---",
                        'name: "sample-skill"',
                        'description: "Execute a governed sample workflow."',
                        "---",
                        "",
                    )
                ),
                encoding="utf-8",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("skill body must not be empty" in error for error in errors),
                errors,
            )

    def test_rejects_an_ambiguous_plain_yaml_scalar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            skill = self.create_skill(repository)
            skill_file = skill / "SKILL.md"
            skill_file.write_text(
                skill_file.read_text(encoding="utf-8").replace(
                    'description: "Execute a governed sample workflow when validation is required."',
                    "description: foo: bar",
                ),
                encoding="utf-8",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("ambiguous plain YAML scalar" in error for error in errors),
                errors,
            )

    def test_rejects_an_unquoted_numeric_description(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            skill = self.create_skill(repository)
            skill_file = skill / "SKILL.md"
            skill_file.write_text(
                skill_file.read_text(encoding="utf-8").replace(
                    'description: "Execute a governed sample workflow when validation is required."',
                    "description: 123",
                ),
                encoding="utf-8",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("must be a double-quoted string" in error for error in errors),
                errors,
            )

    def test_rejects_an_unquoted_numeric_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            skill = self.create_skill(
                repository,
                folder_name="123",
                frontmatter_name="123",
                default_prompt="Use $123 to execute a governed task.",
            )
            skill_file = skill / "SKILL.md"
            skill_file.write_text(
                skill_file.read_text(encoding="utf-8").replace(
                    'name: "123"',
                    "name: 123",
                ),
                encoding="utf-8",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("must be a double-quoted string" in error for error in errors),
                errors,
            )

    def test_rejects_an_unquoted_date_like_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            skill = self.create_skill(
                repository,
                folder_name="2026-07-21",
                frontmatter_name="2026-07-21",
                default_prompt="Use $2026-07-21 to execute a governed task.",
            )
            skill_file = skill / "SKILL.md"
            skill_file.write_text(
                skill_file.read_text(encoding="utf-8").replace(
                    'name: "2026-07-21"',
                    "name: 2026-07-21",
                ),
                encoding="utf-8",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("must be a double-quoted string" in error for error in errors),
                errors,
            )

    def test_rejects_default_prompt_with_only_a_longer_skill_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.create_skill(
                repository,
                default_prompt="Use $sample-skill-extra to execute a governed task.",
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any("must mention $sample-skill" in error for error in errors),
                errors,
            )

    def test_rejects_a_skill_directory_symlink(self) -> None:
        with (
            tempfile.TemporaryDirectory() as repository_directory,
            tempfile.TemporaryDirectory() as external_directory,
        ):
            repository = Path(repository_directory)
            external_repository = Path(external_directory)
            external_skill = self.create_skill(external_repository)
            skills_root = repository / ".agents" / "skills"
            skills_root.mkdir(parents=True)
            (skills_root / "sample-skill").symlink_to(
                external_skill,
                target_is_directory=True,
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any(
                    "skill directory must not be a symbolic link" in error
                    for error in errors
                ),
                errors,
            )

    def test_rejects_a_nested_symlink_without_reading_its_target(self) -> None:
        with (
            tempfile.TemporaryDirectory() as repository_directory,
            tempfile.TemporaryDirectory() as external_directory,
        ):
            repository = Path(repository_directory)
            skill = self.create_skill(repository)
            external_file = Path(external_directory) / "secret.txt"
            external_file.write_text(
                "SECRET_SENTINEL=do-not-print\n",
                encoding="utf-8",
            )
            metadata = skill / "agents" / "openai.yaml"
            metadata.unlink()
            metadata.symlink_to(external_file)

            errors = validate_repository(repository)

            self.assertTrue(
                any("must not contain symbolic links" in error for error in errors),
                errors,
            )
            self.assertNotIn("SECRET_SENTINEL", "\n".join(errors))

    def test_rejects_a_skills_root_symlink(self) -> None:
        with (
            tempfile.TemporaryDirectory() as repository_directory,
            tempfile.TemporaryDirectory() as external_directory,
        ):
            repository = Path(repository_directory)
            external_repository = Path(external_directory)
            self.create_skill(external_repository)
            agents_root = repository / ".agents"
            agents_root.mkdir()
            (agents_root / "skills").symlink_to(
                external_repository / ".agents" / "skills",
                target_is_directory=True,
            )

            errors = validate_repository(repository)

            self.assertTrue(
                any(
                    "skills directory must not be a symbolic link" in error
                    for error in errors
                ),
                errors,
            )


if __name__ == "__main__":
    unittest.main()
