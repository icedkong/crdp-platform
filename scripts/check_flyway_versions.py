#!/usr/bin/env python3
"""同一套迁移历史里版本号不能重复。平台库和租户库各算一套。"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TREES = [
    ROOT / "backend/fu-server/src/main/resources/db/platform",
    ROOT / "backend/fu-server/src/main/resources/db/tenant",
]
NAME = re.compile(r"^V(\d+)__.+\.sql$")


def main() -> int:
    failed = False
    for tree in TREES:
        seen: dict[str, Path] = {}
        for path in sorted(tree.rglob("*.sql")):
            match = NAME.match(path.name)
            if not match:
                print(f"脚本名不符合 V{{版本}}__说明.sql: {path}")
                failed = True
                continue
            version = match.group(1)
            if version in seen:
                print(f"版本 V{version} 重复:\n  {seen[version]}\n  {path}")
                failed = True
            else:
                seen[version] = path
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
