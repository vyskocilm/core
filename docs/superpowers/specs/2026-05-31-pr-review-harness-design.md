# PR Review Harness — Design Spec

**Date:** 2026-05-31
**Status:** Approved (design); pending implementation plan
**Author:** Ivo Raisr (with Claude Code)

## 1. Motivation

PRs against `OmniTrustILM/core` are reviewed by `lubomirw` using what analysis of his
review corpus strongly indicates is an **agentic, tool-using AI reviewer** built on a
frontier model: a fixed severity×dimension rubric, multiple focused passes
(correctness + security), org-wide `gh search code` call-site grounding, and
`CLAUDE.md`-aware reasoning, with the human curating and batch-posting the output.

This harness reconstructs that capability so the author can clear the same bar
**before** review — running the same class of checks locally on a branch or against an
open PR, producing a report in the same grammar, and optionally posting it.

The capability gap being closed is the **harness** (tool access + `CLAUDE.md` grounding
+ multi-pass + structured rubric + verification), not raw model IQ.

## 2. Goals / Non-goals

**Goals**
- One Claude Code command that reviews either the current branch or a given PR number.
- Lens coverage that **auto-scales to what the diff touches** (content-aware routing).
- Findings in lubomirw's exact rubric grammar, written to a local Markdown report.
- Optional, opt-in posting of findings as a single batched PR review.
- Adversarial verification of non-trivial findings to suppress false positives.

**Non-goals (YAGNI — explicitly deferred)**
- CI / GitHub Action wrapper (may wrap the same core later).
- Auto-fix / auto-apply of findings.
- Inline-comment thread resolution / reply management.
- A config file beyond command flags.

## 3. Architecture (Approach B: sub-agent fan-out + verify)

```
/review-pr  ──▶  review-pr skill (orchestrator)
                   │
                   ├─ 1. Diff acquisition      (git diff | gh pr diff)
                   ├─ 2. Router                (paths + diff-tokens → lens set)
                   ├─ 3. Lens fan-out  ── parallel subagents (1 per active lens)
                   │        each returns findings JSON (shared schema)
                   ├─ 4. Synthesis            (merge + dedup + diff-scope filter)
                   ├─ 5. Verify pass  ── parallel skeptic subagents (Major/Minor only)
                   ├─ 6. Render               (rubric Markdown report)
                   └─ 7. Post (optional)      (--post → batched gh api review)
```

Data flow: `diff → route → fan-out(lenses) → synthesis(dedup) → verify(Major/Minor) → render → [--post]`

### 3.1 Components

1. **`/review-pr` slash command** — thin entry point.
   - Arg: optional `PR-number`. Absent ⇒ review current branch vs. its base.
   - Flags: `--post`, `--lenses=a,b,c` (force a set), `--effort=auto|low|high` (default `auto`).
2. **`review-pr` skill** — the orchestrator. Owns the rubric, lens prompt library, router
   table, verify prompt, synthesis/render logic, and post logic.
3. **Diff acquisition** —
   - Branch mode: base defaults to `origin/main` (overridable); diff is
     `git diff $(git merge-base origin/main HEAD)...HEAD` plus the changed-file list.
   - PR mode: `gh pr view <n>` (base, title, body) + `gh pr diff <n>`; base is the PR's
     declared base ref, not `origin/main`.
4. **Router** — deterministic; maps changed paths + grep over diff hunks to an active lens
   set. Emits a rationale per fired lens. Uncategorized files fall back to the always-on core.
5. **Lens subagents** — one per active lens, run in parallel (`dispatching-parallel-agents`).
   Each receives the diff, changed-file list, and project `CLAUDE.md`; the call-site lens
   additionally has `gh search code` access. Each returns findings as JSON (§4 schema).
6. **Synthesis** — merge all findings, then **scope-filter, then dedup** (in that order: decide
   membership first, then collapse duplicates among members — so an out-of-diff kept-type finding
   is never discarded in favour of a same-subject `in-diff` finding that the scope-filter would
   then drop). Scope-filter drops findings whose **subject** the diff does not touch **unless** the
   type is `missing-caller` / `dead-code` / `follow-up` (these legitimately concern code outside the
   diff and carry an in-diff `anchor`; see §4 and §3.9). Dedup is by **subject**
   `(file, line, normalized-claim)` (the location the finding is *about* — see §4) **not** by anchor,
   so two lenses flagging the same orphaned symbol collapse even when they anchored at different
   in-diff lines; the higher-severity copy is kept (tie-break: the one with a resolvable anchor).
   `normalized-claim` = title lowercased, code spans and `file:line` references stripped (including a
   leading `at ` preposition before a location), whitespace collapsed. Bare identifiers are
   **intentionally not stripped** — over-stripping would merge distinct findings that share a line but
   describe different symbols. Two findings with the same subject and an equal normalized-claim are one.
   *(Implementation note: as built, `harness/synthesis.py` realises scope-before-dedup; the original
   draft listed dedup first. The `at`-preposition and no-identifier-stripping rules were pinned during
   implementation.)*
7. **Verify pass** — one skeptic subagent per surviving **Major** and **Minor** finding,
   prompted to *refute* against real code; default-to-drop when it cannot confirm.
   **Nitpicks skip verification** (cheap, low-stakes).
8. **Render** — Markdown report in lubomirw's grammar. Filename is `review-pr-<n>.md` in PR
   mode and `review-branch-<slug>.md` in branch mode (`<slug>` = sanitized branch name),
   written under `docs/superpowers/reviews/`.
9. **Post (optional)** — `--post` submits one `POST .../pulls/{n}/reviews`. **Every** finding
   posts as an inline comment on an in-diff line — out-of-diff findings are re-anchored, not
   relegated to the review body (an unresolvable body comment reads as ignorable). Each
   comment posts at its `anchor` (§4); the body always cites the real **subject** location.
   Anchor resolution walks a fallback chain until one resolves:
   1. the lens-supplied `anchor`, if it maps to a real diff position (RIGHT for added lines,
      LEFT for deleted — e.g. a `dead-code` finding anchors on the deleted caller line);
   2. else the nearest changed hunk in the same file;
   3. else a file-level comment (`subject_type: file`);
   4. else — only when the subject file has zero diff lines and no causal in-diff line exists —
      a review-body bullet, **flagged in the report as un-anchorable** so the body is visibly
      the exception, never the default.

   Requires a PR number. No `--post` ⇒ zero GitHub writes.

## 4. Finding schema (shared structured output)

Every lens subagent returns `{ "findings": [ Finding, ... ] }` where each `Finding` is:

| Field | Type | Notes |
|---|---|---|
| `severity` | enum `major \| minor \| nitpick` | rendered as `!` / `~` / `.` |
| `dimension` | enum | Correctness, Security, Performance, Maintainability, Testing, Documentation, Error handling |
| `file` | string | **subject** — repo-relative path the finding is *about*; may be outside the diff |
| `line` | int \| null | **subject** line, 1-based in the post-change file; null ⇒ file-level / general observation |
| `title` | string | one-line summary |
| `body` | string | full Markdown explanation (may include code suggestion); must cite the subject `file:line` explicitly, since the posted comment may live at a different `anchor` |
| `type` | enum `in-diff \| missing-caller \| dead-code \| follow-up` | drives the synthesis diff-scope filter |
| `anchor` | `{ file, line, side: LEFT\|RIGHT }` \| null | in-diff position where `--post` places the comment. Lenses **must** populate it for `missing-caller`/`dead-code`/`follow-up`; for `in-diff` it defaults to the subject on the RIGHT side. `side: LEFT` anchors on a deleted line. |
| `blocking` | bool | true only for genuine defects introduced by the diff; **may be true only when `severity = major`** (see §7 ordering) |

Validation happens at the tool-call layer (structured output), so lenses retry on schema mismatch.

## 5. Lens catalogue

### 5.1 Always-on (any non-trivial code diff)
- **Correctness & races** — logic bugs, state divergence, race conditions; the safety net
  against router under-triggering.
- **Maintainability & tests** — Javadoc on non-obvious behaviour, comment density per
  `CLAUDE.md`, **test quality** (green-but-asserts-the-wrong-thing, missing edge/race tests),
  coverage gaps.
- **Call-site / dead-code grounding** — org-wide `gh search code --owner OmniTrustILM` to
  find missing production callers, dead methods, broken rename history.

### 5.2 Conditional (fired by the router)
- **Persistence & schema** — entity nullability vs. DB column constraints, cascade misuse,
  `clearAutomatically` persistence-context detach, changed emitted SQL, migration safety.
- **Transaction & concurrency** — tx-across-HTTP-call, pessimistic locking on multi-actor
  rows, event-after-commit ordering, `NOT_SUPPORTED` + explicit-tx, two-writes/two-tx
  split-brain, cache-aside staleness windows. Knows the `CLAUDE.md` writer/orchestrator
  bean-pair pattern.
- **Security & info-leak** — authorization-annotation correctness, `Exception.getMessage()`
  leakage to the wire (`PKIFreeText` / ACME detail / REST errors), OPA scoping, bind-safety.
- **API contract & compat** — silent breaking changes (authz contract, query semantics,
  public visibility, interface/DTO changes); flags release-note / PR-body-worthy changes.
- **Protocol / PKI semantics** — CMP/SCEP/ACME/TSP/signing: session handling, KeyUsage
  checks, chain-building correctness, protocol-level info exposure.
- **CI / build** — `.github/**`, `Dockerfile`, `pom.xml` build config: workflow correctness,
  `fetch-depth`, artifact retention, test-split guards.
- **Dependency & CVE** — `pom.xml` dependency-coordinate changes.

## 6. Router specification

The router is **deterministic** (no model call). It activates the always-on core plus any
conditional lens whose triggers match. Triggers combine **path globs** and **diff-token grep**.

| Trigger signal | Lens |
|---|---|
| `dao/entity/**`, `dao/repository/**`, `db/migration/**`, `*.sql`; or hunk matches `@Entity`/`@Column`/`@Modifying`/`nullable` | Persistence & schema |
| hunk matches `@Transactional`/`@Cacheable`/`@CacheEvict`/`afterCommit`/`@Lock`; or `messaging/jms/**`, `config/cache/**`, `aop/**` | Transaction & concurrency |
| `security/authz/**`; or hunk matches `@ExternalAuthorization`/`SecurityFilter`/`getMessage()`/`PKIFreeText` | Security & info-leak |
| `**/cmp/**`, `**/scep/**`, `**/acme/**`, `**/tsp/**`, `**/signing/**` | Protocol / PKI semantics |
| public `*Service.java` interface, DTO, or annotation-definition change | API contract & compat |
| `.github/**`, `Dockerfile` | CI / build |
| `pom.xml` dependency-coordinate change | Dependency & CVE |

The routing table lives in **one editable block** in the skill. Any changed file matching no
glob still receives the always-on core (never zero coverage).

### 6.1 `--effort` override
- `auto` (default): use the router's output verbatim.
- `low`: clamp to the 3 always-on lenses, ignoring router triggers.
- `high`: run **all** lenses regardless of triggers.

`--lenses=...` overrides both router and effort with an explicit set.

## 7. Report format

The rendered report mirrors lubomirw's grammar:

- Header: active lenses **and why** each fired
  (`Persistence — entities touched: SigningProfileVersion.java`).
- Summary line: `Code review: N issue(s) found.`
- Each finding: `**! Major** | Correctness` / `**~ Minor** | Security` / `**. Nitpick** | Maintainability`,
  then `file:line`, then the body.
- Findings ordered: blocking Major → Major → Minor → Nitpick. `blocking` is a sub-flag of
  Major only — a Minor or Nitpick is never blocking (enforced by the schema constraint in §4).

## 8. Error handling

- A lens that errors or returns invalid JSON ⇒ contributes **zero** findings **and is listed
  in the report as failed** (`⚠ Security lens failed — not covered`). A partial run must never
  read as "clean".
- No diff / unresolvable base ⇒ exit with a clear message, no report.
- `--post` on a branch with no associated PR ⇒ refuse with an actionable message.
- `gh` not authenticated / call-site search unavailable ⇒ the call-site lens degrades to
  diff-only and notes the limitation; the run continues.

## 9. Testing strategy

**Golden-diff fixtures** — small diffs each *planting* a known issue for one lens:
- a `@Transactional` method making an HTTP call (Transaction lens),
- a raw `getMessage()` forwarded to a response (Security lens),
- a new public method with no caller (Call-site lens),
- an entity field boxing a `nullable = false` column (Persistence lens),
- a non-obvious public method with no Javadoc (Maintainability lens).

Assertions:
1. Each lens flags its planted issue.
2. The **router** activates exactly the expected lens set for each fixture's file categories.
3. The **verify pass** does **not** drop a true-positive plant.
4. A **clean** diff yields zero findings and an empty-but-successful report.

## 10. Open questions / future work

- CI wrapper around the same core (post as a check on PR open).
- Caching call-site search results across lenses to avoid duplicate `gh` queries.
- A `--since <ref>` mode for reviewing an arbitrary diff range.

### 10.1 Noted limitations (accepted for v1)

- **Router triggers err toward inclusion.** Diff-token grep matches comments and string
  literals (`@Transactional` in Javadoc, `"@Column"` in a test string), and `getMessage()`
  fires the Security lens on nearly any exception-handling diff. Over-coverage is safe for a
  *trigger*; we accept the extra lens runs rather than risk under-triggering.
- **`--post` is not idempotent.** Re-running posts a second full review; detecting/replacing a
  prior harness-authored review is deferred.
- **No concurrency / token-budget ceiling.** `--effort=high` fans out all lenses plus one
  skeptic per Major/Minor finding; cost scaling is left to the orchestrator's defaults for now.
- **The "lubomirw grammar" is sketched, not canonical.** §7 captures the rendering shape;
  a full grammar reference (exhaustive severity/dimension layout) is not yet pinned, so
  "exact grammar" fidelity is best-effort until one exists.

### 10.2 Testing gaps to close in the plan

§9 validates lens recall and routing but not the riskiest paths. The implementation plan
should add fixtures for:
- **Error handling (§8):** inject a lens failure → assert the report shows `⚠ … not covered`
  and never reads as clean.
- **`--post` payload (§3.9):** a dry-run asserting the review payload shape, including
  out-of-diff findings re-anchored to in-diff positions (LEFT for `dead-code` on a deletion).
- **Dedup (§3.6):** two lenses flagging the same subject → assert one finding survives,
  regardless of differing anchors.
