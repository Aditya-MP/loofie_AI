"""One command for everything:  loofie <command> ...   (same as: python -m app <command> ...)

loofie import sheet ../data/Loofie_AI_Data_Collection.xlsx
loofie profile build
loofie quality assess
loofie matcher match "gel sunscreen for oily skin" --category beauty_skincare
loofie matcher evaluate
loofie brief parse "need 3 girls blr, 15k total, by 5th dec"
loofie brief evaluate
loofie check ../data/Loofie_AI_Data_Collection.xlsx    # all of the above on the team's data, in order
loofie serve                                            # the API for the backend
"""

import importlib
import sys

COMMANDS = {
    "import": ("app.importer.__main__", "load the data team's sheet, or the old field CSV"),
    "profile": ("app.profile.__main__", "build creator profiles on the GPU"),
    "quality": ("app.quality.__main__", "Model 3: label creators real / unsure / suspicious"),
    "matcher": ("app.matcher.__main__", "Model 2: match a brief, or run the brand-hire test"),
    "brief": ("app.brief.__main__", "Model 1: parse a brief, or score against the Briefs tab"),
}


def _run(module: str, args: list[str]) -> int:
    sys.argv = [f"loofie {args[0] if args else ''}".strip(), *args]
    return importlib.import_module(module).main() or 0


def check(sheet: str) -> int:
    """The full real-data test: stops at the first step that fails."""
    steps = [
        ("import", ["sheet", sheet]),
        ("profile", ["build"]),
        ("quality", ["assess"]),
        ("matcher", ["evaluate"]),
        ("brief", ["evaluate"]),
    ]
    for name, args in steps:
        print(f"\n{'=' * 70}\nloofie {name} {' '.join(args)}\n{'=' * 70}", flush=True)
        code = _run(COMMANDS[name][0], args)
        if code:
            print(f"\nStopped: `loofie {name}` failed (exit {code}).")
            return code
    return 0


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252
    if len(sys.argv) < 2 or sys.argv[1] in {"-h", "--help", "help"}:
        print(__doc__)
        for name, (_, about) in COMMANDS.items():
            print(f"  {name:9} {about}")
        print(f"  {'check':9} run every step on the team's sheet")
        print(f"  {'serve':9} start the API (port 8100)")
        return 0
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "check":
        if len(args) != 1:
            print("usage: loofie check PATH_TO_SHEET")
            return 2
        return check(args[0])
    if cmd == "serve":
        import uvicorn

        uvicorn.run("app.api.main:app", host="127.0.0.1", port=8100)
        return 0
    if cmd not in COMMANDS:
        print(f"unknown command {cmd!r}. Run `loofie help`.")
        return 2
    return _run(COMMANDS[cmd][0], args)


if __name__ == "__main__":
    sys.exit(main())
