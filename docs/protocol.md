# Protocols

## App <-> bridge (v1)

Apps connect to `ws://127.0.0.1:39380` (`DECK_BRIDGE_PORT` overrides the port). Frames are JSON text, up to 8 MiB. Connections sending an `Origin` header are refused, which keeps web pages out.

### App to bridge

| Message | Meaning |
|---|---|
| `hello {protocol: 1, appId}` | Must be first. Close codes: 4001 unsupported protocol, 4002 missing `appId`. A newer connection with the same `appId` closes the older one with 4000. |
| `render {page, slotId, image?, text?, nav?}` | Replaces the slot's content on that page. `image` is base64 PNG without a prefix. `page` defaults to `main`. The first render makes its page current. |
| `showPage {page}` | Makes the page current and repaints. |
| `setPageOrder {pages}` | Sets the order for next/previous. Default: order of first render. |
| `clearPage {page}` | Frees a page and its images. |
| `alert {slotId}` | Shows Studio's error indicator on the slot. |

`nav` is `{page: "<id>"}`, `{to: "next"}` or `{to: "previous"}`. Next/previous wrap around. A press aimed at an unknown page changes nothing, logs to `bridge.log` and shows Studio's alert on the slot.

### Bridge to app

| Message | Meaning |
|---|---|
| `welcome {protocol, currentPage}` | Reply to `hello`. `currentPage` is `null` for a new app. |
| `slots {slots: [{slotId, controller, device, hasDisplay}]}` | The full slot list, sent after `welcome` and on every change. |
| `pageChanged {page}` | The current page changed, including through nav slots. |
| `input {page, slotId, event}` | `page` is the page showing at that moment. `event` is below. |

`event` is `{type: "down"}`, `{type: "up"}`, `{type: "run"}` or `{type: "rotate", direction: "left"|"right", held}`.

- Keys send `down`, `run`, `up`, with `run` on press. Dials send `run` and `rotate` only.
- Nav slots send no `input`.

### Slots and pages

- A slot's `appId`, `slotId` and navigation are set in its Property Inspector. Defaults: `appId` is `default`, `slotId` is `<device>:<key>`.
- Navigation set in the inspector overrides `nav` from `render`.
- Slots sharing an `appId` and `slotId` all show the same content. This covers the same position on several Studio pages, and also two devices of the same model, unless they're given different `slotId`s.
- Pages are kept in memory and survive app reconnects, but not a bridge restart. After a reconnect the bridge repaints from them; after `welcome` with `currentPage: null`, the app must render again.
- A slot with no content shows the plugin icon. While its app is disconnected it shows the icon with the title `offline`.

Image sizes that look good: about 196 px on keys, 128 px on knobs. None are cropped; smaller ones look coarse and 256 px looks soft on knobs.

# Ulanzi Studio: observed behaviour

Measured on 2026-10-31 with `probe/com.ulanzi.deckprobe.ulanziPlugin` on Ulanzi Studio 3.4.4, a D200X and a D100. This takes precedence over the public SDK docs where they differ.

## Connection

- Studio listens on `127.0.0.1:3906` and starts the plugin with `address port language version`.
- The plugin sends `{code:0, cmd:"connected", uuid}` first.
- The main service echoes each host message with `code:0`.
- Each `state` sent gets the reply `{cmd:"state", cmdType:"NOTIFY", code:0, param:null}`. It names no slot, so it can't confirm a paint.

## Slots

- `add` carries `controller` (`Keypad`, `Encoder`), `device` (`D200X`), `key`, `actionid`, `uuid`, and `param` (saved settings).
- Keys and dials share the `col_row` format. On the D200X the dials are `2_3`, `3_3`, `4_3`, in the row below the keys.
- The D100 reports `device: "Dial"`, with a dial at `0_2` and keys at `0_0`, `1_0`, `2_0`. It has no display, so paints have no visible effect. Key coordinates alone don't identify a slot across devices.
- `add` is followed by duplicate `setactive {active:true}` messages within about 10 ms (2 or 3 seen). Handling must be idempotent.
- Returning to a page sends `setactive {active:true}` for its slots. No `active:false` has been logged, so leaving a page can't be relied on as a signal.

## Keys

- `run` arrives about 2 ms after `keydown`, not on release. Treat it as the press.
- `keyup` arrives on release, so a hold can be timed (a 2.4 s hold was measured). No repeat events arrive during a hold.
- Unmatched `keyup` messages occur: 3 of 16 had no preceding `keydown`. Cause unknown; ignore them.

## Dials

- `dialrotate` has `rotateEvent` = `left|right|hold-left|hold-right`. There is no count field.
- There is one message per step, with no batching seen. The D100 reached gaps of 8 ms in fast turns (D200X: 36 ms), so clients must keep up with bursts of 50+ events per second.
- Dials send no `run`.
- `dialdown` and `dialup` arrived 7-15 ms apart in all 28 observed presses. That is too short for a physical press, so they probably fire together on release. Press duration is not usable.
- No `dialdown` preceded `hold-*` rotation, and none appeared when the hold began. A press-and-hold without rotation is therefore not expected to be detectable.

## Display

- `state` with a base64 PNG paints D200X keys and the smaller dial images.
- `setFeedback` with `{title:{text}, icon:{value}}` and a `data:image/png;base64,...` value changes the knob image. Plain strings, as the first probe sent, had no reliable effect.
- D200X keys don't display the text sent with `state`.
- Protocol V3.1 (Studio 3.3.0+) also has `setImage`, `setState` and `setTitle`. The bridge uses `setTitle` for key text.

## D200X wide button

- It is key `3_2`, a 2x1 item that Studio's device config (`%APPDATA%\Ulanzi\UlanziDeck\Config\device_type_source.json`) lists as both `LargeItem` and `FixedItem`.
- D200X profiles hold the built-in `com.ulanzi.ulanzideck.smallwindow.window` action there.
- Neither the public SDK nor the installed API docs offer a way to paint it or receive its input. Placing a plugin action there by editing a profile is untested and unsupported.
- `0_3` and `1_3` are round physical buttons with no display, holding `page.prev`/`page.next` by default. They accept plugin actions after a confirmation dialog in Studio.
- The knobs have no screens of their own. Their images and titles appear side by side on the wide button's display when it is in knob mode.
- `setTitle` displays on keys, and on a knob without an image.

## Not yet verified

- What `clear` reports when a slot is removed: none has been logged.
- Saving inspector settings through `paramfromplugin`, per the SDK examples.
