#!/usr/bin/env python3
"""Checks the Markdown docs: every relative link points to a file, every anchor to a heading,
and every Mermaid block starts with a known diagram type. Exit code 1 on a problem."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KINDS = ("flowchart", "sequenceDiagram", "stateDiagram-v2", "erDiagram", "classDiagram", "gantt", "pie")
LINK = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)\s]+)\)")
FENCE = re.compile(r"^```")


def slug(heading: str) -> str:
    text = re.sub(r"`", "", heading.strip().lower())
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.replace(" ", "-")


def headings(path: pathlib.Path) -> set[str]:
    found, in_code = set(), False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_code = not in_code
        elif not in_code and line.startswith("#"):
            found.add(slug(line.lstrip("#")))
    return found


def main() -> int:
    problems = []
    files = [p for p in ROOT.rglob("*.md") if "target" not in p.parts and "node_modules" not in p.parts]
    for path in files:
        in_code, fence_kind, first = False, "", False
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            where = f"{path.relative_to(ROOT)}:{number}"
            if FENCE.match(line):
                in_code = not in_code
                fence_kind, first = line.strip()[3:], in_code
                continue
            if in_code:
                if fence_kind == "mermaid" and first and line.strip():
                    first = False
                    if not line.strip().startswith(KINDS):
                        problems.append(f"{where}: a Mermaid block must start with one of {', '.join(KINDS)}")
                continue
            for target in LINK.findall(line):
                if target.startswith(("http://", "https://", "mailto:")):
                    continue
                file_part, _, anchor = target.partition("#")
                dest = path if not file_part else (path.parent / file_part).resolve()
                if not dest.exists():
                    problems.append(f"{where}: link to a missing file: {target}")
                elif anchor and dest.is_file() and dest.suffix == ".md" and anchor not in headings(dest):
                    problems.append(f"{where}: link to a missing heading: {target}")
    for problem in problems:
        print(problem)
    print(f"{len(files)} files checked, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
