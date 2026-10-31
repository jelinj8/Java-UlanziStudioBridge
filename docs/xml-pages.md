# Deck pages in the XML filesystem

With `ulanzi-client-fx` on the classpath of a BSToolbox app, `UlanziStudioPlugin` loads deck pages from the merged XML filesystem at startup. Without a `RemoteControl/UlanziDeck` configuration it does nothing and opens no connection. `RemoteControl` can hold other remote control tools alongside it.

```xml
<file name="RemoteControl">
  <file name="UlanziDeck">
    <attribute name="uri" value="ws://127.0.0.1:39380"/>     <!-- optional -->
    <file name="freecad">                                    <!-- the app ID -->
      <attribute name="uri" value="ws://127.0.0.1:39380"/>   <!-- optional, overrides the one above -->
      <file name="pages">
        <file name="main" type="deckPage" position="10">
          <file name="D200X:1_1"><attribute name="action" value="Save"/></file>
          <file name="D200X:2_3">
            <attribute name="left" value="ZoomOut"/>
            <attribute name="right" value="ZoomIn"/>
            <attribute name="press" value="ZoomReset"/>      <!-- optional -->
            <attribute name="heldLeft" value="PanLeft"/>     <!-- optional, with heldRight -->
            <attribute name="heldRight" value="PanRight"/>
            <attribute name="display" value="ZoomLevel"/>    <!-- optional, default press, else left -->
          </file>
          <file name="D200X:1_3"><attribute name="nav" value="next"/></file>
          <file name="D200X:0_3">
            <attribute name="nav" value="page:tools"/>       <!-- also "previous" -->
            <attribute name="display" value="ToolsIcon"/>    <!-- optional -->
          </file>
        </file>
      </file>
    </file>
  </file>
</file>
```

- Each child of `UlanziDeck` is an app ID, matching the **App ID** field on the slots in Studio. Each gets its own connection and its own pages, so one app can register under several app IDs.
- A page is a node of type `deckPage`, named with the page ID. It can live in its own descriptor through the usual `<include>`.
- `position` sets the page order, used by next/previous and for the page shown first.
- Each child of a page is a slot, named with the slot ID. Actions are `UIActions` keys.
- A `nav` slot is a navigation slot, `left` and `right` make an encoder, and `action` makes a button.
- Modules can add slots to another module's page through normal filesystem merging.

Slot IDs default to `<model>:<key>`, so one configuration works on any device of that model. Two devices of one model show the same content until the slots on the additional device get their own names through the **Slot ID** field in Studio, for example `second:1_1`. Studio's messages don't identify individual devices.

From code, `UlanziStudioPlugin.getBinder(appId)` returns the binder for `showPage` or for adding pages built in code. It returns `null` for an app ID with no pages configured.

## When something is wrong

| Situation | Behaviour |
|---|---|
| No `RemoteControl/UlanziDeck`, or an app ID without pages | Nothing loads for it, no connection, debug log only |
| Unknown action key | Warning naming the page, slot and reason; that slot is skipped, the rest of the page loads |
| Invalid slot (no usable attributes, bad `nav`, only one of `left`/`right` or `heldLeft`/`heldRight`) | Warning; slot skipped |
| Child of `pages` without type `deckPage` | Warning; skipped |
| JavaFX toolkit isn't running, or a bad `uri` | Error logged; that app ID doesn't start, others and the app continue |
| Plugin not running | The client keeps trying to reconnect |

`UIActionBase` creates its text and icon properties on the first setter call, so an action must have them set before the module installs, or later changes won't reach the deck.
