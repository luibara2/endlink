# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub's
[security advisory form](https://github.com/luibara2/endlink/security/advisories/new). That opens a
channel only you and the maintainer can see, and lets a fix exist before the problem is public.

Tell me what you can: what the flaw allows, how to reproduce it, and which versions you tested. A
proof of concept helps but is not required to report.

## What is in scope

Endlink stands between players and backend servers and owns authentication for the whole network, so
these matter most:

- **Bypassing authentication** — joining as another player, or joining without a valid Xbox login.
- **Bypassing backend verification** — reaching a backend directly, or forging a join that
  EndlinkGuard accepts, without the shared secret.
- **Privilege escalation** — running an admin command without the permission for it.
- **Remote crashes or resource exhaustion** reachable by an unauthenticated client, beyond what the
  configured rate limits are meant to allow.
- **Leaking one player's data to another**, including XUIDs and IP addresses.

## What is not

- Anything requiring a backend to be reachable directly from the internet. Backends run in offline
  mode by design and are expected to be firewalled to the proxy; EndlinkGuard is the second layer,
  not the first.
- Anything requiring local code execution on the proxy host. An attacker there has already won:
  the config holds the shared secret, and the loopback listener trusts local addons by design.
- Bugs in Minecraft, Bedrock Dedicated Server or Endstone themselves — report those upstream.
- The older-version translation chain, which is documented as incomplete and unsupported.

## Supported versions

This is a work in progress with no long-term support branches. Fixes land on `main` and go out in the
next release. Only the latest release is supported.
