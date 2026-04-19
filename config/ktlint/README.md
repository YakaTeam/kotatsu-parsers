# ktlint configuration

This directory holds the [ktlint](https://pinterest.github.io/ktlint/) baseline
used by the `ktlint` CI workflow (`.github/workflows/ktlint.yml`).

## Why a baseline

ktlint was added to an existing codebase. A one-off pass produced tens of
thousands of style deviations — most of them indentation and wrapping
differences that predate any automated linting on the project. Rather than
reformatting every file at once (which would rewrite history across nearly
every parser and create merge conflicts for every open PR), we snapshot the
current state in `baseline.xml` and enforce ktlint only for **new or
regressed** violations introduced by future commits.

## How it works

The workflow runs on every PR that touches Kotlin sources, the editorconfig,
the baseline file, or the workflow itself. It downloads the pinned ktlint
release and runs:

```
ktlint --editorconfig=.editorconfig \
       --baseline=config/ktlint/baseline.xml \
       --reporter=plain,group_by_file=true \
       "src/**/*.kt"
```

Violations already recorded in `baseline.xml` are ignored; anything else fails
the job.

## Running locally

```bash
# macOS / Linux
curl -sSLo ktlint https://github.com/pinterest/ktlint/releases/download/1.7.1/ktlint
chmod +x ktlint
./ktlint --editorconfig=.editorconfig \
         --baseline=config/ktlint/baseline.xml \
         "src/**/*.kt"
```

Use `--format` to auto-fix what ktlint can fix (do not commit bulk
reformatting without coordinating — it invalidates the baseline).

## Regenerating the baseline

Only needed after a deliberate cleanup pass or after bumping `KTLINT_VERSION`
in the workflow. From the repo root:

```bash
ktlint --editorconfig=.editorconfig \
       --reporter=baseline,output=config/ktlint/baseline.xml \
       "src/**/*.kt"
```

The checked-in baseline is expected to shrink over time as violations are
cleaned up in small, targeted PRs.
