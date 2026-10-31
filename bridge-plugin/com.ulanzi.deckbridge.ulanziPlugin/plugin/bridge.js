// Routes between Ulanzi Studio slots and connected apps. Holds the slot registry
// and each app's pages; has no I/O of its own. See docs/protocol.md.
const PROTOCOL = 1;
const DEFAULT_APP_ID = 'default';
const DEFAULT_PAGE = 'main';
// Studio repeats setactive within about 10 ms of add
const DUPLICATE_PAINT_MS = 50;
const NO_DISPLAY_DEVICES = new Set(['Dial']);

// the D200X's round buttons beside the knobs are in the knobs' row
const D200X_KNOB_ROW = '3';

function hasDisplay(slot) {
	if (NO_DISPLAY_DEVICES.has(slot.device))
		return false;
	return !(slot.device === 'D200X' && slot.controller === 'Keypad' && slot.key.endsWith(`_${D200X_KNOB_ROW}`));
}

function contextOf(msg) {
	return `${msg.uuid}___${msg.key}___${msg.actionid}`;
}

class Bridge {
	/**
	 * @param {object} io
	 * @param {(message: object) => void} io.toStudio sends one message to Studio
	 * @param {(text: string) => void} io.log
	 * @param {() => number} [io.now]
	 */
	constructor({ toStudio, log, now = Date.now }) {
		this.toStudio = toStudio;
		this.log = log;
		this.now = now;
		this.slots = new Map();
		this.apps = new Map();
	}

	// ---- Studio side ----

	onStudioMessage(msg) {
		switch (msg.cmd) {
			case 'add':
				this.#register(msg);
				break;
			case 'paramfromapp':
			case 'paramfromplugin':
				this.#updateSettings(msg, msg.param);
				break;
			case 'didReceiveSettings':
				this.#updateSettings(msg, msg.settings);
				break;
			case 'setactive':
				if (msg.active) {
					const slot = this.slots.get(contextOf(msg));
					if (slot)
						this.#paintSlot(slot);
				}
				break;
			case 'clear':
				for (const item of msg.param ?? [])
					this.#unregister(contextOf(item));
				break;
			case 'keydown':
				this.#keyDown(msg);
				break;
			case 'keyup':
				this.#keyUp(msg);
				break;
			case 'run':
			case 'dialdown':
				this.#press(msg);
				break;
			case 'dialrotate':
				this.#rotate(msg);
				break;
		}
	}

	#register(msg) {
		const context = contextOf(msg);
		const slot = {
			context,
			uuid: msg.uuid,
			key: msg.key,
			actionid: msg.actionid,
			controller: msg.controller,
			device: msg.device,
			keyIsDown: false,
		};
		this.#applySettings(slot, msg.param);
		this.slots.set(context, slot);
		this.#sendSlots(slot.appId);
		this.#paintSlot(slot);
	}

	#unregister(context) {
		const slot = this.slots.get(context);
		if (!slot)
			return;
		this.slots.delete(context);
		this.#sendSlots(slot.appId);
	}

	#updateSettings(msg, settings) {
		const slot = this.slots.get(contextOf(msg));
		if (!slot || !settings)
			return;
		const previousAppId = slot.appId;
		const previousSlotId = slot.slotId;
		const previousNav = JSON.stringify(slot.nav);
		this.#applySettings(slot, settings);
		// Studio resends unchanged settings, e.g. when a slot is selected
		if (previousAppId === slot.appId && previousSlotId === slot.slotId && previousNav === JSON.stringify(slot.nav))
			return;
		this.#sendSlots(slot.appId);
		if (previousAppId !== slot.appId)
			this.#sendSlots(previousAppId);
		slot.lastPaint = null;
		this.#paintSlot(slot);
	}

	#applySettings(slot, settings) {
		const s = settings ?? {};
		slot.appId = s.appId?.trim() || DEFAULT_APP_ID;
		slot.slotId = s.slotId?.trim() || `${slot.device}:${slot.key}`;
		slot.nav = null;
		if (s.navMode === 'next' || s.navMode === 'previous')
			slot.nav = { to: s.navMode };
		else if (s.navMode === 'page' && s.navPage?.trim())
			slot.nav = { page: s.navPage.trim() };
	}

	#keyDown(msg) {
		const slot = this.slots.get(contextOf(msg));
		if (!slot)
			return;
		slot.keyIsDown = true;
		if (!this.#navOf(slot))
			this.#sendInput(slot, { type: 'down' });
	}

	#keyUp(msg) {
		const slot = this.slots.get(contextOf(msg));
		// Studio sends some keyup messages with no keydown before them
		if (!slot || !slot.keyIsDown)
			return;
		slot.keyIsDown = false;
		if (!this.#navOf(slot))
			this.#sendInput(slot, { type: 'up' });
	}

	#press(msg) {
		const slot = this.slots.get(contextOf(msg));
		if (!slot)
			return;
		const nav = this.#navOf(slot);
		if (nav)
			this.#navigate(slot, nav);
		else
			this.#sendInput(slot, { type: 'run' });
	}

	#rotate(msg) {
		const slot = this.slots.get(contextOf(msg));
		if (!slot)
			return;
		const held = msg.rotateEvent.startsWith('hold-');
		this.#sendInput(slot, { type: 'rotate', direction: held ? msg.rotateEvent.slice(5) : msg.rotateEvent, held });
	}

	#navOf(slot) {
		if (slot.nav)
			return slot.nav;
		const app = this.apps.get(slot.appId);
		return app?.pages.get(app.current)?.get(slot.slotId)?.nav ?? null;
	}

	#navigate(slot, nav) {
		const appId = slot.appId;
		const app = this.apps.get(appId);
		if (!app?.conn)
			return;
		let target = nav.page;
		if (nav.to) {
			if (app.order.length === 0)
				return;
			const step = nav.to === 'next' ? 1 : -1;
			const index = app.order.indexOf(app.current);
			target = app.order[(index + step + app.order.length) % app.order.length];
		} else if (!app.pages.has(target)) {
			this.log(`navigation to unknown page ${target} of ${appId} ignored`);
			this.toStudio({ cmd: 'showAlert', uuid: slot.uuid, key: slot.key, actionid: slot.actionid });
			return;
		}
		this.#showPage(appId, app, target);
	}

	// ---- App side ----

	/** @param {{send: (text: string) => void, close: (code: number, reason: string) => void}} conn */
	onAppMessage(conn, msg) {
		if (msg.type === 'hello') {
			this.#hello(conn, msg);
			return;
		}
		const appId = conn.appId;
		const app = this.apps.get(appId);
		if (!app || app.conn !== conn) {
			this.log(`message ${msg.type} before hello ignored`);
			return;
		}
		switch (msg.type) {
			case 'render':
				this.#render(appId, app, msg);
				break;
			case 'setPageOrder':
				app.order = [...msg.pages];
				break;
			case 'showPage':
				this.#showPage(appId, app, msg.page);
				break;
			case 'clearPage':
				this.#clearPage(appId, app, msg.page);
				break;
			case 'alert':
				for (const slot of this.#slotsOf(appId, msg.slotId))
					this.toStudio({ cmd: 'showAlert', uuid: slot.uuid, key: slot.key, actionid: slot.actionid });
				break;
			default:
				this.log(`unknown message type ${msg.type} from ${appId}`);
		}
	}

	onAppDisconnected(conn) {
		const app = this.apps.get(conn.appId);
		if (!app || app.conn !== conn)
			return;
		app.conn = null;
		this.#paintApp(conn.appId);
	}

	#hello(conn, msg) {
		if (msg.protocol !== PROTOCOL) {
			conn.close(4001, `unsupported protocol ${msg.protocol}, expected ${PROTOCOL}`);
			return;
		}
		const appId = msg.appId?.trim();
		if (!appId) {
			conn.close(4002, 'appId is required');
			return;
		}
		const app = this.#app(appId);
		// the newer connection wins, so a restarted app isn't locked out by a stale one
		if (app.conn)
			app.conn.close(4000, 'replaced by a newer connection');
		app.conn = conn;
		conn.appId = appId;
		this.#send(app, { type: 'welcome', protocol: PROTOCOL, currentPage: app.current });
		this.#sendSlots(appId);
		this.#paintApp(appId);
	}

	#app(appId) {
		let app = this.apps.get(appId);
		if (!app) {
			app = { conn: null, pages: new Map(), order: [], current: null };
			this.apps.set(appId, app);
		}
		return app;
	}

	#page(app, pageId) {
		let page = app.pages.get(pageId);
		if (!page) {
			page = new Map();
			app.pages.set(pageId, page);
			if (!app.order.includes(pageId))
				app.order.push(pageId);
		}
		return page;
	}

	#render(appId, app, msg) {
		const pageId = msg.page ?? DEFAULT_PAGE;
		this.#page(app, pageId).set(msg.slotId, { image: msg.image, text: msg.text, nav: msg.nav });
		if (app.current === null) {
			this.#showPage(appId, app, pageId);
			return;
		}
		if (app.current === pageId) {
			for (const slot of this.#slotsOf(appId, msg.slotId))
				this.#paintSlot(slot);
		}
	}

	#showPage(appId, app, pageId) {
		this.#page(app, pageId);
		app.current = pageId;
		this.#paintApp(appId);
		this.#send(app, { type: 'pageChanged', page: pageId });
	}

	#clearPage(appId, app, pageId) {
		app.pages.delete(pageId);
		app.order = app.order.filter((p) => p !== pageId);
		if (app.current === pageId)
			this.#paintApp(appId);
	}

	#slotsOf(appId, slotId) {
		return [...this.slots.values()].filter((s) => s.appId === appId && (slotId === undefined || s.slotId === slotId));
	}

	#sendInput(slot, event) {
		const app = this.apps.get(slot.appId);
		if (app?.conn)
			this.#send(app, { type: 'input', page: app.current, slotId: slot.slotId, event });
	}

	#sendSlots(appId) {
		const app = this.apps.get(appId);
		if (!app?.conn)
			return;
		// the same slotId can exist on several Studio pages; apps see it once
		const unique = new Map();
		for (const slot of this.#slotsOf(appId)) {
			unique.set(slot.slotId, {
				slotId: slot.slotId,
				controller: slot.controller,
				device: slot.device,
				hasDisplay: hasDisplay(slot),
			});
		}
		this.#send(app, { type: 'slots', slots: [...unique.values()] });
	}

	#send(app, message) {
		app.conn.send(JSON.stringify(message));
	}

	// ---- painting ----

	#paintApp(appId) {
		for (const slot of this.#slotsOf(appId)) {
			slot.lastPaint = null;
			this.#paintSlot(slot);
		}
	}

	#paintSlot(slot) {
		if (!hasDisplay(slot))
			return;
		const app = this.apps.get(slot.appId);
		const online = Boolean(app?.conn);
		const content = online ? app.pages.get(app.current)?.get(slot.slotId) : null;
		const text = online ? content?.text ?? '' : 'offline';

		const now = this.now();
		const last = slot.lastPaint;
		if (last && last.content === content && last.text === text && now - last.at < DUPLICATE_PAINT_MS)
			return;
		slot.lastPaint = { content, text, at: now };

		const { uuid, key, actionid } = slot;
		if (slot.controller === 'Encoder' && content?.image) {
			this.toStudio({
				cmd: 'setFeedback', uuid, key, actionid,
				layout: { icon: { value: `data:image/png;base64,${content.image}` }, title: { text } },
			});
			return;
		}
		// without an image the slot falls back to the manifest's default state
		const entry = content?.image
			? { uuid, key, actionid, type: 1, data: content.image, textData: text, showtext: Boolean(text) }
			: { uuid, key, actionid, type: 0, state: 0, textData: text, showtext: Boolean(text) };
		this.toStudio({ cmd: 'state', param: { statelist: [entry] } });
		// keys don't display the text sent with state
		this.toStudio({ cmd: 'setTitle', param: { uuid, key, actionid, text } });
	}
}

module.exports = { Bridge, PROTOCOL };
