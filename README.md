# Ulanzi Studio Bridge

Lets your own applications drive the keys and dials of an Ulanzi deck, such as the D200X or D100, through Ulanzi Studio. Apps set images and text and receive presses and rotation, with pages of bindings they can switch between.

```
Ulanzi Studio <-> Deck Bridge plugin (Node) <-> your apps
```

| Part | What it is |
|---|---|
| `bridge-plugin/` | The Ulanzi Studio plugin. It holds each app's pages, so every client gets them for free |
| `ulanzi-client` | Java client for the plugin, with no runtime dependencies |
| `ulanzi-client-fx` | Binds BSToolbox-jfx UI actions to slots, in code or from the XML filesystem |

Any language can be a client: the plugin speaks JSON over a local WebSocket, described in [docs/protocol.md](docs/protocol.md).

## Install the plugin

Download `deck-bridge-plugin-<version>.zip` from [Releases](https://github.com/jelinj8/Java-UlanziStudioBridge/releases), unzip it into `%APPDATA%\Ulanzi\UlanziDeck\Plugins\`, and restart Ulanzi Studio. Then drag the **App slot** action onto keys and dials. Each slot's settings have an App ID, a Slot ID and optional page navigation.

## Use it from Java

```xml
<dependency>
  <groupId>cz.bliksoft.ulanzi</groupId>
  <artifactId>ulanzi-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
DeckClient client = new DeckClient("my-app", new DeckListener() {
    @Override
    public void onInput(SlotInput input) {
        System.out.println(input);
    }

    @Override
    public void onConnected(String currentPage) {
        // draw here, as the plugin may not remember earlier pages
    }
});
client.start();
client.render("main", "D200X:0_0", pngBytes, "Hello", null);
```

Slot IDs default to `<device model>:<key>`, such as `D200X:0_0`.

With [BSToolbox-jfx](https://github.com/jelinj8/Java-BSToolbox-jfx), use `cz.bliksoft.ulanzi:ulanzi-client-fx` and bring your own `common-java-utils-jfx`, 0.8 or newer:

```java
DeckBinder binder = new DeckBinder("my-app");
binder.register(new DeckPage("main")
    .bind("D200X:1_1", SlotBinding.button(action))
    .bind("D200X:2_3", SlotBinding.encoder(left, right).press(press)));
binder.start();
```

Or define pages in the XML filesystem, with no code: see [docs/xml-pages.md](docs/xml-pages.md).

## Several apps

Slots belong to the app named by their App ID. Ulanzi Studio tells apps apart by executable, so every plain Java app looks the same to it. There are two ways around that:

- [Focus routing](docs/xml-pages.md): one Studio profile for `javaw.exe`, whose slots follow the most recently focused app.
- [A native launcher](docs/packaging.md): the app gets its own executable, and so its own Studio profile. [BSNative launcher](https://github.com/jelinj8/Java-BSNative-launcher) is the simplest way.

## Building

```
mvn test
cd bridge-plugin/com.ulanzi.deckbridge.ulanziPlugin
npm ci
node --test test/
```

The JavaFX tests need a desktop session, and skip themselves without one.

## Licence

[Apache License 2.0](LICENSE). The plugin bundles the Ulanzi SDK's JavaScript libraries from [UlanziDeckPlugin-SDK](https://github.com/UlanziTechnology/UlanziDeckPlugin-SDK), also under Apache 2.0, in `bridge-plugin/com.ulanzi.deckbridge.ulanziPlugin/libs`.

This project isn't affiliated with Ulanzi.
