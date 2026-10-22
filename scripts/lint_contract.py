#!/usr/bin/env python3
"""Contract linter for schema/.

Checks, for every feature in schema/features/<id>/SPEC.md:
  1. Every event listed in section 3 ("Eventos") has a schema file events/<tipo>.v<N>.json.
  2. Every schema file is valid JSON Schema (draft 2020-12).
  3. Every fixture under fixtures/<proyección>/<caso>.json:
       - has projection/params/events/expected,
       - projection is "<this feature id>.<proyección dir name>",
       - every event validates against envelope.v1.json (with fixture defaults applied)
         and against events/<type>.v<v>.json.
  4. Every case listed in section 7 exists as a file, and every file is listed.
  5. Consumed events (section 3, "Eventos consumidos") exist and are owned by the named feature.

Exit code 0 = contract is coherent. Any finding prints "ERROR <feature>: ..." and exits 1.
Requires: pip install jsonschema
"""
import json
import re
import sys
from pathlib import Path

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:  # pragma: no cover
    print("ERROR: missing dependency. Run: pip install jsonschema", file=sys.stderr)
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "schema"
EVENTS = SCHEMA / "events"
FEATURES = SCHEMA / "features"

FIXTURE_DEFAULTS = {"user_id": "u1", "device_id": "test"}

errors: list[str] = []


def err(feature: str, msg: str) -> None:
    errors.append(f"ERROR {feature}: {msg}")


def load_json(path: Path):
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def section(md: str, number: int) -> str:
    """Return the body of '## N. ...' up to the next '## '."""
    m = re.search(rf"^## {number}\.[^\n]*\n(.*?)(?=^## |\Z)", md, re.S | re.M)
    return m.group(1) if m else ""


def table_rows(block: str) -> list[list[str]]:
    rows = []
    for line in block.splitlines():
        line = line.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if all(re.fullmatch(r":?-+:?", c) for c in cells if c):
            continue
        rows.append(cells)
    return rows[1:] if rows else []  # drop header


def code(cell: str) -> str:
    m = re.search(r"`([^`]+)`", cell)
    return m.group(1) if m else cell.strip()


def spec_meta(md: str) -> dict:
    meta = {}
    for cells in table_rows(md.split("## 1.")[0]):
        if len(cells) >= 2:
            meta[cells[0]] = code(cells[1])
    return meta


def main() -> int:
    envelope_schema = load_json(SCHEMA / "envelope.v1.json")
    Draft202012Validator.check_schema(envelope_schema)
    envelope = Draft202012Validator(envelope_schema, format_checker=FormatChecker())

    # Load and check every event schema once.
    event_validators: dict[tuple[str, int], Draft202012Validator] = {}
    for path in sorted(EVENTS.glob("*.json")):
        m = re.fullmatch(r"([a-z][a-z0-9_]*)\.v(\d+)\.json", path.name)
        if not m:
            err("schema", f"bad event schema filename {path.name} (expected <tipo>.v<N>.json)")
            continue
        try:
            schema = load_json(path)
            Draft202012Validator.check_schema(schema)
        except Exception as e:  # noqa: BLE001
            err("schema", f"{path.name}: {e}")
            continue
        event_validators[(m.group(1), int(m.group(2)))] = Draft202012Validator(schema, format_checker=FormatChecker())

    owners: dict[str, str] = {}  # event type -> feature id
    specs: dict[str, str] = {}

    for spec_path in sorted(FEATURES.glob("*/SPEC.md")):
        fid = spec_path.parent.name
        if fid.startswith("_"):
            continue
        md = spec_path.read_text(encoding="utf-8")
        specs[fid] = md
        meta = spec_meta(md)
        if meta.get("id") != fid:
            err(fid, f"SPEC id `{meta.get('id')}` does not match directory name")

        # 1. produced events have schemas
        sec3 = section(md, 3)
        produced = sec3.split("### Eventos consumidos")[0]
        for cells in table_rows(produced):
            if len(cells) < 2:
                continue
            etype, ver = code(cells[0]), cells[1]
            if etype.lower() in ("ninguno", "ninguna", "<tipo>"):
                continue
            if not ver.isdigit():
                err(fid, f"event `{etype}` has non-numeric version `{ver}`")
                continue
            if (etype, int(ver)) not in event_validators:
                err(fid, f"event `{etype}` v{ver} listed in SPEC but schema/events/{etype}.v{ver}.json is missing")
            if etype in owners and owners[etype] != fid:
                err(fid, f"event `{etype}` is also produced by `{owners[etype]}`; one owner per event")
            owners[etype] = fid

    # 5. consumed events exist and belong to the named owner
    for fid, md in specs.items():
        sec3 = section(md, 3)
        if "### Eventos consumidos" not in sec3:
            continue
        for cells in table_rows(sec3.split("### Eventos consumidos")[1]):
            if len(cells) < 2:
                continue
            etype, owner = code(cells[0]), code(cells[1])
            if etype.lower() in ("ninguno", "ninguna"):
                continue
            if etype not in owners:
                err(fid, f"consumes `{etype}` but no feature produces it")
            elif owners[etype] != owner:
                err(fid, f"consumes `{etype}` from `{owner}` but it is owned by `{owners[etype]}`")

    # 3 + 4. fixtures
    for fid, md in specs.items():
        meta = spec_meta(md)
        if meta.get("estado") == "eliminado":
            continue
        fixtures_dir = FEATURES / fid / "fixtures"
        listed: set[str] = set()
        projections: dict[str, str] = {}  # P1 -> name, from section 4 headings
        for m in re.finditer(r"^### (P\d+)\. `([^`]+)`", section(md, 4), re.M):
            projections[m.group(1)] = m.group(2)
        for cells in table_rows(section(md, 7)):
            if len(cells) < 2:
                continue
            pkey, case = cells[0].strip(), code(cells[1])
            if case.startswith("caso_01_<"):
                continue
            pname = projections.get(pkey)
            if not pname:
                err(fid, f"fixture `{case}` references `{pkey}` which is not defined in section 4")
                continue
            listed.add(f"{pname}/{case}")
            if not (fixtures_dir / pname / f"{case}.json").exists():
                err(fid, f"fixture listed in SPEC but file missing: fixtures/{pname}/{case}.json")

        found: set[str] = set()
        for fx in sorted(fixtures_dir.glob("*/*.json")) if fixtures_dir.exists() else []:
            rel = f"{fx.parent.name}/{fx.stem}"
            found.add(rel)
            try:
                data = load_json(fx)
            except Exception as e:  # noqa: BLE001
                err(fid, f"fixtures/{rel}.json is not valid JSON: {e}")
                continue
            for key in ("projection", "params", "events", "expected"):
                if key not in data:
                    err(fid, f"fixtures/{rel}.json missing key `{key}`")
            want = f"{fid}.{fx.parent.name}"
            if data.get("projection") != want:
                err(fid, f"fixtures/{rel}.json projection is `{data.get('projection')}`, expected `{want}` (from directory name)")
            seen_ids: set[str] = set()
            for i, ev in enumerate(data.get("events", [])):
                ev = {**FIXTURE_DEFAULTS, **ev}
                for e in envelope.iter_errors(ev):
                    err(fid, f"fixtures/{rel}.json events[{i}] envelope: {e.message}")
                if ev.get("id") in seen_ids:
                    err(fid, f"fixtures/{rel}.json events[{i}] duplicate id `{ev.get('id')}`")
                seen_ids.add(ev.get("id"))
                key = (ev.get("type"), ev.get("v"))
                v = event_validators.get(key)
                if v is None:
                    err(fid, f"fixtures/{rel}.json events[{i}] type `{key[0]}` v{key[1]} has no schema")
                    continue
                for e in v.iter_errors(ev.get("payload", {})):
                    path = "/".join(str(p) for p in e.absolute_path) or "<root>"
                    err(fid, f"fixtures/{rel}.json events[{i}] ({key[0]}) payload.{path}: {e.message}")
        for rel in sorted(found - listed):
            err(fid, f"fixture file exists but is not listed in SPEC section 7: fixtures/{rel}.json")

    if errors:
        print("\n".join(errors))
        print(f"\n{len(errors)} error(s).")
        return 1
    n_specs = len(specs)
    n_fx = sum(1 for _ in FEATURES.glob("*/fixtures/*/*.json"))
    print(f"contract ok: {n_specs} feature(s), {len(event_validators)} event schema(s), {n_fx} fixture(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
