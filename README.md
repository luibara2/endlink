# Endlink

[![Build](https://github.com/luibara2/endlink/actions/workflows/build.yml/badge.svg)](https://github.com/luibara2/endlink/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/luibara2/endlink?include_prereleases&sort=semver)](https://github.com/luibara2/endlink/releases)
[![Licence](https://img.shields.io/github/license/luibara2/endlink)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft%20Bedrock-1.26.51-brightgreen)](https://github.com/luibara2/endlink#versions-run-the-latest-on-both-ends)

A Velocity-style proxy for Minecraft: Bedrock with Endstone/BDS backends. Players stay connected to
the proxy while switching between backend servers; the proxy owns authentication and forges offline
logins to the backends.

By analogy: an Endstone server is Paper, and Endlink is Velocity.

```
java -jar Endlink.jar [config.properties]
```

On first start it writes a fully documented `config.properties` and creates a `plugins/` folder.

> ### Status: in production on one server, still young
>
> **Proven in real use:** Minecraft 1.26.40 through 1.26.51 clients against 1.26.40 through 1.26.45
> backends, including a 1.26.45 client and a 1.26.44 client on the same backend at once. Players
> join, play, and switch between backends keeping their session, identity and permissions. That has
> run a live server at around ten concurrent players.
>
> **New in v0.5.3: PowerNukkitX backends, played on.** A player who met a BDS or Endstone backend
> first and then switched to a PowerNukkitX one sat on *Building terrain* for good, or fell through
> an empty world. BDS has the client request terrain a sub-chunk at a time and the client keeps that
> mode for the session; PowerNukkitX sends whole chunks and never answers the requests. Endlink now
> learns which kind each backend is from its first chunk and reaches a whole-chunk backend by
> reconnect, the same way it already reaches a Java server through Geyser. Also fixed: translated
> chat from any backend arriving as raw keys (*%multiplayer.player.joined*), new players landing at
> a backend's y=32768 staging point after a switch, and a chunk occasionally lost at the end of one.
> Tested with a real 1.26.51 client against a PowerNukkitX 3.0.5 server behind an Endstone hub.
>
> **In v0.5.2: the protocol number 1.26.50 actually shipped with.** v0.5.1 was built against
> Preview 26.50.27, which asks for **2192**. The stable release renumbered to **2193** on the way
> out, so v0.5.1 refused every real 1.26.50 and 1.26.51 player at the door with *client protocol
> 2193, proxy speaks up to 2192*. Nothing else about 1.26.50 changed across the renumber — Mojang's
> own schema dump for the preview against the one for the release differs in two files, a README and
> that number — so everything below is as it was, under the number clients send. **Anyone running
> v0.5.1 should upgrade**; there is no configuration that works around it.
>
> **In v0.5.1: Minecraft 1.26.50 and 1.26.51, played on.** 1.26.50 renumbered the protocol
> again and this time the format really moved — thirteen packets changed shape and two are new — and
> it also added properties to 139 block types, which is the part that broke worlds rather than
> connections. A block's network id is a hash of its state, so every stair, fence, glass pane, iron
> and copper bar and trip wire became a number the other side had never heard of, and a client with
> no block for an id draws air: the first 1.26.50 player through an untranslated proxy saw none of
> them at all. Endlink renumbers them in the chunk data itself, and computes the states the older
> backend cannot send — a stair's corner shape, and which way a fence, pane or bar reaches out.
> Confirmed in play on a 1.26.51 client against a live 1.26.44 backend, and checked against a
> 1.26.45 client standing in the same place. See
> [1.26.50 clients on older backends](#12650-clients-on-older-backends).
>
> **In v0.5.0:** Minecraft 1.26.45, protocol 2169. Mojang renumbered the protocol in a hotfix
> for a single field, and server software has not followed — so a 1.26.45 client on a 1.26.44
> backend is the configuration to expect for as long as that lasts, and it needs no configuration
> here. Confirmed on a live server. See
> [1.26.45 clients on 1.26.44 backends](#12645-clients-on-12644-backends).
>
> **Also in v0.5.0, not confirmed in play:** a fix for a mount that stops taking input after a
> backend switch ([#1](https://github.com/luibara2/endlink/issues/1)). The cause was found by
> reading the relay and the fix is covered by tests, but nobody has ridden a horse across a switch
> to confirm it. If mounts still misbehave after a switch, that issue is where to say so.
>
> **Verified but not yet at scale (since v0.2.0):** the cross-backend item, entity and block
> registries, resource packs loaded from unpacked folders, and backend packs cached and served by the
> proxy. Confirmed by hand with a handful of players, not under a full server.
>
> **Deliberately unfinished:** the older-version translation chain (see
> [Versions](#versions-run-the-latest-on-both-ends)). Run the current Minecraft release on both ends.

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
- **Addon content that survives the switch.** Bedrock reads a client's resource packs, item registry,
  entity list and block definitions once, at level init, and a seamless switch does not re-run it —
  so without help, a backend's custom items draw arbitrary vanilla textures and its custom entities
  and blocks are invisible to anyone who arrived from somewhere else. Endlink serves packs from
  `resourcePacks.dir` and caches each backend's own packs as it learns them, and gives every client
  the combined registries of all backends, translating each backend's network ids to match. See
  `resourcePacks.cacheBackendPacks` and `crossBackendPalette` in the config.
- **Rate limiting and abuse controls** on the public listener: per-address session caps, connection
  attempt windows, RakNet packet limits and optional connection cookies.
- **An addon API.** Anything in `plugins/` is discovered at startup and can extend the proxy. With
  an empty `plugins/` folder, Endlink is exactly a Bedrock proxy and nothing else.
- **Minecraft: Java Edition players — in beta**, through the optional
  [ViaEndlink](https://github.com/luibara2/viaendlink) addon. Java clients join, chat and walk
  around; they cannot open a chest yet. Read its status notice before relying on it. Endlink itself
  does not need it and knows nothing about Java.
- **Minecraft: Java *servers* as backends**, through a Geyser instance in front of one. Bedrock
  players can be sent to a Java server alongside the Bedrock ones, keeping their identity and their
  player data. Moving across that boundary reconnects them to the proxy rather than handing them
  over, for a reason that cannot be worked around; see
  [Minecraft: Java backends](#minecraft-java-backends).

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

### 1.26.45 clients on 1.26.44 backends

This is the gap right now, and it is the one the rule above is about. Minecraft 1.26.45 is a hotfix
that renumbered the protocol from 2168 to 2169, while server software — Endstone included — is still
built against 1.26.44. Clients update themselves; backends do not.

Endlink speaks both, so a 1.26.45 player reaches a 1.26.44 backend with nothing to configure. The
two protocols differ in exactly one field, a scoreboard removal, and the proxy converts it on the
way through. Leave `backend.protocol=auto` and it resolves the pair on its own.

This is the supported short-term gap, not a reason to stay on 1.26.44 — move the backends up when
their server software does.

### 1.26.50 clients on older backends

Minecraft 1.26.50 renumbered the protocol again, 2169 to 2193. Unlike the 1.26.45 hotfix above this
is a real format change — thirteen packets moved and two are new — so it is not a pair of numbers
for one wire format, and the proxy translates across it in earnest rather than passing packets
through.

Endlink speaks 2193, so a 1.26.50 or 1.26.51 player reaches a 1.26.45, 1.26.44 or 1.26.40
backend with nothing to configure. Leave `backend.protocol=auto` and it resolves the pair itself.

**2192 was the preview's number, not the release's.** Mojang's dumps for Preview 26.50.27 and for
stable 1.26.50.5 differ in two files: a README and the protocol number. Everything written against
the preview was therefore correct except the one number that decides whether a player is let in at
all, and a proxy pinned to 2192 refuses every real 1.26.50 client with `LOGIN_FAILED_SERVER_OLD`.
If you are reading a codec, a changelog or a third-party table that says 1.26.50 is 2192, it was
written before the release shipped.

**Blocks are translated across this step, not just packets.** 1.26.50 added properties to 139 block
types — every stair gained a corner state, and every fence, glass pane, iron and copper bar and trip
wire gained four connection states. A block's network id is a hash of its state, so each of those
became a number the other side has never heard of, and a client with no block for an id draws air:
the first 1.26.50 player through an untranslated proxy saw no stairs, no fences, no panes and no bars
at all. Endlink renumbers them in the chunk data itself, in both directions, from a table diffed out
of Mojang's own per-version block metadata.

**Stair corner shapes are computed, not defaulted.** The corner state is derived from surrounding
stairs, so a backend that has no such state cannot send one, and leaving it empty renders every inner
and outer corner in the world as a straight stair facing the wrong way. Endlink works the shape out
from the stairs around each block, using the same rule Java Edition uses — which is the rule Mojang
says the new state exists for parity with. Stairs on a chunk's outer edge are left straight, because
the neighbouring chunk may not have arrived.

**Fence, pane and bar connections are computed too**, by the same rule and in the same place, and
with the whole of Java's rule: a fence connects to a fence of the same woodiness, a pane or bar to any
other pane or bar, and either also connects to a block whose face beside it is solid. That last
clause is what makes a glass pane set into a stone window frame connect to the frame, and it is
answered from a generated table of Java's own `connectsTo` and `attachsTo` run against every block
state — because these states exist for parity with Java, so Java's answers are the right ones.

Going the other way, a state the older version cannot express — an inner-corner stair, a fence
connected on two sides — arrives as the plain block, which is what that version drew for itself
before these were block states. Blocks 1.26.50 did not change keep their id untouched.

**The other direction is covered too.** Once the backends move to 1.26.50, a player who has not
updated would otherwise have no path through the version graph at all and be refused at the door.
1.26.45 clients can reach a 1.26.50 backend, with the same block table read the other way. This is
the one upgrade edge the proxy owns; every other one belongs to an addon.

**Tested, but only partly played on.** The codec comes from CloudburstMC's, built against Mojang's
preview 26.50.27. The block translation was written after a 26.50.27 player reported the missing
stairs, and it is covered by tests down to the chunk payload — but a release client has not been
through every path of it. If something is wrong with a 1.26.50 join, that is where to look first.

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
| `commands.enabled` | Set `false` to disable all proxy command injection and handling, including qualified `/proxy:*` forms; backend commands are forwarded unchanged |
| `backend.protocol` | Leave `auto` — it reads the version from the backend rather than trusting a pinned value |
| `network.udp.*BufferBytes` | Kernel buffers for the shared listener and backend sockets. Endlink warns when the OS caps them; on Linux, raise `net.core.rmem_max` / `net.core.wmem_max` above the configured values to avoid RakNet retransmission stalls under bursts |
| `publicAddress` | Only for networks with a Java backend: the address players are sent back to when a move needs a reconnect. Empty uses the address each player connected with, which is usually right |
| `resourcePacks.dir` | Packs every client gets at login, as `.mcpack` files or unpacked folders |
| `resourcePacks.cacheBackendPacks` | Learns each backend's packs into `cache/packs` and serves them itself, so packs work after a switch without copying them into the directory above. The first player to switch to an unlearned backend waits for one download. A pack edited on a backend is noticed and re-learned even when its `manifest.json` version does not change |
| `crossBackendPalette` | Leave on if any backend has custom items or entities — it is what keeps them rendering after a switch. The proxy learns each backend's registry on the first visit and caches it in `cache/backend-palettes.nbt`; after an addon change, the first player there sees the old registry until they rejoin |

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

### Minecraft: Java backends

A backend can also be a **Java server**, reached through a [Geyser](https://geysermc.org) instance
that the proxy treats as an ordinary Bedrock backend. Bedrock players join Endlink as usual and can
be sent to it alongside the Bedrock ones. Install
[EndlinkGuard's `geyser/` build](https://github.com/luibara2/endlinkguard) into that Geyser's
`extensions/` folder — it does the same job there as the Endstone build does on a Bedrock backend,
and additionally carries the player's real IP through to the Java server.

Nothing in Endlink's config needs setting for this. There is one behaviour to know about:

**Moving to or from a Java backend reconnects the player instead of handing them over.** A Bedrock
client reads its block-id scheme from the StartGame it logs in with and cannot be told otherwise
while it is playing. Bedrock servers hash block ids from the block state; Geyser numbers them by
palette position. A seamless handoff across that boundary delivers chunks the client cannot decode —
the player would stand in an empty or scrambled world with nothing in any log to say why.

So Endlink learns each backend's scheme from its first StartGame, remembers it, and moves players
across that boundary by transferring them back to **its own address** and putting them where they
asked. The player never leaves the proxy: same listener, same identity check, same permissions, and
backends stay unreachable from outside. They see a loading screen. Switches between backends of the
same kind are seamless as before.

The same reconnect is used for a Bedrock backend that sends whole chunks, such as **PowerNukkitX**.
BDS and Endstone have the client request terrain a sub-chunk at a time, and a client keeps that mode
for its whole session; handed seamlessly to a server that never answers those requests, it waits on
"Building terrain" in an empty world. Endlink learns this from each backend's first chunk too, so a
player who met a BDS backend first reaches a whole-chunk one by reconnect. The way back is seamless.

That transfer needs an address to send the client back to. By default the proxy uses whatever address
each player connected with, which is correct per player and needs no configuration; set
`publicAddress` when that is not good enough.

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
| [ViaEndlink](https://github.com/luibara2/viaendlink) | **Beta.** Optional addon for Minecraft: Java Edition *players* joining Bedrock backends. Joining, chat and terrain work; containers do not — read its status notice first |
| [Geyser](https://geysermc.org) | Not an addon: run one in front of a Java *server* to offer it as a backend. See [Minecraft: Java backends](#minecraft-java-backends) |
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
