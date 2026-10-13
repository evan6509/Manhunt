# Repository workflow

- Minecraft target: Fabric 26.2, Java 25.
- Optional network deployment: Velocity 4.0, two Fabric backends and Docker.
  Keep standalone mode available with the network bridge disabled by default.
- Do routine work, edits, and commits on `codex/development`.
- `main` is the integration and release branch. Never commit changes directly
  to `main`. Use a reviewed pull request from `codex/development` into `main`.
- Preserve existing work. Never reset branches or discard unrelated edits.
- Push development work when requested. Do not merge a pull request, push a
  merged `main`, create release tags, or publish a release without explicit user
  authorization. A successful push to `main` automatically publishes a release.
- Continue working on `codex/development` after a merge; bring reviewed `main`
  history back into it when necessary without rewriting shared commits.
- Install the local direct-commit guard in each new clone with
  `git config core.hooksPath scripts/git-hooks`. It rejects ordinary commits on
  `main`; actual merge commits can proceed after user authorization.
- GitHub refused private-repository branch protection on the current account
  plan. Keep the repository private and follow the PR policy; do not assume
  GitHub is enforcing it remotely.

# Validation

- Run `python3 -m unittest discover -s scripts -p 'test_*.py' -v` for release
  automation changes.
- Run `./gradlew --no-daemon build runGametest` with Java 25, then
  `python3 scripts/verify_mod_jar.py` before publishing build changes.
- Use `MANHUNT_VERSION=1.0.1` to check an automatically selected release version
  without editing `gradle.properties`. Clean the build when changing versions.
- Keep test/build/JAR evidence separate from connected-player gameplay and
  remote Actions/release evidence. Never claim a release was published unless
  GitHub confirms it and its assets.
- Network changes require `:velocity-plugin:test` (included in `build`) and
  `python3 -m unittest discover -s docker -p 'test_*.py' -v`. Test incomplete
  transfers, generation matching, checkpoint recovery, and runner selection.
- Never archive/reset a backend until every transfer succeeds, the source is
  empty, and the backend acknowledges the exact completed world/run IDs.
  Ordinary crashes preserve worlds. Archive only the complete `world/` after
  the JVM exits; retain operator/configuration files and proxy history.
- The next runner excludes the preceding runner and prefers players with fewer
  runner turns, choosing randomly among ties. Auto-start waits for two players.

# Release automation

- PRs and development pushes build and test; only successful pushes to `main`
  publish releases. Manual workflow dispatch builds and tests only.
- Releases start at `v1.0.0` and increment the patch version. The selected
  version must match the JAR filename and `fabric.mod.json`.
- Serialize version selection through publication. Retrying the same commit
  must reuse its release rather than replace a published release's assets.
- Include the Fabric JAR, Velocity plugin, Docker source ZIP, `CHANGELOG.md`,
  and `SHA256SUMS`. Changelogs must
  include direct commits as well as GitHub-generated PR notes.
