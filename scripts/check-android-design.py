"""Check project typography and literal spacing/icon-size rules (no dependencies).

Run: python scripts/check-android-design.py
Inherited M3 text styles, runtime measurements, drawing strokes and shadows are allowed.
This is a source check, not a substitute for accessibility/layout testing on a device.
"""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "androidApp/src/main/kotlin"


def code_only(source: str) -> str:
    # Preserve offsets/line numbers while excluding comments and string contents.
    return re.sub(
        r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"',
        lambda match: re.sub(r"[^\n]", " ", match.group()),
        source,
        flags=re.DOTALL,
    )


def violations(source: str):
    code = code_only(source)
    for match in re.finditer(r"\b(?:fontSize|fontWeight|lineHeight|letterSpacing)\s*=|\b\d+(?:\.\d+)?\.sp\b", code):
        yield match.start(), "Choose a theme typography style instead of overriding text metrics."

    calls = r"\b(?:padding|PaddingValues|spacedBy|size|sizeIn|widthIn|heightIn)\s*\("
    ranges = []
    for match in re.finditer(calls, code):
        depth = 1
        end = match.end()
        while end < len(code) and depth:
            depth += (code[end] == "(") - (code[end] == ")")
            end += 1
        ranges.append((match.end(), end))
    for match in re.finditer(r"\biconSize\s*(?::\s*Dp\s*)?=\s*", code):
        ranges.append((match.end(), code.find("\n", match.end()) if "\n" in code[match.end():] else len(code)))
    # Spacer dimensions are layout gaps, rather than artwork or progress-track heights.
    for match in re.finditer(r"\bSpacer\s*\(", code):
        depth, end = 1, match.end()
        while end < len(code) and depth:
            depth += (code[end] == "(") - (code[end] == ")")
            end += 1
        ranges.append((match.end(), end))

    reported = set()
    for start, end in ranges:
        for match in re.finditer(r"(?<![\w.])(-?\d+(?:\.\d+)?)\.dp\b", code[start:end]):
            value = float(match[1])
            position = start + match.start()
            if value not in (0, 2) and (value < 4 or value % 4 != 0) and position not in reported:
                reported.add(position)
                yield position, f"Use 0, 2 or a positive multiple of 4 dp; found {match[0]}."


def main() -> int:
    failures = 0
    files = sorted(SOURCE.rglob("*.kt"))
    for path in files:
        source = path.read_text(encoding="utf-8")
        for position, message in violations(source):
            line = source.count("\n", 0, position) + 1
            print(f"{path.relative_to(ROOT)}:{line}: {message}")
            failures += 1
    print(f"Android design check: {len(files)} files, {failures} violations.")
    return int(failures > 0)


if __name__ == "__main__":
    raise SystemExit(main())
