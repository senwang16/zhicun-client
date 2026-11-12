# Security Policy

ZhiCun / 智存 is an Android-first personal knowledge capture app with an optional hosted AI gateway.
It is maintained by one individual developer. This policy explains which versions get security fixes,
how to report a problem privately, and what is in scope.

## Supported versions

The project is pre-1.0. Only the latest release receives security fixes; older versions are not
backported.

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Any earlier release | No |
| Unreleased development builds | Best effort, not guaranteed |

If you are on an older build, the fix is to upgrade to the latest release.

## Reporting a vulnerability

Do not open a public issue, pull request, discussion, or social media post with vulnerability details
before a fix is available. That includes proof-of-concept code and screenshots of the problem.

Report privately through GitHub private vulnerability reporting on the repository:
`https://github.com/senwang16/zhicun-client` (Security tab, "Report a vulnerability"). If that
channel is not available to you, contact the maintainer via GitHub and ask for a private channel
without describing the vulnerability in public.

Please include:

- The affected component: Android app, a specific screen or feature, or the official gateway's
  access-control behavior (its source is private; describe the observed behavior instead).
  Name the relevant path or endpoint if you know it.
- The version or commit you tested, and whether it was a release build.
- Reproduction steps, or a proof of concept, with any secrets and real user content removed.
- The impact: what data or capability is exposed, and who can trigger the issue.
- Any suggested fix or mitigation you are aware of.
- Whether you want credit in the eventual advisory.

## Response expectations

This is a single-maintainer, best-effort project with no service-level agreement, no on-call rotation,
and no bug bounty. There is no guaranteed acknowledgement or fix timeline, and a report may sit for a
while before it is triaged. What you can expect:

- An attempt to confirm receipt and to tell you whether the report is accepted or out of scope.
- A fix or mitigation when the issue is confirmed and can be addressed, released as part of the
  normal release flow.
- A request to hold disclosure until a fix has shipped. If a fix will take a long time, that will be
  said plainly rather than left open.

Do not test against infrastructure you do not own. Stand up your own Worker deployment, use your own
API keys, and never touch another user's data, device, or account.

## In scope

Examples of issues that are in scope:

- Key leakage: API keys or gateway tokens exposed in logs, crash reports, backups, preferences stored
  without Keystore encryption, source, build outputs, or gateway responses. Android keys are meant to
  be encrypted with AndroidKeyStore AES/GCM (see `data/settings/KeystoreTokenCipher.kt`).
- Authentication or authorization bypass in the gateway: requests accepted without a valid token,
  tokens usable after revocation, an admin endpoint reachable without authorization, or
  `X-Client-Id` treated as an identity or authorization signal rather than a rate-limit bucket.
- Prompt injection that causes data exfiltration: model output or fetched content that makes the app
  or gateway send user data, files, credentials, or captured content to an attacker-controlled
  destination, or that bypasses a user's explicit consent for cloud AI.
- Unsafe URL fetching and SSRF: gateway code that can be made to fetch internal metadata endpoints,
  private network addresses, or arbitrary URLs through a user-influenced field (for example, a custom
  OpenAI-compatible provider base URL).
- Path traversal in file import or export: crafted file names, archives, or export paths that write
  outside the intended app directory or overwrite unrelated files.
- Logic errors that result in sensitive data being sent to the official gateway when the user has not
  enabled cloud AI, or to a provider other than the one configured.

## Out of scope

Examples of issues that are not in scope for this policy:

- Physical access to an unlocked, already-authenticated device.
- A rooted or otherwise compromised device, or an attacker with full control of the OS or the app's
  private storage.
- Vulnerabilities in third-party AI providers or their APIs. Report those to the provider.
- Denial of service by exhausting your own quota, your own rate limit, or your own Cloudflare account.
- Missing hardening features, such as certificate pinning or screenshots blocked by default, that are
  not claimed anywhere in the repository.
- Reports generated solely by automated scanners without a demonstrated, realistic impact.
- Social engineering of the maintainer or users, and spam or bulk-filed reports.

## Safe harbor

If you make a good-faith effort to comply with this policy while researching and reporting, the
maintainer will not pursue or support legal action against you for that research. Good faith means:

- You test only against your own deployments, devices, and accounts, and use only data you are
  authorized to use. If you encounter real user data, stop and report it instead of exploring it.
- You do not degrade service for others, exfiltrate data, or persist access beyond what is needed to
  demonstrate the issue.
- You give the maintainer a reasonable opportunity to fix the issue before public disclosure, and you
  coordinate the disclosure date.
- You comply with applicable law.

If a third party brings a claim against you for research conducted under this policy, the maintainer
will make the good-faith nature of that work clear.

## Questions

Questions about this policy that do not reveal vulnerability details can go to GitHub Issues. Anything
that could help an attacker should go through private reporting instead.