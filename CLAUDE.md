# Claude + Codex review workflow

## Temporary user-authorized exception

From 2026-10-23 06:03:50 UTC through 2026-10-25 06:03:50 UTC
(October 25, 2026 at 2:03:50 a.m. America/New_York), Codex may implement
changes and edit files in this repository. The user authorized this two-day
override of the review-only restriction. All other project instructions remain
in effect. After this window expires, the review-only restriction below resumes.

Claude Code implements; Codex CLI reviews. After implementing and committing a change in
this repo, run the Codex review before reporting the work done:

    powershell -File .vscode/scripts/codex-review.ps1              # reviews last commit (HEAD)
    powershell -File .vscode/scripts/codex-review.ps1 -Uncommitted # reviews working-tree changes
    powershell -File .vscode/scripts/codex-review.ps1 -Base main   # reviews branch vs. main

Findings are printed and saved to `.vscode/scripts/codex-review-latest.md`. Fix anything
real, then re-run to confirm. Codex only reviews in this workflow — it never edits files.

VS Code tasks `codex-review-last-commit` and `codex-review-uncommitted` run the same thing
for manual use (Terminal → Run Task).
