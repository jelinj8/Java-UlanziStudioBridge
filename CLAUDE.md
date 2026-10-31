# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Lets external applications drive the slots of an Ulanzi deck (D200X, D100) through Ulanzi Studio. FreeCAD integration is a goal, so nothing in the bridge may know about a specific client.

```
Ulanzi Studio <-ws:3906-> bridge plugin (Node) <-ws:39380-> apps (Java client, others)
```

- `bridge-plugin/`: the Studio plugin. A Java process can't be a Studio plugin, so this has to be Node. `plugin/bridge.js` holds the logic with no I/O; `plugin/app.js` wires up the sockets. Pages live here so every client reuses them.
- `ulanzi-client/`: Java protocol client. Must stay free of runtime dependencies (no Spring, JavaFX, or logging library), hence its own JSON codec.
- `ulanzi-client-fx/`: pages of `UIActionBase` bindings, from `cz.bliksoft.javautils.app.ui.actions` in `../BSToolbox-jfx` (`common-java-utils-jfx` `0.8`, `provided` scope, so the client app brings its own version). That dependency stays out of `ulanzi-client`. Its tests start the JavaFX toolkit, so they need a desktop session.
- `docs/xml-pages.md`: `UlanziStudioPlugin` loads pages from the BSToolbox XML filesystem (`RemoteControl/UlanziDeck/<appId>/pages`).
- `docs/packaging.md`: packaging an app with `jpackage` so Studio can give it its own profile.
- `probe/`: development plugin that logs raw Studio messages.
- `docs/protocol.md`: the app protocol, and Studio behaviour measured on real hardware. It overrides the public SDK docs, which lag the installed Studio.

## Commands

```
mvn test                                 # Java tests
mvn test -pl ulanzi-client -Dtest=DeckClientTest#sendsCommandsInOrder
node --test test/                        # bridge tests, run from the plugin folder
```

Run bridge tests with Studio's Node (`C:\Program Files (x86)\Ulanzi Studio\nodejs\node.exe`, v20.12.2), since that's what runs the plugin.

Try the client on a real deck (`default` is the slots' appId, 60 the seconds to run):

```
mvn -q test-compile
java -cp "ulanzi-client/target/classes;ulanzi-client/target/test-classes" cz.bliksoft.ulanzi.client.DeckDemo default 60
```

To deploy the bridge, copy the plugin folder without `test/` to `%APPDATA%\Ulanzi\UlanziDeck\Plugins\` and restart Studio. It doesn't relaunch a plugin that exits.

## Publishing

Forgejo Actions in `.forgejo/workflows/`, publishing to the `Bliksoft-public` registry:

- `maven-snapshot-deploy.yml`: on each push to `main`, publishes the `<revision>` snapshot.
- `release.yml`: on a `vX.Y.Z` tag, publishes that version and creates a Forgejo release with the plugin archive (`scripts/package-plugin.sh`). Nothing is committed back, so bump `<revision>` in `pom.xml` by hand afterwards.

`ulanzi-client-fx` tests skip themselves on the headless runner, so run `mvn test` on a desktop before tagging.

The repo will move to `jelinj8` on GitHub, with Forgejo pull-mirroring it. The workflows move with it, since Forgejo reads `.forgejo/` from the mirrored content and GitHub ignores it. At that point, also update the remote, and add the house `maven-mirror-sync.yml`.

## Hardware facts that shape the code

See `docs/protocol.md` for detail.

- Key `run` fires on press, not release. Some `keyup`s have no `keydown`.
- Dials send one message per step, with gaps down to 8 ms and no count. Press duration and press-and-hold aren't observable.
- Studio repeats `setactive` after `add`, and `active:false` has never been seen.
- The D100 has no display. The D200X wide button (`3_2`) is reserved by Studio.
