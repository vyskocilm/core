# Curate-and-Post Findings — Design Spec

**Date:** 2026-06-01
**Status:** DRAFT — brainstorming in progress; pending final user approval before writing the implementation plan
**Author:** Ivo Raisr (with Claude Code)
**Builds on:** `docs/superpowers/specs/2026-05-31-pr-review-harness-design.md` (the `review-pr` skill)

## 1. Motivation

`review-pr` produces verified findings and can `--post` them, but `--post` posts the
**entire** verified set in one shot. The real workflow is: Claude presents the verified
findings, the author reads and **keeps a subset** they care about, and wants *those*
transferred to the PR — **without manually transcribing them**. This spec closes that
loop with a human-curation gate between the presented findings and the post.

The action being added is **transfer the curated subset to the PR** (post as one batched
review). It is **not** auto-fixing code.

## 2. Goals / Non-goals

**Goals**
- After a review, present findings as a **numbered list** in the conversation.
- Let the author pick a subset **conversationally** (`"1, 3"`, `"all majors"`, `"all but nitpicks"`).
- Resolve the pick with a **tested, deterministic selector** (no model-guessed parsing).
- Post **only the picked subset** as one batched PR review, via the existing post path.
- Posting requires an **existing PR**; refuse otherwise.

**Non-goals (v1, explicitly deferred)**
- Auto-fixing / auto-applying findings to code.
- Rewording or editing a finding's text before posting.
- Creating the PR (`gh pr create`) as part of the flow.
- Posting when no PR exists (no "hold for later" queue).
- Per-finding interactive accept/reject prompts (the conversational pick replaces this).

## 3. Architecture

A tail-extension to the `review-pr` pipeline (its steps 6–7), **not** a new skill. Steps 1–5
(acquire → route → lens fan-out → synthesis → verify) are unchanged. The change:

```
verified.json
  → harness list           (present numbered findings in chat)
  → author picks subset    (conversational)
  → harness select --pick  (tested expression parse → subset)
  → subset.json
  → harness post --dry-run (preview)  → [author confirms] → harness post  (one review)
```

Posting still requires an existing PR (branch mode with no PR ⇒ refuse, unchanged).

## 4. Components

### 4.1 `harness/selection.py` (new, pure, tested)
`select(findings, expr) -> subset` — parses a selection expression against the findings in
**canonical order** and returns the chosen subset (order preserved). Pure function; the
error-prone parsing logic is unit-tested.

### 4.2 `harness list --findings F` (new CLI subcommand)
Prints the numbered canonical list — one line per finding: `N. <glyph> <Severity> | <Dimension>  file:line — title`.
For in-chat presentation. Numbering derives from the existing `render.order_findings`.

### 4.3 `harness select --findings F --pick "EXPR"` (new CLI subcommand)
Emits `{ "findings": [ ... ] }` for the chosen subset, fed straight into the existing
`harness post`. Numbering derives from the **same** `render.order_findings`, so the numbers
the author sees in `list` equal the numbers `select` resolves (no drift).

### 4.4 SKILL.md step 7 (modified)
After render: run `harness list` → present the numbered findings → ask which to post (require
a PR) → run `harness select --pick "<author reply>"` → `harness post --dry-run` preview →
on confirmation, live `harness post` of the subset.

## 5. Selection grammar (`EXPR`)

Case-insensitive; comma/space-separated tokens; **union** semantics across tokens.

| Form | Meaning |
|---|---|
| `3`, `1,4` | findings #3, #1, #4 |
| `2-5` | findings 2 through 5 |
| `major[s]` / `minor[s]` / `nitpick[s]` | all findings of that severity |
| `blocking` | all findings with `blocking: true` |
| `all` | every finding |
| `none` | empty set |
| `all except <tokens>` / `all but <tokens>` | start from the set built before `except`/`but`, subtract the named tokens (e.g. `all except nitpicks`, `all but 2,4`) |

**Fail loud:** an out-of-range index (`< 1` or `> N`) or an unknown token ⇒ non-zero exit
with a clear message listing valid forms. **Never** silently fall back to `all` on a parse
error. (Dimension keywords are out of scope for v1 — severity only.)

## 6. Data flow

`verified.json → harness list (numbered presentation) → author picks → harness select --pick "EXPR" → subset.json → harness post --dry-run (preview) → [author go] → harness post (one batched review)`

Numbering source of truth: `render.order_findings` (shared by `list` and `select`).

## 7. Error handling

- **No PR (branch mode):** refuse before any posting (unchanged from `review-pr` §8).
- **Unparseable / out-of-range pick:** show the error, re-ask; do not post.
- **Empty selection (`none`):** post nothing; report that nothing was posted.

## 8. Testing strategy

- **Selector unit tests:** indices; ranges; each severity word; `blocking`; `all`/`none`;
  `except`/`but` exclusions; union + dedup across tokens; out-of-range index ⇒ error;
  unknown token ⇒ error; empty (`none`) ⇒ empty subset.
- **`list` numbering:** matches `render.order_findings` canonical order.
- **Integration:** `list → select → post` yields a payload containing exactly the picked
  findings (and no others), with anchors resolved as in `review-pr` §3.9.

## 9. Open questions (to confirm before the plan)

- Selection grammar surface — is severity-only (no dimension keywords) acceptable for v1? (proposed: yes)
- Keep it selection-only (no per-finding wording edits before posting)? (proposed: yes — non-goal)
- Should the persisted report also carry the index numbers, or only the in-chat `list` output? (proposed: in-chat `list` only; report grammar unchanged)
