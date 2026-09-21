# Claude + Codex review workflow

Claude Code implements; Codex CLI reviews. After implementing and committing a change in
this repo, run the Codex review before reporting the work done:

    powershell -File .vscode/scripts/codex-review.ps1              # reviews last commit (HEAD)
    powershell -File .vscode/scripts/codex-review.ps1 -Uncommitted # reviews working-tree changes
    powershell -File .vscode/scripts/codex-review.ps1 -Base main   # reviews branch vs. main

Findings are printed and saved to `.vscode/scripts/codex-review-latest.md`. Fix anything
real, then re-run to confirm. Codex only reviews in this workflow — it never edits files.

VS Code tasks `codex-review-last-commit` and `codex-review-uncommitted` run the same thing
for manual use (Terminal → Run Task).
