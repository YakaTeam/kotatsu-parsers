# Security Policy

## Supported Versions

Only the `master` branch receives security fixes. Downstream forks that
re-publish an older snapshot should pull the fix forward on their own.

## Reporting a Vulnerability

Please **do not** open a public issue for security problems.

Use GitHub's private vulnerability reporting:

1. Go to <https://github.com/YakaTeam/kotatsu-parsers/security/advisories/new>
2. Fill in what you found, a minimal reproduction, and the affected version
   (commit SHA or JitPack release tag).
3. Submit.

Only repository maintainers can see the report.

### What's in scope

- Code-execution or sandbox-escape paths in the parser library itself (for
  example: a parser that writes arbitrary files, triggers reflection on
  attacker-controlled class names, or evaluates untrusted scripts in-process).
- Requests made to unintended hosts due to URL handling in the shared
  utilities (`org.koitharu.kotatsu.parsers.util`, `network`, `core`).
- Credential / token leakage through logs, exceptions, or `toString()`.
- Known-vulnerable third-party dependencies shipped by this library.

### What's out of scope

- Behavior of the remote manga sites the parsers target. That's the site's
  responsibility, not ours.
- Missing TLS or weak ciphers on a parsed site.
- CAPTCHA / Cloudflare bypass requests. Parsers go through a normal HTTP
  client and honor the site's protections; marking a parser `@Broken`
  when a site is gated is the intended behavior.
- User-installed forks, third-party apps that embed the library, or
  modifications made outside this repository.

## Response Expectations

This is a volunteer-maintained open-source library. A realistic timeline:

- Acknowledgement of the report within a few days.
- Triage and a rough severity read within two weeks.
- Fix merged to `master` before the advisory is disclosed publicly, where
  practical.

If a reported issue is fundamentally a remote-site problem or a downstream
app problem, we will say so and close the advisory with an explanation
rather than silently drop it.
