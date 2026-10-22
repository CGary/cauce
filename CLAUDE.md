# CLAUDE.md

@AGENTS.md

Project-specific notes for Claude Code on top of AGENTS.md:

- First session on a fresh clone with empty `android/`, `server/`, `web/`: follow `BOOTSTRAP.md` phase by phase. Stop at each phase gate and report.
- `/feature <id>` and `/new-feature <id>` are the only two entry points for feature work. Use them; do not improvise a workflow.
- Builds are slow on the owner's machine. Prefer JVM unit tests (`:core:*`) and `go test` over emulator or full APK builds. Build the APK only when there is UI to look at.
- Never commit secrets. `.env`, `*.secret`, Cloudflare tokens and R2 keys are gitignored; `server/.env.example` documents the variables.

## Before any push: sensitive-information check

The repository is PUBLIC. Before every `git push`, and before creating or making any repository public, review everything that will be published.
Review `git diff origin/main..HEAD`, or the full history on the first push, plus untracked files about to be added.
Check for secrets: API keys, tokens, passwords, Cloudflare/R2 credentials, private keys, keystores, and `.env` contents.
Check for personal data: real name and email, phone, home address, ID numbers, location history, health or financial records.
Check for home network details: public IP, tunnel hostnames, private domains, and Cloudflare account or zone IDs.
Fixtures must use invented data, never real personal data.
If anything is found, do not push; remove it, rewrite history if it was committed, and report it to the owner.
Commits must use the GitHub noreply email, not a personal email.
