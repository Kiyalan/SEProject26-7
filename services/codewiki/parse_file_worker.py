"""Parse a single source file in an isolated process (survives SIGSEGV).

Usage:
  python parse_file_worker.py <abs_file> <repo_root> <language> <out_pickle>
"""
from __future__ import annotations

import os
import pickle
import sys
from pathlib import Path

os.environ.setdefault("CODEWIKI_AST_PARSE_WORKERS", "1")


def main() -> int:
    if len(sys.argv) != 5:
        print(
            "usage: parse_file_worker.py <abs_file> <repo_root> <language> <out_pickle>",
            file=sys.stderr,
        )
        return 2
    abs_file, repo_root, language, out_pickle = sys.argv[1:5]
    try:
        from backend.app.services.ast_parser import AstParser

        parser = AstParser()
        symbols = parser.parse_file(
            Path(abs_file),
            repo_root=Path(repo_root),
            language=language or None,
        )
        with open(out_pickle, "wb") as fh:
            pickle.dump(("ok", symbols), fh, protocol=pickle.HIGHEST_PROTOCOL)
        return 0
    except Exception as exc:
        try:
            with open(out_pickle, "wb") as fh:
                pickle.dump(("err", str(exc)), fh, protocol=pickle.HIGHEST_PROTOCOL)
        except Exception:
            pass
        print(f"parse_file_worker error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
