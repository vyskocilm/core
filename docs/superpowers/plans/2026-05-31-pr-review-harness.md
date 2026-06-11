# PR Review Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `review-pr` Claude Code skill — a hybrid harness whose deterministic core (diff parsing, router, schema validation, synthesis/dedup, anchor resolution, render, post-payload) is tested Python, and whose judgment work (lens reviews + adversarial verify) is markdown-driven subagent fan-out — that reviews the current branch or a given PR and optionally posts one batched PR review.

**Architecture:** A markdown `SKILL.md` orchestrates the spec's 7-step pipeline. The mechanical, deterministic steps (steps 2, 4, 6, 7 and diff acquisition) are implemented in a small Python package, `harness/`, exposed through one CLI with subcommands; the orchestrator shells out to it. The judgment steps (step 3 lens fan-out, step 5 verify) are Claude subagents driven by prompts in `references/lenses.md`, exchanging Finding JSON validated by the Python `schema` module. Golden-diff fixtures drive a real `pytest` suite for the deterministic core and an agentic self-test for lens recall.

**Tech Stack:** Python 3.14 (stdlib only — no third-party deps; hand-rolled JSON-schema validation to keep the skill dependency-free), `argparse` CLI, `git` + `gh` CLI (subprocess), `pytest` for the deterministic core, Claude Code subagent fan-out (`superpowers:dispatching-parallel-agents`), Markdown skill at `~/.claude/skills/review-pr/`.

**Spec:** `docs/superpowers/specs/2026-05-31-pr-review-harness-design.md` (Approach B).

---

## File structure (final layout)

```
~/.claude/skills/review-pr/
  SKILL.md                  # orchestrator: frontmatter, invocation, 7-step pipeline,
                            #   shells out to `python3 -m harness ...`, dispatches subagents
  harness/
    __init__.py             # package marker; exposes nothing (CLI is __main__)
    __main__.py             # argparse CLI: acquire|route|validate|synth|render|post subcommands
    diffmodel.py            # parse unified diff → DiffModel (hunks, commentable lines, position maps)
    schema.py               # Finding JSON Schema + dependency-free validator (incl. blocking⇒major)
    router.py               # changed-files + diff → active lens set + rationale; effort/lenses overrides
    synthesis.py            # merge + subject-dedup (normalized-claim) + diff-scope filter
    anchor.py               # anchor fallback chain → commentable GitHub position (LEFT/RIGHT/file/body)
    render.py               # findings → rubric Markdown report + report-filename logic
    post.py                 # build batched review payload; submit via gh, or dry-run
    acquire.py              # branch/PR diff acquisition (git/gh subprocess)
  references/
    lenses.md               # lens prompt library: 3 always-on + 7 conditional
    testing.md              # agentic self-test (lens recall, verify-keeps-true-positive)
  tests/
    __init__.py
    conftest.py             # fixture loader: reads fixtures/<name>/{diff.patch,changed-files.txt}
    test_diffmodel.py
    test_schema.py
    test_router.py
    test_synthesis.py
    test_anchor.py
    test_render.py
    test_post.py
    fixtures/               # golden diffs shared by pytest AND the agentic recall test
      transaction-http/     {diff.patch, changed-files.txt, expected.md}
      security-getmessage/  {diff.patch, changed-files.txt, expected.md}
      callsite-orphan/      {diff.patch, changed-files.txt, expected.md}
      persistence-nullable/ {diff.patch, changed-files.txt, expected.md}
      maintainability-javadoc/ {diff.patch, changed-files.txt, expected.md}
      clean/                {diff.patch, changed-files.txt, expected.md}
      dedup-twolens/        {diff.patch, changed-files.txt, expected.md}
      post-anchor/          {diff.patch, changed-files.txt, expected.md}
```

Runtime reports go to the **target repo's** `docs/superpowers/reviews/`, not under the skill dir.

**Run tests:** `cd ~/.claude/skills/review-pr && .venv/bin/python -m pytest tests/ -q`
(a `.venv` with pytest already exists under the skill dir; the harness runtime itself uses system `python3` and stays stdlib-only)
**Run CLI:** `cd ~/.claude/skills/review-pr && python3 -m harness <subcommand> ...`

> **Commit policy:** the skill lives under `~/.claude/` (outside this repo). CLAUDE.local.md says don't commit without being asked. "Commit" steps below are checkpoint markers; the only in-repo artifact is this plan. If the user later wants version control / CI for the Python core, mirror `harness/` + `tests/` into the repo's `.claude/skills/review-pr/`.

---

## Task 1: Package scaffold + diff model

**Files:**
- Create: `~/.claude/skills/review-pr/harness/__init__.py` (empty)
- Create: `~/.claude/skills/review-pr/tests/__init__.py` (empty)
- Create: `~/.claude/skills/review-pr/harness/diffmodel.py`
- Test: `~/.claude/skills/review-pr/tests/test_diffmodel.py`

Everything downstream (router grep, scope filter, anchor positions, post payload) depends on a correct diff parse, so build it first.

- [ ] **Step 1: Write the failing test**

```python
# tests/test_diffmodel.py
from harness.diffmodel import parse_diff

ADD_AND_DELETE = """diff --git a/src/Foo.java b/src/Foo.java
--- a/src/Foo.java
+++ b/src/Foo.java
@@ -10,4 +10,5 @@ class Foo {
 context0
-removedOld
+addedNew1
+addedNew2
 context1
"""

NEW_FILE = """diff --git a/src/New.java b/src/New.java
new file mode 100644
--- /dev/null
+++ b/src/New.java
@@ -0,0 +1,2 @@
+line one
+line two
"""

def test_changed_files_lists_both_paths():
    m = parse_diff(ADD_AND_DELETE + NEW_FILE)
    assert m.changed_files() == ["src/Foo.java", "src/New.java"]

def test_commentable_right_includes_added_and_context():
    m = parse_diff(ADD_AND_DELETE)
    # new-file numbering: 10 context0, 11 addedNew1, 12 addedNew2, 13 context1
    assert m.commentable_right("src/Foo.java") == {10, 11, 12, 13}
    assert m.added_right("src/Foo.java") == {11, 12}

def test_commentable_left_includes_deleted_and_context():
    m = parse_diff(ADD_AND_DELETE)
    # old-file numbering: 10 context0, 11 removedOld, 12 context1
    assert m.commentable_left("src/Foo.java") == {10, 11, 12}
    assert m.deleted_left("src/Foo.java") == {11}

def test_new_file_flagged_and_only_right_side():
    m = parse_diff(NEW_FILE)
    assert m.is_new("src/New.java") is True
    assert m.commentable_right("src/New.java") == {1, 2}
    assert m.commentable_left("src/New.java") == set()

def test_touches_true_for_added_line_false_for_unchanged():
    m = parse_diff(ADD_AND_DELETE)
    assert m.touches("src/Foo.java", 11) is True       # added
    assert m.touches("src/Foo.java", 99) is False      # outside any hunk
    assert m.touches("src/Other.java", 1) is False     # file not in diff
    assert m.touches("src/Foo.java", None) is True      # file-level on a changed file

def test_nearest_changed_right_line():
    m = parse_diff(ADD_AND_DELETE)
    assert m.nearest_right("src/Foo.java", 50) == 12    # closest added line
    assert m.nearest_right("src/Other.java", 1) is None

def test_grep_text_excludes_diff_headers():
    m = parse_diff(ADD_AND_DELETE)
    body = m.grep_text()
    assert "addedNew1" in body and "removedOld" in body and "context0" in body
    assert "diff --git" not in body and "+++ b/src/Foo.java" not in body
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd ~/.claude/skills/review-pr && python3 -m pytest tests/test_diffmodel.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'harness.diffmodel'`.

- [ ] **Step 3: Write the implementation**

```python
# harness/diffmodel.py
"""Parse a unified diff (git diff / gh pr diff) into a queryable model.

The model answers the questions the rest of the harness needs: which files
changed, which (file, line, side) positions are commentable, which lines the
diff actually touches (for the scope filter), and a header-stripped text body
for the router's token grep.
"""
import re
from dataclasses import dataclass, field

_HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


@dataclass
class FileDiff:
    path: str
    is_new: bool = False
    is_deleted: bool = False
    added_right: set = field(default_factory=set)     # new-file line nums of '+' lines
    context_right: set = field(default_factory=set)   # new-file line nums of ' ' lines
    deleted_left: set = field(default_factory=set)     # old-file line nums of '-' lines
    context_left: set = field(default_factory=set)     # old-file line nums of ' ' lines
    body_lines: list = field(default_factory=list)     # content of +/-/space lines (no headers)


class DiffModel:
    def __init__(self, files):
        self._files = files  # dict path -> FileDiff

    def changed_files(self):
        return list(self._files.keys())

    def _f(self, path):
        return self._files.get(path)

    def is_new(self, path):
        f = self._f(path)
        return bool(f and f.is_new)

    def added_right(self, path):
        f = self._f(path)
        return set(f.added_right) if f else set()

    def deleted_left(self, path):
        f = self._f(path)
        return set(f.deleted_left) if f else set()

    def commentable_right(self, path):
        f = self._f(path)
        return (f.added_right | f.context_right) if f else set()

    def commentable_left(self, path):
        f = self._f(path)
        return (f.deleted_left | f.context_left) if f else set()

    def touches(self, path, line):
        f = self._f(path)
        if not f:
            return False
        if line is None:
            return True
        return line in f.added_right

    def nearest_right(self, path, line):
        cands = self.added_right(path) or self.commentable_right(path)
        if not cands:
            return None
        return min(cands, key=lambda n: (abs(n - line), n))

    def grep_text(self):
        out = []
        for f in self._files.values():
            out.extend(f.body_lines)
        return "\n".join(out)


def parse_diff(text):
    files = {}
    cur = None
    old_ln = new_ln = 0
    for raw in text.splitlines():
        if raw.startswith("diff --git "):
            cur = None
            continue
        if raw.startswith("--- "):
            continue
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            if target == "/dev/null":
                continue  # deletion handled when we know the path from ---; rare, keep simple
            path = target[2:] if target.startswith("b/") else target
            cur = files.setdefault(path, FileDiff(path=path))
            continue
        if raw.startswith("new file"):
            if cur:
                cur.is_new = True
            continue
        m = _HUNK.match(raw)
        if m:
            old_ln = int(m.group(1))
            new_ln = int(m.group(3))
            continue
        if cur is None:
            continue
        if raw.startswith("+"):
            cur.added_right.add(new_ln)
            cur.body_lines.append(raw[1:])
            new_ln += 1
        elif raw.startswith("-"):
            cur.deleted_left.add(old_ln)
            cur.body_lines.append(raw[1:])
            old_ln += 1
        elif raw.startswith(" "):
            cur.context_right.add(new_ln)
            cur.context_left.add(old_ln)
            cur.body_lines.append(raw[1:])
            old_ln += 1
            new_ln += 1
        # '\ No newline at end of file' and blank → ignore
    # a brand-new file has the +++ before "new file"? git emits "new file mode" before ---,
    # so cur is set after +++; mark new files by their /dev/null --- side:
    return DiffModel(files)
```

> Note on new-file detection: git emits `new file mode 100644` *before* the `---`/`+++`
> lines, so `cur` is still None when "new file" is seen. Fix by also marking new when the
> `---` line is `/dev/null`. Implement that in Step 3 by tracking the last `---` target:

Refine the `--- ` and `new file` handling so new files are detected via `--- /dev/null`:

```python
        if raw.startswith("--- "):
            cur_old_is_devnull = raw[4:].strip() == "/dev/null"
            continue
        ...
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            path = target[2:] if target.startswith("b/") else target
            cur = files.setdefault(path, FileDiff(path=path))
            if cur_old_is_devnull:
                cur.is_new = True
            cur_old_is_devnull = False
            continue
```

(Initialize `cur_old_is_devnull = False` before the loop and drop the separate
`new file` branch.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd ~/.claude/skills/review-pr && python3 -m pytest tests/test_diffmodel.py -q`
Expected: PASS (7 passed).

- [ ] **Step 5: Commit (checkpoint marker — see commit policy)**

---

## Task 2: Golden-diff fixtures

**Files (each a dir under `tests/fixtures/` with `diff.patch`, `changed-files.txt`, `expected.md`):**
`transaction-http/`, `security-getmessage/`, `callsite-orphan/`, `persistence-nullable/`, `maintainability-javadoc/`, `clean/`, `dedup-twolens/`, `post-anchor/`
- Create: `~/.claude/skills/review-pr/tests/conftest.py`

These feed both the deterministic pytest suite (router/synthesis/anchor/post tests load them) and the agentic recall test. Build them before the modules that consume them.

- [ ] **Step 1: Write conftest.py fixture loader**

```python
# tests/conftest.py
import pathlib
import pytest

FIXROOT = pathlib.Path(__file__).parent / "fixtures"

def load(name):
    d = FIXROOT / name
    return {
        "diff": (d / "diff.patch").read_text(),
        "files": (d / "changed-files.txt").read_text().split(),
        "dir": d,
    }

@pytest.fixture
def fixtures():
    return load
```

- [ ] **Step 2: Create the 5 recall fixtures (spec §9)**

For each: `diff.patch` plants exactly one issue; `changed-files.txt` lists the paths the router sees; `expected.md` records the expected router lens set + the must-appear finding + verify outcome. Concrete content:

**`transaction-http/`** — `changed-files.txt`: `src/main/java/com/czertainly/core/service/impl/FooService.java`
`diff.patch`:
```
diff --git a/src/main/java/com/czertainly/core/service/impl/FooService.java b/src/main/java/com/czertainly/core/service/impl/FooService.java
--- a/src/main/java/com/czertainly/core/service/impl/FooService.java
+++ b/src/main/java/com/czertainly/core/service/impl/FooService.java
@@ -40,3 +40,9 @@ public class FooService {
     }
+
+    @Transactional
+    public void syncFoo(String url) {
+        Foo foo = repo.findById(1L).orElseThrow();
+        ResponseEntity<String> r = restTemplate.getForEntity(url, String.class);
+        foo.setState(r.getBody());
+    }
 }
```
`expected.md`:
```
## Expected router lenses
- Correctness & races (always-on)
- Maintainability & tests (always-on)
- Call-site / dead-code (always-on)
- Transaction & concurrency (fired by: hunk matches @Transactional)
## Expected findings (must appear)
- Transaction & concurrency | Correctness | Major | FooService.java:42 (tx held across HTTP call)
## Expected verify outcome
- kept (true positive)
```

**`security-getmessage/`** — `changed-files.txt`: `src/main/java/com/czertainly/core/api/cmp/CmpController.java`
`diff.patch`:
```
diff --git a/src/main/java/com/czertainly/core/api/cmp/CmpController.java b/src/main/java/com/czertainly/core/api/cmp/CmpController.java
--- a/src/main/java/com/czertainly/core/api/cmp/CmpController.java
+++ b/src/main/java/com/czertainly/core/api/cmp/CmpController.java
@@ -22,3 +22,7 @@ public class CmpController {
         } catch (Exception e) {
-            return PkiMessage.ok();
+            return PkiMessage.error(new PKIFreeText(e.getMessage()));
         }
     }
```
`expected.md`: router = always-on + Security & info-leak (hunk matches `getMessage()` and `PKIFreeText`). Finding: `Security & info-leak | Security | Major | CmpController.java:25`. Verify: kept.

**`callsite-orphan/`** — `changed-files.txt`: `src/main/java/com/czertainly/core/service/impl/BarService.java`
`diff.patch`: adds a new `public void unusedExport()` method (no caller anywhere).
`expected.md`: router = always-on only (no conditional trigger). Finding: `Call-site / dead-code | Maintainability | Minor | BarService.java:<decl line>`, `type: missing-caller`, anchor on the declaration line RIGHT. Verify: kept.

**`persistence-nullable/`** — `changed-files.txt`: `src/main/java/com/czertainly/core/dao/entity/SigningProfileVersion.java`
`diff.patch`:
```
diff --git a/src/main/java/com/czertainly/core/dao/entity/SigningProfileVersion.java b/src/main/java/com/czertainly/core/dao/entity/SigningProfileVersion.java
--- a/src/main/java/com/czertainly/core/dao/entity/SigningProfileVersion.java
+++ b/src/main/java/com/czertainly/core/dao/entity/SigningProfileVersion.java
@@ -30,2 +30,4 @@ public class SigningProfileVersion {
+    @Column(name = "version", nullable = false)
+    private Integer version;
 }
```
`expected.md`: router = always-on + Persistence & schema (path `dao/entity/**` and hunk `@Column`/`nullable`). Finding: `Persistence & schema | Correctness | Major | SigningProfileVersion.java:31` (boxed Integer for NOT NULL column). Verify: kept.

**`maintainability-javadoc/`** — `changed-files.txt`: `src/main/java/com/czertainly/core/util/CertUtil.java`
`diff.patch`: adds a non-trivial `public static boolean isChainValid(...)` (loops/branches) with NO Javadoc.
`expected.md`: router = always-on only. Finding: `Maintainability & tests | Documentation | Minor | CertUtil.java:<decl line>`. Verify: kept.

- [ ] **Step 3: Create the 3 risk fixtures (spec §9.4 + §10.2)**

**`clean/`** — `changed-files.txt`: `src/main/java/com/czertainly/core/util/StringUtil.java`. `diff.patch`: a typo fix inside a comment only (no logic change). `expected.md`: router = always-on; **findings: NONE**; expected report `Code review: 0 issue(s) found.` and successful.

**`dedup-twolens/`** — `changed-files.txt`: a `*Service.java` whose diff **deletes** the only call to `helperX()` (leaving it dead) — so both Correctness and Call-site lenses can flag the same subject (`helperX`) at different anchors. `expected.md`: after synthesis, **exactly one** finding for subject `helperX` survives (subject-dedup collapses the two). Documents both lenses' raw findings (same subject, different anchors) as input.

**`post-anchor/`** — `changed-files.txt`: a `*Service.java`. `diff.patch` **deletes** the sole caller line of `legacyMethod()`:
```
diff --git a/src/main/java/com/czertainly/core/service/impl/BazService.java b/src/main/java/com/czertainly/core/service/impl/BazService.java
--- a/src/main/java/com/czertainly/core/service/impl/BazService.java
+++ b/src/main/java/com/czertainly/core/service/impl/BazService.java
@@ -55,4 +55,3 @@ public class BazService {
     public void run() {
-        legacyMethod();
         doOther();
     }
```
`expected.md`: a `dead-code` finding whose **subject** is `legacyMethod` (out of diff) and whose **anchor** is the deleted caller line (old-file line 56), `side: LEFT`. Expected `--post` payload (dry-run): one inline comment at `BazService.java` line 56 `side LEFT`, **not** a body bullet.

- [ ] **Step 4: Content check**

8 fixtures present, each with the 3 files. The 5 recall fixtures map 1:1 to the 5 §9 lenses. clean (§9.4), dedup-twolens (§10.2 dedup), post-anchor (§10.2 LEFT re-anchor) cover the risk paths. (§10.2 error-handling is exercised by the agentic test forcing a lens failure — Task 12 — not a diff fixture.) Each `expected.md` states router set + must-appear findings + verify outcome.

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 3: Schema module

**Files:**
- Create: `~/.claude/skills/review-pr/harness/schema.py`
- Test: `~/.claude/skills/review-pr/tests/test_schema.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_schema.py
from harness.schema import validate_finding, FINDING_SCHEMA

def base():
    return {
        "severity": "major", "dimension": "Correctness",
        "file": "src/Foo.java", "line": 12, "title": "x", "body": "see src/Foo.java:12",
        "type": "in-diff",
        "anchor": {"file": "src/Foo.java", "line": 12, "side": "RIGHT"},
        "blocking": False,
    }

def test_valid_finding_passes():
    ok, errs = validate_finding(base())
    assert ok and errs == []

def test_bad_severity_enum_fails():
    f = base(); f["severity"] = "critical"
    ok, errs = validate_finding(f)
    assert not ok and any("severity" in e for e in errs)

def test_blocking_requires_major():
    f = base(); f["severity"] = "minor"; f["blocking"] = True
    ok, errs = validate_finding(f)
    assert not ok and any("blocking" in e for e in errs)

def test_null_line_allowed():
    f = base(); f["line"] = None
    ok, errs = validate_finding(f)
    assert ok

def test_null_anchor_allowed():
    f = base(); f["anchor"] = None
    ok, errs = validate_finding(f)
    assert ok

def test_missing_required_field_fails():
    f = base(); del f["dimension"]
    ok, errs = validate_finding(f)
    assert not ok and any("dimension" in e for e in errs)

def test_bad_anchor_side_fails():
    f = base(); f["anchor"]["side"] = "MIDDLE"
    ok, errs = validate_finding(f)
    assert not ok and any("side" in e for e in errs)

def test_schema_object_has_all_enums():
    props = FINDING_SCHEMA["properties"]["findings"]["items"]["properties"]
    assert set(props["severity"]["enum"]) == {"major","minor","nitpick"}
    assert set(props["type"]["enum"]) == {"in-diff","missing-caller","dead-code","follow-up"}
```

- [ ] **Step 2: Run to verify it fails**

Run: `python3 -m pytest tests/test_schema.py -q` → FAIL (`No module named 'harness.schema'`).

- [ ] **Step 3: Write the implementation**

```python
# harness/schema.py
"""Finding JSON Schema + a dependency-free validator.

We hand-roll validation (no jsonschema dep) because the skill must run with
stdlib only. The schema dict is reproduced verbatim for handing to subagents as
structured output; validate_finding enforces it plus the cross-field invariant
blocking ⇒ severity == major (spec §4/§7).
"""

SEVERITIES = ["major", "minor", "nitpick"]
DIMENSIONS = ["Correctness", "Security", "Performance", "Maintainability",
              "Testing", "Documentation", "Error handling"]
TYPES = ["in-diff", "missing-caller", "dead-code", "follow-up"]
SIDES = ["LEFT", "RIGHT"]

FINDING_SCHEMA = {
    "type": "object",
    "required": ["findings"],
    "properties": {
        "findings": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["severity", "dimension", "file", "line", "title",
                             "body", "type", "anchor", "blocking"],
                "properties": {
                    "severity":  {"enum": SEVERITIES},
                    "dimension": {"enum": DIMENSIONS},
                    "file":  {"type": "string"},
                    "line":  {"type": ["integer", "null"]},
                    "title": {"type": "string"},
                    "body":  {"type": "string"},
                    "type":  {"enum": TYPES},
                    "anchor": {
                        "type": ["object", "null"],
                        "required": ["file", "line", "side"],
                        "properties": {
                            "file": {"type": "string"},
                            "line": {"type": "integer"},
                            "side": {"enum": SIDES},
                        },
                    },
                    "blocking": {"type": "boolean"},
                },
            },
        }
    },
}

_REQUIRED = FINDING_SCHEMA["properties"]["findings"]["items"]["required"]


def validate_finding(f):
    """Return (ok, errors[])."""
    errs = []
    for k in _REQUIRED:
        if k not in f:
            errs.append(f"missing required field: {k}")
    if errs:
        return False, errs
    if f["severity"] not in SEVERITIES:
        errs.append(f"bad severity: {f['severity']}")
    if f["dimension"] not in DIMENSIONS:
        errs.append(f"bad dimension: {f['dimension']}")
    if f["type"] not in TYPES:
        errs.append(f"bad type: {f['type']}")
    if not isinstance(f["file"], str):
        errs.append("file must be string")
    if not (f["line"] is None or isinstance(f["line"], int)):
        errs.append("line must be int or null")
    if not isinstance(f["blocking"], bool):
        errs.append("blocking must be bool")
    a = f["anchor"]
    if a is not None:
        for k in ("file", "line", "side"):
            if k not in a:
                errs.append(f"anchor missing {k}")
        if "side" in a and a["side"] not in SIDES:
            errs.append(f"bad anchor side: {a['side']}")
        if "line" in a and not isinstance(a["line"], int):
            errs.append("anchor line must be int")
    if f.get("blocking") and f.get("severity") != "major":
        errs.append("blocking may be true only when severity == major")
    return (not errs), errs


def validate_findings(obj):
    """Validate a {findings: [...]} envelope; return (ok, errors[])."""
    if not isinstance(obj, dict) or "findings" not in obj:
        return False, ["envelope missing 'findings' array"]
    if not isinstance(obj["findings"], list):
        return False, ["'findings' must be an array"]
    all_errs = []
    for i, f in enumerate(obj["findings"]):
        ok, errs = validate_finding(f)
        all_errs += [f"findings[{i}]: {e}" for e in errs]
    return (not all_errs), all_errs
```

- [ ] **Step 4: Run to verify it passes**

Run: `python3 -m pytest tests/test_schema.py -q` → PASS (8 passed).

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 4: Router module

**Files:**
- Create: `~/.claude/skills/review-pr/harness/router.py`
- Test: `~/.claude/skills/review-pr/tests/test_router.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_router.py
from harness.router import route, ALWAYS_ON, ALL_LENSES

def test_always_on_only_for_plain_service(fixtures):
    fx = fixtures("callsite-orphan")
    r = route(fx["files"], fx["diff"])
    assert set(r["active"]) == set(ALWAYS_ON)

def test_transactional_hunk_fires_transaction_lens(fixtures):
    fx = fixtures("transaction-http")
    r = route(fx["files"], fx["diff"])
    assert "Transaction & concurrency" in r["active"]
    assert set(ALWAYS_ON).issubset(set(r["active"]))
    assert "@Transactional" in r["rationale"]["Transaction & concurrency"]

def test_entity_path_fires_persistence(fixtures):
    fx = fixtures("persistence-nullable")
    r = route(fx["files"], fx["diff"])
    assert "Persistence & schema" in r["active"]

def test_getmessage_and_pkifreetext_fire_security(fixtures):
    fx = fixtures("security-getmessage")
    r = route(fx["files"], fx["diff"])
    assert "Security & info-leak" in r["active"]

def test_pki_path_fires_protocol_lens():
    files = ["src/main/java/com/czertainly/core/api/cmp/CmpController.java"]
    r = route(files, "@@ -1 +1 @@\n+x\n")
    assert "Protocol / PKI semantics" in r["active"]

def test_effort_low_clamps_to_always_on(fixtures):
    fx = fixtures("transaction-http")
    r = route(fx["files"], fx["diff"], effort="low")
    assert set(r["active"]) == set(ALWAYS_ON)

def test_effort_high_runs_all(fixtures):
    fx = fixtures("callsite-orphan")
    r = route(fx["files"], fx["diff"], effort="high")
    assert set(r["active"]) == set(ALL_LENSES)

def test_explicit_lenses_override_everything(fixtures):
    fx = fixtures("transaction-http")
    r = route(fx["files"], fx["diff"], effort="high",
              lenses=["Security & info-leak"])
    assert r["active"] == ["Security & info-leak"]

def test_unmatched_file_still_gets_always_on():
    r = route(["README.md"], "@@ -1 +1 @@\n+hi\n")
    assert set(r["active"]) == set(ALWAYS_ON)

def test_pom_dependency_fires_dependency_lens():
    files = ["pom.xml"]
    diff = "@@ -1 +2 @@\n+    <artifactId>bouncycastle</artifactId>\n"
    r = route(files, diff)
    assert "Dependency & CVE" in r["active"]
```

- [ ] **Step 2: Run to verify it fails**

Run: `python3 -m pytest tests/test_router.py -q` → FAIL (`No module named 'harness.router'`).

- [ ] **Step 3: Write the implementation**

```python
# harness/router.py
"""Deterministic lens router (spec §6). No model call.

Activates the always-on core for any non-trivial code diff, then each
conditional lens whose path-glob OR diff-token trigger matches. Triggers err
toward inclusion (spec §10.1): grep matches tokens even inside comments/strings.
The routing table below is the single editable block.
"""
import fnmatch
import re

from harness.diffmodel import parse_diff

CORRECTNESS = "Correctness & races"
MAINTAIN = "Maintainability & tests"
CALLSITE = "Call-site / dead-code"
PERSISTENCE = "Persistence & schema"
TRANSACTION = "Transaction & concurrency"
SECURITY = "Security & info-leak"
APICONTRACT = "API contract & compat"
PROTOCOL = "Protocol / PKI semantics"
CIBUILD = "CI / build"
DEPENDENCY = "Dependency & CVE"

ALWAYS_ON = [CORRECTNESS, MAINTAIN, CALLSITE]

# Each rule: (lens, [path globs], [hunk-token regexes]). Path glob OR token match fires it.
ROUTING_TABLE = [
    (PERSISTENCE,
     ["*dao/entity/*", "*dao/repository/*", "*db/migration/*", "*.sql"],
     [r"@Entity", r"@Column", r"@Modifying", r"nullable"]),
    (TRANSACTION,
     ["*messaging/jms/*", "*config/cache/*", "*aop/*"],
     [r"@Transactional", r"@Cacheable", r"@CacheEvict", r"afterCommit", r"@Lock"]),
    (SECURITY,
     ["*security/authz/*"],
     [r"@ExternalAuthorization", r"SecurityFilter", r"getMessage\(\)", r"PKIFreeText"]),
    (PROTOCOL,
     ["*/cmp/*", "*/scep/*", "*/acme/*", "*/tsp/*", "*/signing/*"],
     []),
    (CIBUILD,
     ["*.github/*", "*Dockerfile*"],
     []),
]


def _api_contract_fires(files, diff_text):
    # public *Service.java interface / DTO / annotation-definition change (heuristic)
    for f in files:
        if f.endswith("Service.java") or "/dto/" in f or "Dto.java" in f:
            return True
    return bool(re.search(r"public\s+(interface|@interface)", diff_text))


def _dependency_fires(files, diff_text):
    if not any(f.endswith("pom.xml") for f in files):
        return False
    return bool(re.search(r"<(artifactId|groupId|version)>", diff_text))


def _path_hit(globs, files):
    for g in globs:
        for f in files:
            if fnmatch.fnmatch(f, g) or fnmatch.fnmatch("/" + f, g):
                return f
    return None


def _token_hit(tokens, body):
    for t in tokens:
        if re.search(t, body):
            return t
    return None


ALL_LENSES = ALWAYS_ON + [PERSISTENCE, TRANSACTION, SECURITY,
                          APICONTRACT, PROTOCOL, CIBUILD, DEPENDENCY]


def route(changed_files, diff_text, effort="auto", lenses=None):
    """Return {'active': [lens...], 'rationale': {lens: why}}."""
    if lenses:
        return {"active": list(lenses),
                "rationale": {l: "forced via --lenses" for l in lenses}}
    if effort == "low":
        return {"active": list(ALWAYS_ON),
                "rationale": {l: "always-on (effort=low)" for l in ALWAYS_ON}}
    if effort == "high":
        return {"active": list(ALL_LENSES),
                "rationale": {l: "forced via --effort=high" for l in ALL_LENSES}}

    body = parse_diff(diff_text).grep_text()
    active = list(ALWAYS_ON)
    rationale = {l: "always-on" for l in ALWAYS_ON}

    for lens, globs, tokens in ROUTING_TABLE:
        hit_path = _path_hit(globs, changed_files)
        hit_tok = _token_hit(tokens, body)
        if hit_path or hit_tok:
            active.append(lens)
            why = []
            if hit_path:
                why.append(f"path matches {hit_path}")
            if hit_tok:
                why.append(f"hunk matches {hit_tok}")
            rationale[lens] = "; ".join(why)

    if _api_contract_fires(changed_files, body):
        active.append(APICONTRACT)
        rationale[APICONTRACT] = "public Service/DTO/annotation change"
    if _dependency_fires(changed_files, body):
        active.append(DEPENDENCY)
        rationale[DEPENDENCY] = "pom.xml dependency-coordinate change"

    # de-dupe preserving order
    seen, ordered = set(), []
    for l in active:
        if l not in seen:
            seen.add(l)
            ordered.append(l)
    return {"active": ordered, "rationale": rationale}
```

- [ ] **Step 4: Run to verify it passes**

Run: `python3 -m pytest tests/test_router.py -q` → PASS (10 passed).

> If `test_always_on_only_for_plain_service` fails because the orphan fixture's
> `*Service.java` path trips the API-contract heuristic, that's expected behavior
> divergence — adjust the fixture's filename to a non-Service class (e.g. `BarHelper.java`)
> OR relax `_api_contract_fires` to require an interface/DTO signal, and update
> `callsite-orphan/expected.md`. Document the choice in the fixture's expected.md.

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 5: Synthesis module

**Files:**
- Create: `~/.claude/skills/review-pr/harness/synthesis.py`
- Test: `~/.claude/skills/review-pr/tests/test_synthesis.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_synthesis.py
from harness.synthesis import normalize_claim, synthesize
from harness.diffmodel import parse_diff

def f(**kw):
    base = {"severity": "minor", "dimension": "Correctness", "file": "src/A.java",
            "line": 10, "title": "t", "body": "b", "type": "in-diff",
            "anchor": None, "blocking": False}
    base.update(kw); return base

def test_normalize_strips_code_and_locations():
    n = normalize_claim("`helperX()` is dead at src/A.java:10  (orphan)")
    assert n == "is dead (orphan)"

DIFF = """diff --git a/src/A.java b/src/A.java
--- a/src/A.java
+++ b/src/A.java
@@ -8,2 +8,3 @@
 ctx
+addedline
 ctx2
"""

def test_dedup_same_subject_same_claim_different_anchor():
    a = f(line=9, title="`helperX` is dead",
          anchor={"file": "src/A.java", "line": 9, "side": "RIGHT"})
    b = f(line=9, title="helperX is dead at src/A.java:9", severity="major",
          anchor={"file": "src/A.java", "line": 10, "side": "RIGHT"})
    out = synthesize([[a], [b]], parse_diff(DIFF))
    assert len(out) == 1
    assert out[0]["severity"] == "major"   # higher severity kept

def test_scope_filter_drops_in_diff_finding_off_diff():
    off = f(line=999, type="in-diff")       # subject not in diff
    out = synthesize([[off]], parse_diff(DIFF))
    assert out == []

def test_scope_filter_keeps_missing_caller_off_diff():
    mc = f(line=999, type="missing-caller",
           anchor={"file": "src/A.java", "line": 9, "side": "RIGHT"})
    out = synthesize([[mc]], parse_diff(DIFF))
    assert len(out) == 1

def test_keeps_in_diff_finding_on_changed_line():
    on = f(line=9, type="in-diff")           # 9 is added (new-file numbering: 8 ctx,9 added,10 ctx2)
    out = synthesize([[on]], parse_diff(DIFF))
    assert len(out) == 1
```

- [ ] **Step 2: Run to verify it fails** → `No module named 'harness.synthesis'`.

- [ ] **Step 3: Write the implementation**

```python
# harness/synthesis.py
"""Merge lens findings, dedup by subject, filter to diff scope (spec §3.6)."""
import re

_CODE_SPAN = re.compile(r"`[^`]*`")
_LOCATION = re.compile(r"\S+:\d+")
_WS = re.compile(r"\s+")

_OUT_OF_DIFF_TYPES = {"missing-caller", "dead-code", "follow-up"}
_SEV_RANK = {"major": 3, "minor": 2, "nitpick": 1}


def normalize_claim(title):
    t = _CODE_SPAN.sub(" ", title)
    t = _LOCATION.sub(" ", t)
    t = t.lower()
    t = _WS.sub(" ", t).strip()
    return t


def _subject_key(f):
    return (f["file"], f["line"], normalize_claim(f["title"]))


def _resolvable(f):
    return f.get("anchor") is not None


def dedup(findings):
    groups = {}
    for f in findings:
        groups.setdefault(_subject_key(f), []).append(f)
    out = []
    for _, group in groups.items():
        group.sort(key=lambda f: (_SEV_RANK[f["severity"]], _resolvable(f)),
                   reverse=True)
        out.append(group[0])
    return out


def scope_filter(findings, model):
    kept = []
    for f in findings:
        if f["type"] in _OUT_OF_DIFF_TYPES:
            kept.append(f)
        elif model.touches(f["file"], f["line"]):
            kept.append(f)
    return kept


def synthesize(findings_lists, model):
    merged = [f for lst in findings_lists for f in lst]
    return scope_filter(dedup(merged), model)
```

- [ ] **Step 4: Run to verify it passes** → PASS (5 passed).

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 6: Anchor module

**Files:**
- Create: `~/.claude/skills/review-pr/harness/anchor.py`
- Test: `~/.claude/skills/review-pr/tests/test_anchor.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_anchor.py
from harness.anchor import resolve_anchor
from harness.diffmodel import parse_diff

DIFF = """diff --git a/src/Baz.java b/src/Baz.java
--- a/src/Baz.java
+++ b/src/Baz.java
@@ -55,4 +55,3 @@ class Baz {
     public void run() {
-        legacyMethod();
         doOther();
     }
"""

def fnd(**kw):
    base = {"file": "src/Baz.java", "line": 80, "type": "dead-code",
            "anchor": None, "title": "t", "body": "b", "severity": "major",
            "dimension": "Correctness", "blocking": False}
    base.update(kw); return base

def test_uses_supplied_left_anchor_on_deleted_line():
    m = parse_diff(DIFF)
    # old-file line 56 is the deleted `legacyMethod();`
    f = fnd(anchor={"file": "src/Baz.java", "line": 56, "side": "LEFT"})
    r = resolve_anchor(f, m)
    assert r == {"kind": "inline", "path": "src/Baz.java", "line": 56, "side": "LEFT"}

def test_falls_back_to_nearest_changed_when_anchor_invalid():
    m = parse_diff(DIFF)
    f = fnd(anchor={"file": "src/Baz.java", "line": 999, "side": "RIGHT"})
    r = resolve_anchor(f, m)
    assert r["kind"] == "inline" and r["side"] == "RIGHT"
    assert r["line"] in m.commentable_right("src/Baz.java")

def test_falls_back_to_file_level_when_file_in_diff_but_no_line():
    m = parse_diff(DIFF)
    f = fnd(file="src/Baz.java", anchor=None, line=None)
    r = resolve_anchor(f, m)
    assert r["kind"] in ("inline", "file")   # nearest-or-file; never body for a changed file

def test_body_fallback_only_when_file_absent_from_diff():
    m = parse_diff(DIFF)
    f = fnd(file="src/NotInDiff.java", line=5, anchor=None)
    r = resolve_anchor(f, m)
    assert r["kind"] == "body" and r["unanchorable"] is True
```

- [ ] **Step 2: Run to verify it fails** → `No module named 'harness.anchor'`.

- [ ] **Step 3: Write the implementation**

```python
# harness/anchor.py
"""Resolve a finding's posting position via the spec §3.9 fallback chain:
1. supplied anchor if commentable; 2. nearest changed line in same file;
3. file-level comment; 4. review-body bullet (flagged un-anchorable)."""


def _anchor_commentable(anchor, model):
    if not anchor:
        return False
    side = anchor.get("side")
    line = anchor.get("line")
    path = anchor.get("file")
    if side == "RIGHT":
        return line in model.commentable_right(path)
    if side == "LEFT":
        return line in model.commentable_left(path)
    return False


def resolve_anchor(finding, model):
    anchor = finding.get("anchor")
    # 1. supplied anchor
    if _anchor_commentable(anchor, model):
        return {"kind": "inline", "path": anchor["file"],
                "line": anchor["line"], "side": anchor["side"]}

    subj_file = finding["file"]
    file_in_diff = subj_file in model.changed_files()

    # 2. nearest changed line in the same file (RIGHT)
    if file_in_diff:
        target_line = finding.get("line") or 0
        near = model.nearest_right(subj_file, target_line)
        if near is not None:
            return {"kind": "inline", "path": subj_file, "line": near, "side": "RIGHT"}
        # 3. file-level
        return {"kind": "file", "path": subj_file}

    # 4. body bullet — only when subject file has zero diff lines
    return {"kind": "body", "path": subj_file, "unanchorable": True}
```

- [ ] **Step 4: Run to verify it passes** → PASS (4 passed).

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 7: Render module

**Files:**
- Create: `~/.claude/skills/review-pr/harness/render.py`
- Test: `~/.claude/skills/review-pr/tests/test_render.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_render.py
from harness.render import render_report, report_filename, order_findings

def f(sev, dim, file, line, blocking=False, title="t", body="b"):
    return {"severity": sev, "dimension": dim, "file": file, "line": line,
            "blocking": blocking, "title": title, "body": body,
            "type": "in-diff", "anchor": None}

def test_filename_pr_mode():
    assert report_filename("pr", pr_number=42, branch=None) == "review-pr-42.md"

def test_filename_branch_mode_sanitizes():
    assert report_filename("branch", pr_number=None,
                           branch="feat/Signing-X") == "review-branch-feat-signing-x.md"

def test_ordering_blocking_major_first():
    items = [f("nitpick","Maintainability","A.java",1),
             f("minor","Security","B.java",2),
             f("major","Correctness","C.java",3,blocking=False),
             f("major","Correctness","D.java",4,blocking=True)]
    ordered = order_findings(items)
    assert [x["file"] for x in ordered] == ["D.java","C.java","B.java","A.java"]

def test_report_has_summary_and_lens_header():
    md = render_report(
        title="PR #7: thing",
        fired={"Correctness & races": "always-on"},
        failed=["Security & info-leak"],
        findings=[f("major","Correctness","C.java",3,blocking=True)],
    )
    assert "Code review: 1 issue(s) found." in md
    assert "Correctness & races" in md and "always-on" in md
    assert "Security & info-leak lens failed — not covered" in md
    assert "**! Major** | Correctness" in md
    assert "`C.java:3`" in md

def test_clean_report_zero_issues_still_renders():
    md = render_report(title="branch x", fired={"Correctness & races": "always-on"},
                       failed=[], findings=[])
    assert "Code review: 0 issue(s) found." in md
```

- [ ] **Step 2: Run to verify it fails** → `No module named 'harness.render'`.

- [ ] **Step 3: Write the implementation**

```python
# harness/render.py
"""Render findings into lubomirw's rubric grammar (spec §7)."""
import re

GLYPH = {"major": "!", "minor": "~", "nitpick": "."}
LABEL = {"major": "Major", "minor": "Minor", "nitpick": "Nitpick"}
_SEV_RANK = {"major": 2, "minor": 1, "nitpick": 0}


def report_filename(mode, pr_number=None, branch=None):
    if mode == "pr":
        return f"review-pr-{pr_number}.md"
    slug = re.sub(r"[^a-z0-9]+", "-", (branch or "").lower()).strip("-")
    return f"review-branch-{slug}.md"


def order_findings(findings):
    def key(f):
        blocking = bool(f.get("blocking")) and f["severity"] == "major"
        return (
            0 if blocking else 1,
            -_SEV_RANK[f["severity"]],
            f["file"],
            f["line"] if f["line"] is not None else 1 << 30,
        )
    return sorted(findings, key=key)


def _finding_block(f):
    loc = f["file"] + ":" + (str(f["line"]) if f["line"] is not None else "None")
    head = f"**{GLYPH[f['severity']]} {LABEL[f['severity']]}** | {f['dimension']}"
    return f"{head}\n`{loc}`\n\n{f['body']}\n"


def render_report(title, fired, failed, findings):
    lines = [f"# Review — {title}", ""]
    lines.append("**Lenses fired:**")
    for lens, why in fired.items():
        lines.append(f"- {lens} — {why}")
    if failed:
        lines.append("")
        for lens in failed:
            lines.append(f"**Lenses failed:** ⚠ {lens} lens failed — not covered")
    lines.append("")
    lines.append(f"Code review: {len(findings)} issue(s) found.")
    lines.append("")
    lines.append("---")
    lines.append("")
    for f in order_findings(findings):
        lines.append(_finding_block(f))
    return "\n".join(lines).rstrip() + "\n"
```

- [ ] **Step 4: Run to verify it passes** → PASS (5 passed).

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 8: Post module (payload builder + dry-run)

**Files:**
- Create: `~/.claude/skills/review-pr/harness/post.py`
- Test: `~/.claude/skills/review-pr/tests/test_post.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_post.py
from harness.post import build_review_payload
from harness.diffmodel import parse_diff

DIFF = """diff --git a/src/Baz.java b/src/Baz.java
--- a/src/Baz.java
+++ b/src/Baz.java
@@ -55,4 +55,3 @@ class Baz {
     public void run() {
-        legacyMethod();
         doOther();
     }
"""

def f(**kw):
    base = {"file": "src/Baz.java", "line": 80, "type": "dead-code",
            "severity": "major", "dimension": "Correctness", "blocking": False,
            "title": "legacyMethod now dead", "body": "src/Baz.java:80 is dead",
            "anchor": {"file": "src/Baz.java", "line": 56, "side": "LEFT"}}
    base.update(kw); return base

def test_dead_code_reanchors_left_not_body():
    payload = build_review_payload([f()], parse_diff(DIFF))
    assert payload["event"] == "COMMENT"
    assert len(payload["comments"]) == 1
    c = payload["comments"][0]
    assert c["path"] == "src/Baz.java" and c["line"] == 56 and c["side"] == "LEFT"
    # the body still cites the true subject location
    assert "src/Baz.java:80" in c["body"]
    # nothing relegated to the review body
    assert "un-anchorable" not in payload["body"].lower()

def test_unanchorable_goes_to_body_and_is_flagged():
    g = f(file="src/Gone.java", anchor=None)
    payload = build_review_payload([g], parse_diff(DIFF))
    assert payload["comments"] == []
    assert "un-anchorable" in payload["body"].lower()
    assert "src/Gone.java" in payload["body"]
```

- [ ] **Step 2: Run to verify it fails** → `No module named 'harness.post'`.

- [ ] **Step 3: Write the implementation**

```python
# harness/post.py
"""Build the single batched PR-review payload (spec §3.9). Submission via gh is
handled by the CLI; this module is pure so it is fully testable (dry-run)."""
from harness.anchor import resolve_anchor


def build_review_payload(findings, model, body_header=""):
    comments = []
    body_bullets = []
    for f in findings:
        res = resolve_anchor(f, model)
        if res["kind"] == "inline":
            comments.append({"path": res["path"], "line": res["line"],
                             "side": res["side"], "body": f["body"]})
        elif res["kind"] == "file":
            comments.append({"path": res["path"], "subject_type": "file",
                             "body": f["body"]})
        else:  # body
            body_bullets.append(
                f"- ⚠ un-anchorable ({f['file']}:{f['line']}): {f['title']}\n\n{f['body']}")
    body = body_header
    if body_bullets:
        body = (body + "\n\n" if body else "") + \
               "### Un-anchorable findings (no in-diff line)\n" + "\n".join(body_bullets)
    return {"event": "COMMENT", "body": body, "comments": comments}
```

- [ ] **Step 4: Run to verify it passes** → PASS (2 passed).

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 9: CLI glue + diff acquisition

**Files:**
- Create: `~/.claude/skills/review-pr/harness/acquire.py`
- Create: `~/.claude/skills/review-pr/harness/__main__.py`
- Test: `~/.claude/skills/review-pr/tests/test_cli.py`

- [ ] **Step 1: Write the failing test (CLI wiring + acquire arg-building, no network)**

```python
# tests/test_cli.py
import json, subprocess, sys, pathlib
from harness.acquire import branch_diff_cmds, pr_diff_cmds

ROOT = pathlib.Path(__file__).parents[1]

def test_branch_cmds_use_merge_base():
    cmds = branch_diff_cmds(base="origin/main")
    assert any("merge-base" in " ".join(c) for c in cmds["resolve_base"])
    assert cmds["diff"][:3] == ["git", "diff", "BASE"] or "diff" in cmds["diff"]

def test_pr_cmds_use_declared_base():
    cmds = pr_diff_cmds(7)
    assert ["gh", "pr", "diff", "7"] == cmds["diff"]
    assert "baseRefName" in " ".join(cmds["meta"])

def test_cli_route_subcommand_emits_json(tmp_path):
    diff = tmp_path / "d.patch"; diff.write_text(
        "diff --git a/X.java b/X.java\n--- a/X.java\n+++ b/X.java\n@@ -1 +1,2 @@\n x\n+@Transactional\n")
    files = tmp_path / "f.txt"; files.write_text("X.java\n")
    out = subprocess.check_output(
        [sys.executable, "-m", "harness", "route",
         "--diff", str(diff), "--files", str(files)],
        cwd=str(ROOT))
    data = json.loads(out)
    assert "Transaction & concurrency" in data["active"]
```

- [ ] **Step 2: Run to verify it fails** → import/attr errors.

- [ ] **Step 3: Write acquire.py**

```python
# harness/acquire.py
"""Build the git/gh command sequences for diff acquisition (spec §3.3).
Returned as command lists so the CLI runs them and the logic stays testable
without a live repo/network."""


def branch_diff_cmds(base="origin/main"):
    return {
        "fetch": ["git", "fetch", "origin", "--quiet"],
        "resolve_base": ["git", "merge-base", base, "HEAD"],
        "diff": ["git", "diff", "BASE...HEAD"],          # BASE substituted by caller
        "files": ["git", "diff", "--name-only", "BASE...HEAD"],
    }


def pr_diff_cmds(n):
    return {
        "meta": ["gh", "pr", "view", str(n), "--json",
                 "baseRefName,title,body,headRefName"],
        "diff": ["gh", "pr", "diff", str(n)],
        "files": ["gh", "pr", "diff", str(n), "--name-only"],
    }
```

- [ ] **Step 4: Write __main__.py (argparse CLI)**

```python
# harness/__main__.py
"""review-pr deterministic core CLI.

Subcommands the orchestrator calls:
  route    --diff FILE --files FILE [--effort low|high|auto] [--lenses a,b]
  validate --findings FILE            (validate a {findings:[...]} envelope)
  synth    --diff FILE --findings FILE [--findings FILE ...]
  render   --title STR --fired JSON --failed JSON --findings FILE --out FILE
           [--mode pr|branch --pr N --branch NAME]   (also prints the path)
  post     --diff FILE --findings FILE --pr N [--dry-run]
"""
import argparse
import json
import sys

from harness import router, schema, synthesis, render, post
from harness.diffmodel import parse_diff


def _read(p):
    with open(p) as fh:
        return fh.read()


def _load_findings(p):
    return json.load(open(p))["findings"]


def cmd_route(a):
    lenses = a.lenses.split(",") if a.lenses else None
    r = router.route(_read(a.files).split(), _read(a.diff),
                     effort=a.effort, lenses=lenses)
    print(json.dumps(r, indent=2))


def cmd_validate(a):
    ok, errs = schema.validate_findings(json.load(open(a.findings)))
    print(json.dumps({"ok": ok, "errors": errs}, indent=2))
    sys.exit(0 if ok else 1)


def cmd_synth(a):
    model = parse_diff(_read(a.diff))
    lists = [_load_findings(p) for p in a.findings]
    out = synthesis.synthesize(lists, model)
    print(json.dumps({"findings": out}, indent=2))


def cmd_render(a):
    findings = _load_findings(a.findings)
    md = render.render_report(a.title, json.loads(a.fired),
                              json.loads(a.failed), findings)
    fname = render.report_filename(a.mode, a.pr, a.branch)
    with open(a.out or fname, "w") as fh:
        fh.write(md)
    print(a.out or fname)


def cmd_post(a):
    model = parse_diff(_read(a.diff))
    payload = post.build_review_payload(_load_findings(a.findings), model)
    if a.dry_run:
        print(json.dumps(payload, indent=2))
        return
    import subprocess
    subprocess.run(
        ["gh", "api", "--method", "POST",
         f"repos/{a.owner}/{a.repo}/pulls/{a.pr}/reviews",
         "--input", "-"],
        input=json.dumps(payload).encode(), check=True)


def main(argv=None):
    p = argparse.ArgumentParser(prog="harness")
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("route"); r.add_argument("--diff", required=True)
    r.add_argument("--files", required=True)
    r.add_argument("--effort", default="auto", choices=["auto", "low", "high"])
    r.add_argument("--lenses", default=None); r.set_defaults(fn=cmd_route)

    v = sub.add_parser("validate"); v.add_argument("--findings", required=True)
    v.set_defaults(fn=cmd_validate)

    s = sub.add_parser("synth"); s.add_argument("--diff", required=True)
    s.add_argument("--findings", action="append", required=True)
    s.set_defaults(fn=cmd_synth)

    rd = sub.add_parser("render"); rd.add_argument("--title", required=True)
    rd.add_argument("--fired", required=True); rd.add_argument("--failed", default="[]")
    rd.add_argument("--findings", required=True); rd.add_argument("--out", default=None)
    rd.add_argument("--mode", default="branch"); rd.add_argument("--pr", type=int, default=None)
    rd.add_argument("--branch", default=None); rd.set_defaults(fn=cmd_render)

    po = sub.add_parser("post"); po.add_argument("--diff", required=True)
    po.add_argument("--findings", required=True); po.add_argument("--pr", type=int)
    po.add_argument("--owner", default="OmniTrustILM"); po.add_argument("--repo", default="core")
    po.add_argument("--dry-run", action="store_true"); po.set_defaults(fn=cmd_post)

    args = p.parse_args(argv)
    args.fn(args)


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run to verify it passes** → PASS (3 passed). Then run the full suite:

Run: `cd ~/.claude/skills/review-pr && python3 -m pytest tests/ -q`
Expected: all pass (≈37 tests across modules).

- [ ] **Step 6: Commit (checkpoint marker)**

---

## Task 10: SKILL.md orchestrator

**Files:**
- Create: `~/.claude/skills/review-pr/SKILL.md`

- [ ] **Step 1: Frontmatter + overview**

`name: review-pr` (invocable as `/review-pr`). Description covers trigger phrases (review PR/branch, pre-review, clear the reviewer's bar) and the flags.

```markdown
---
name: review-pr
description: >
  Reviews the current branch or a given PR with content-routed lens fan-out,
  adversarial verification, and a rubric Markdown report; optionally posts one
  batched PR review. Use when asked to review a PR or branch, "pre-review"
  before requesting human review, or run a correctness/security review of a
  diff. Accepts an optional PR number and flags --post, --lenses=a,b,c,
  --effort=auto|low|high, --base=<ref>.
---

# review-pr

Hybrid harness: a tested Python core (`harness/`) does the deterministic work
(diff parse, routing, schema validation, synthesis/dedup, anchor resolution,
render, post payload); Claude subagents do the judgment work (per-lens review +
adversarial verify). Run the core with `python3 -m harness <cmd>` from the skill
dir. Lens prompts: `references/lenses.md`. Self-test: `references/testing.md`.
```

- [ ] **Step 2: Invocation & pipeline sections**

Document arg parsing (§3.1/§6.1) and the 7 steps, each naming the exact CLI call or subagent action. Write it as an ordered procedure:

```markdown
## Invocation
Parse args: bare int ⇒ PR mode; none ⇒ branch mode; flags --post, --lenses=,
--effort=, --base=. Echo resolved mode/PR/base/effort in one line.

## Step 1 — Acquire diff
Branch: `git fetch origin -q`; `BASE=$(git merge-base <base> HEAD)`;
`git diff "$BASE"...HEAD > /tmp/rp/diff.patch`;
`git diff --name-only "$BASE"...HEAD > /tmp/rp/files.txt`.
PR: `gh pr view <n> --json baseRefName,title,body`; `gh pr diff <n> > diff.patch`;
`gh pr diff <n> --name-only > files.txt`. Empty diff / bad base ⇒ STOP, no report.

## Step 2 — Route
`python3 -m harness route --diff diff.patch --files files.txt [--effort .. --lenses ..]`
Parse the JSON {active, rationale}. These are the lenses to fan out and the
header rationale.

## Step 3 — Lens fan-out
Use superpowers:dispatching-parallel-agents. One subagent per active lens. Give
each: the diff, files.txt, the target repo CLAUDE.md (+ sub-project CLAUDE.md),
its prompt from references/lenses.md, and the schema from
`python3 -m harness ...` (the FINDING_SCHEMA in harness/schema.py). The call-site
lens is told it may use `gh search code --owner OmniTrustILM`. Each subagent
writes {findings:[...]} to /tmp/rp/findings-<lens>.json. Validate each with
`python3 -m harness validate --findings <file>`; a lens that errors or fails
validation contributes nothing and is added to the FAILED list (Step 6 header).

## Step 4 — Synthesis
`python3 -m harness synth --diff diff.patch --findings f1.json --findings f2.json ...`
→ deduped, scope-filtered {findings:[...]}.

## Step 5 — Verify
For each surviving major/minor finding, dispatch one skeptic subagent (parallel)
to REFUTE it against the code; default-to-drop on non-confirmation. Nitpicks
skip verify. Write the kept set to /tmp/rp/verified.json.

## Step 6 — Render
`python3 -m harness render --title "<PR #n: title | branch>" \
  --fired '<rationale JSON>' --failed '<failed-lens JSON>' \
  --findings verified.json --mode pr|branch --pr N --branch NAME \
  --out docs/superpowers/reviews/<name>.md`  (writes into the TARGET repo).

## Step 7 — Post (optional, --post)
Requires a PR. Branch w/o PR ⇒ refuse. Preview:
`python3 -m harness post --diff diff.patch --findings verified.json --pr N --dry-run`
then, on user go, drop --dry-run to submit one batched review. Warn it is not
idempotent (re-running posts a second review).
```

- [ ] **Step 3: Error-handling + notes section (§8, §10.1)**

```markdown
## Error handling
- Lens error/invalid JSON ⇒ zero findings + listed in "Lenses failed"; a partial
  run never reads as clean.
- No diff / unresolvable base ⇒ clear message, no report.
- --post with no PR ⇒ refuse with actionable message.
- gh unauth / `gh search code` unavailable ⇒ call-site lens degrades to diff-only
  and says so in a finding; run continues.
## Accepted limitations (v1)
- Router triggers err toward inclusion (grep matches comments/strings).
- --post is not idempotent.
- No token-budget ceiling on --effort=high.
```

- [ ] **Step 4: Content check**

Every spec §3 step has a SKILL section naming the exact CLI call or subagent action; flags (§3.1/§6.1) parsed; report path is the target repo; §8 + §10.1 present; `name: review-pr`.

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Task 11: references/lenses.md

**Files:**
- Create: `~/.claude/skills/review-pr/references/lenses.md`

- [ ] **Step 1: Shared preamble + always-on lenses (§5.1)**

Write the preamble (return JSON per schema; set `type` correctly; populate `anchor` for out-of-diff types; cite subject `file:line` in every body) and the 3 always-on lenses with **Responsibility / Must-flag / CLAUDE.md hooks**. (Use the content drafted in the prior plan revision — Correctness & races; Maintainability & tests; Call-site / dead-code with `gh search code --owner OmniTrustILM` and degradation note.)

- [ ] **Step 2: Conditional lenses (§5.2)**

Write the 7 conditional lenses (Persistence & schema; Transaction & concurrency; Security & info-leak; API contract & compat; Protocol / PKI semantics; CI / build; Dependency & CVE), each with Responsibility / Must-flag / CLAUDE.md hooks. The Must-flag lists must each cover the plant in the matching golden fixture (tx-across-HTTP, raw getMessage→wire, boxed Integer for nullable=false, orphan public method, undocumented non-obvious method).

- [ ] **Step 3: Content check**

10 lenses total; names byte-identical to `harness/router.py` constants (`Correctness & races`, `Transaction & concurrency`, etc.); each recall fixture's plant is named in exactly one lens's Must-flag list.

- [ ] **Step 4: Commit (checkpoint marker)**

---

## Task 12: references/testing.md + full verification run

**Files:**
- Create: `~/.claude/skills/review-pr/references/testing.md`

- [ ] **Step 1: Write the agentic self-test procedure**

```markdown
# review-pr self-test

The deterministic core is covered by pytest (`python3 -m pytest tests/ -q`):
router lens-sets, schema/blocking⇒major, synthesis dedup + scope filter, anchor
LEFT/file/body fallback, render ordering/clean-report, post LEFT re-anchor +
un-anchorable body. Run that first; it must be green.

This file covers the parts pytest can't — lens recall and verify behavior —
by driving the skill on the golden fixtures.

## Agentic assertions
1. Recall (§9.1): for each recall fixture, dispatch its expected lens(es) on the
   fixture diff; the named lens emits a finding matching expected.md
   (dimension/severity/subject).
2. Verify keeps true positives (§9.3): the skeptic does NOT drop the planted
   finding.
3. Error handling (§10.2): force the Security lens to fail on security-getmessage
   (e.g. feed it a malformed schema), run render with that lens in --failed;
   assert the report shows "⚠ Security … not covered" and is not clean.
4. Clean (§9.4): drive the clean fixture end-to-end; zero findings; report says
   "Code review: 0 issue(s) found." and is written successfully.

## How to run
For each fixture, feed diff.patch + changed-files.txt as the acquired diff
(skip Step 1's git/gh), run `route`, dispatch the lens subagent(s), `synth`,
verify, `render`, and compare to expected.md. For post payload, the pytest case
`test_post.py::test_dead_code_reanchors_left_not_body` already asserts §10.2's
re-anchor — no agentic run needed.
```

- [ ] **Step 2: Run the full pytest suite**

Run: `cd ~/.claude/skills/review-pr && python3 -m pytest tests/ -q`
Expected: all green. Fix any failure in the relevant module + re-run (CLAUDE.md convergence discipline).

- [ ] **Step 3: Run the agentic self-test**

Execute the 4 agentic assertions in `testing.md` against the fixtures by driving the skill. Record pass/fail. For failures, fix the lens prompt (`references/lenses.md`) or SKILL step and re-run that assertion. Acceptance gate: all 4 agentic assertions + full pytest green.

- [ ] **Step 4: Smoke-test end-to-end on a real diff (no post)**

From a repo checkout, run `/review-pr --effort=low` on the current branch; confirm a report is written to `docs/superpowers/reviews/review-branch-<slug>.md` with the lens header and a summary line. (No `--post`, so zero GitHub writes.)

- [ ] **Step 5: Commit (checkpoint marker)**

---

## Self-review against the spec

- §3 pipeline (7 steps) — Task 9 CLI + Task 10 SKILL map step-for-step (acquire, route, fan-out, synth, verify, render, post). ✔
- §3.1 flags / §6.1 effort+lenses — `router.route` (Task 4) + SKILL invocation (Task 10). ✔
- §3.3 diff acquisition, correct base per mode — `acquire.py` (Task 9). ✔
- §3.6 synthesis (subject-dedup, normalized-claim, scope filter w/ 3 type exceptions) — `synthesis.py` (Task 5), tested. ✔
- §3.7 verify (major/minor only, default-to-drop, nitpicks skip) — Task 10 Step 5 + agentic test (Task 12). ✔
- §3.8 render filenames + §3.9 anchor fallback chain (LEFT on deletion, file, body-flagged) — `render.py` (Task 7) + `anchor.py`/`post.py` (Tasks 6, 8), tested. ✔
- §4 schema (+ blocking⇒major invariant, anchor required for out-of-diff types) — `schema.py` (Task 3), tested. ✔
- §5 lens catalogue (10) — `references/lenses.md` (Task 11), names identical to router constants. ✔
- §6 router table — `router.py` ROUTING_TABLE (Task 4), tested per trigger. ✔
- §7 report grammar (glyphs, ordering blocking-Major→Major→Minor→Nitpick, header w/ rationale, failed-lens line) — `render.py` (Task 7), tested. ✔
- §8 error handling (4 cases, partial-never-clean) — Task 10 Step 3 + lens-failure agentic assertion (Task 12). ✔
- §9 testing (5 recall + clean) — fixtures (Task 2) + agentic test (Task 12). ✔
- §10.2 gaps (error-handling, post-payload LEFT re-anchor, dedup) — error-handling agentic assertion (Task 12), `test_post.py` (Task 8), `test_synthesis.py` + dedup-twolens fixture (Tasks 5, 2). ✔
- §10.1 limitations — surfaced in SKILL (Task 10 Step 3). ✔

**Name/type consistency:** lens display names identical across `router.py` (Task 4), `lenses.md` (Task 11), fixtures' expected.md (Task 2). Finding field names identical across `schema.py`, `synthesis.py`, `anchor.py`, `render.py`, `post.py` (Tasks 3,5,6,7,8). Glyphs `!`/`~`/`.` in `render.py` match §7. Report filenames defined once in `render.report_filename` (Task 7). CLI subcommand names in `__main__.py` (Task 9) match the calls written in SKILL.md (Task 10).

**Known follow-ups (spec §10, out of v1 scope):** CI wrapper, call-site search caching, `--since <ref>` range mode. The `acquire.py` `BASE...HEAD` substitution and new-file `/dev/null` detection (Task 1) are the two parse details most worth an extra eyeball during execution.
