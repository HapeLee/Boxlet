"""Export the exact Compose Fluent icon paths for the native WinUI client.

Inputs: fluent-icons core/extended source JARs in the Gradle cache.
Output is checked in; building the Windows client does not require this script.
"""
import json
import os
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1/io.github.compose-fluent"
COMMANDS = {"moveTo": "M", "lineTo": "L", "horizontalLineTo": "H", "verticalLineTo": "V",
            "curveTo": "C", "reflectiveCurveTo": "S", "quadTo": "Q", "reflectiveQuadTo": "T", "arcTo": "A", "close": "Z"}
WANTED = {"ClipboardPaste": "regular", "Document": "regular", "Image": "regular", "TextDescription": "regular",
          "Folder": "regular", "Apps": "regular", "Globe": "regular", "ArrowSync": "filled",
          "Send": "filled", "ArrowDownload": "filled", "History": "filled", "Settings": "filled", "Info": "filled"}
result = {}
for archive in sorted(CACHE.glob("fluent-icons-*-desktop/*/*/*-sources.jar")):
    with zipfile.ZipFile(archive) as source:
        for name, style in WANTED.items():
            if name in result:
                continue
            match = next((entry for entry in source.namelist() if entry.endswith(f"/{style}/{name}.kt")), None)
            if not match:
                continue
            code = source.read(match).decode().split("fluentPath {")[1]
            segments = []
            for operation, arguments in re.findall(r"^\s+(\w+)\(([^)]*)\)", code, re.M):
                relative = operation.endswith("Relative")
                base = operation.removesuffix("Relative")
                if base not in COMMANDS:
                    raise ValueError(f"Unsupported vector operation: {operation}")
                letter = COMMANDS[base].lower() if relative else COMMANDS[base]
                values = arguments.replace("false", "0").replace("true", "1").replace("f", "")
                segments.append(letter + values)
            result[name] = " ".join(segments)
missing = set(WANTED) - set(result)
if missing:
    raise ValueError(f"Missing icons: {missing}; resolve Compose dependencies first.")
destination = ROOT / "windowsApp/Assets/FluentIcons.json"
destination.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(f"Exported {len(result)} shared Fluent paths to {destination}")
