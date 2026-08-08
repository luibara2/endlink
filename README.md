# Endlink

[![Build](https://github.com/luibara2/endlink/actions/workflows/build.yml/badge.svg)](https://github.com/luibara2/endlink/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/luibara2/endlink?include_prereleases&sort=semver)](https://github.com/luibara2/endlink/releases)
[![Licence](https://img.shields.io/github/license/luibara2/endlink)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft%20Bedrock-1.26.40-brightgreen)](https://github.com/luibara2/endlink#versions-run-the-latest-on-both-ends)

A Velocity-style proxy for Minecraft: Bedrock with Endstone/BDS backends. Players stay connected to
the proxy while switching between backend servers; the proxy owns authentication and forges offline
logins to the backends.

By analogy: an Endstone server is Paper, and Endlink is Velocity.

```
java -jar Endlink.jar [config.properties]
```

On first start it writes a fully documented `config.properties` and creates a `plugins/` folder.

> ### Status: work in progress
>
> Endlink is under active development and not finished. Expect rough edges, and expect things to
> change.
>
> **What is tested and working: a Minecraft 1.26.40 client against a 1.26.40 backend.** That pairing
> is exercised properly — a player joins, plays, and switches between backends. Anything else is
> less certain, and the older-version translation chain in particular is incomplete (see
> [Versions](#versions-run-the-latest-on-both-ends)).
>
> If you run it, run it on the current Minecraft release on both ends.

## What it does

- **One connection, many servers.** Players switch backends without reconnecting, keeping their
  session, identity and permissions.
- **The proxy owns authentication.** Backends run in offline mode behind it and are secured by
  EndlinkGuard, which rejects any join that did not come through the proxy.
- **A version-translation layer** for the window where a backend lags a Minecraft release, through a
  registry of adjacent-version translators that chain across longer gaps. Read the note on versions
  below before relying on it.
- **Failover and forced hosts.** A dead backend moves players rather than dropping them; a hostname
  can route to a specific backend.
- **Rate limiting and abuse controls** on the public listener: per-address session caps, connection
  attempt windows, RakNet packet limits and optional connection cookies.
- **An addon API.** Anything in `plugins/` is discovered at startup and can extend the proxy. With
  an empty `plugins/` folder, Endlink is exactly a Bedrock proxy and nothing else.

## Versions: run the latest, on both ends

**Keep clients and backends on the current Minecraft release.** That is the configuration Endlink is
built for, tested in, and the only one that is expected to work properly.

The translator chain that lets an older client onto a newer backend, or the reverse, exists because
backends lag a release by days or weeks after an update. It is useful for exactly that gap. Beyond
it, **the older-version chain is unstable and incomplete, and it is not going to be finished.**

That is a deliberate decision rather than a backlog item. Bedrock updates itself, there is no
supported way for an ordinary player to stay on an old version, so a version-locked player is not a
real audience to build for. Effort spent there buys nothing that keeping current does not.

Practically: after a Minecraft update, update the backends promptly. `backend.protocol=auto` reads
the version from each backend rather than trusting a pinned value, so the proxy follows them without
a config change — the pinned-value route is how version skew gets introduced by accident.

## Configuration

Everything lives in one `config.properties`, generated with comments explaining each setting — the
file the jar writes is byte-for-byte the documented template, so the copy on a server is its own
documentation. `config.example.properties` here is that template.

The settings worth knowing before a first run:

| Setting | Why |
| --- | --- |
| `backends` / `backend.<name>.host` | The servers players can be sent to |
| `backendVerification.sharedSecret` | Must match EndlinkGuard's `shared_secret` on every backend |
| `permissions.admins` | XUIDs allowed to run `/send`, `/alert`, `/glist`, `/perm` |
| `backend.protocol` | Leave `auto` — it reads the version from the backend rather than trusting a pinned value |

## Backends

**Use [Endstone](https://github.com/EndstoneMC/endstone) as the backend server, with
[EndlinkGuard](https://github.com/luibara2/endlinkguard) installed on it.** That is the combination
Endlink is built and tested against.

Endstone is a plugin-capable server built on Bedrock Dedicated Server, which is what makes the
backend side of this possible: EndlinkGuard needs to run code during the join to check it.

EndlinkGuard is not optional in any real deployment. Endlink puts backends into offline mode so it
can own authentication — that is what lets one session move between servers — and a backend in
offline mode with nothing guarding it is open to anyone who learns its address. EndlinkGuard verifies
every join against the proxy over an HMAC-signed handshake and rejects the rest.

Set `backendVerification.sharedSecret` here and `shared_secret` in EndlinkGuard's `config.toml` to
the same value. Mismatch fails closed: proxied joins are rejected and both sides say so in the log.

## Building

Needs a JDK 21+ in `JAVA_HOME`. From this directory:

```
gradle build
```

`dist/Endlink.jar` is the deployable artifact. `gradle test` runs the suite. Nothing else is needed —
the Bedrock codecs and the RakNet transport are vendored under `protocol/` and `network/`, so a fresh
clone builds on its own.

**On Windows, clone with long paths enabled.** The vendored codecs nest deeply enough to exceed the
260-character `MAX_PATH` limit, and git fails partway through with `Filename too long`:

```
git -c core.longpaths=true clone https://github.com/luibara2/endlink.git
```

Or enable it once for good: `git config --global core.longpaths true`.

## Related

| | |
| --- | --- |
| [EndlinkGuard](https://github.com/luibara2/endlinkguard) | The backend plugin. Verifies proxy joins and rejects direct ones — install it on every backend |
| [Endstone](https://github.com/EndstoneMC/endstone) | The recommended backend server: plugin-capable Bedrock Dedicated Server |

## Licence

Endlink is licensed under the **Apache License 2.0** — see `LICENSE`. Use it, fork it, ship it,
including commercially; keep the notice and state what you changed.

It builds on Apache-2.0 work and keeps their notices:

| | |
| --- | --- |
| [CloudburstMC Protocol](https://github.com/CloudburstMC/Protocol) | Apache 2.0 — the Bedrock codecs, via a fork carrying additional hand-written ones |
| [Netty](https://netty.io), [jose4j](https://bitbucket.org/b_c/jose4j) | Apache 2.0 |
| [Endstone](https://github.com/EndstoneMC/endstone) | Apache 2.0 — the recommended backend server |

**No GPL code is used here, deliberately.** Anything built on GPL-3.0 libraries lives in a separate
addon rather than in this repository, so that Endlink itself can stay permissively licensed. Bundling
or linking such code here would place Endlink under GPL-3.0 too.
