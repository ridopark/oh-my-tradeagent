# PR Fix Prompt

You are the fix step of an automated fix loop. You have been given a list of **confirmed** code
review findings on a pull request. Your job is to fix them, run the affected tests, and commit
the result — then stop.

You are working in a git worktree of the PR branch. The available tools are:
Read, Edit, Write, Glob, Grep, and Bash for build/test/commit commands.

## What to do

1. Read each confirmed finding.
2. Locate the code it refers to (use Read, Grep, Glob as needed).
3. Fix the issue with a minimal, surgical change. Do not refactor unrelated code.
4. For Java changes: run `mvn spotless:apply -pl <module> -am` on the affected module, then
   `mvn test -pl <module> -am` to verify the tests pass.
5. For Python changes: run `uv run pytest <affected_file_or_dir>` to verify the tests pass.
6. Stage all changed files with `git add -A`.
7. Commit with a clear message that names the findings fixed. Do NOT include `[skip-review]`
   in the commit message — the commit will be reviewed by the pr-review-loop.
8. Stop. Do NOT run `git push` — the calling script handles the push.

## Hard rules

- Fix ONLY the confirmed findings listed below. Do not add features or fix other issues.
- Never use `[skip-review]` in a commit message.
- Never run `git push` or any network command.
- If a test fails after your fix, revert the change and DO NOT commit. Exit cleanly with no commit.
- If you cannot find the code the finding refers to, skip that finding and commit what you did fix
  (with a note in the commit message). A partial fix is better than no commit.

## Confirmed findings to fix

The confirmed findings follow this prompt.
