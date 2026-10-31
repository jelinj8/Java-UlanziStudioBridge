// Routes between Ulanzi Studio slots and connected apps. Holds the slot registry
// and each app's pages; has no I/O of its own. See docs/protocol.md.
const { UNCLAIMED_SLOT_PNG, OFFLINE_SLOT_PNG } = require('./placeholders');

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
		// group -> its apps' IDs, the most recently focused first
		this.groups = new Map();
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
		this.#notifySlots(slot.appId);
		this.#paintSlot(slot);
	}

	#unregister(context) {
		const slot = this.slots.get(context);
		if (!slot)
			return;
		this.slots.delete(context);
		this.#notifySlots(slot.appId);
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
		this.#notifySlots(slot.appId);
		if (previousAppId !== slot.appId)
			this.#notifySlots(previousAppId);
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
		const app = this.apps.get(this.#routeOf(slot));
		return app?.pages.get(app.current)?.get(slot.slotId)?.nav ?? null;
	}

	/**
	 * The app a slot currently belongs to. A slot's App ID can name a group, which
	 * means its most recently focused connected app.
	 */
	#routeOf(slot) {
		const members = this.groups.get(slot.appId);
		return members?.find((id) => this.apps.get(id)?.conn) ?? slot.appId;
	}

	#navigate(slot, nav) {
		const appId = this.#routeOf(slot);
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
			case 'focus':
				this.#focus(appId, app);
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
		this.#repaintAround(conn.appId, app);
	}

	#focus(appId, app) {
		const members = this.groups.get(app.group);
		if (!members || members[0] === appId)
			return;
		members.splice(members.indexOf(appId), 1);
		members.unshift(appId);
		this.#repaintAround(appId, app);
	}

	#joinGroup(appId, app, group) {
		if (app.group !== group) {
			const previous = this.groups.get(app.group);
			if (previous)
				previous.splice(previous.indexOf(appId), 1);
			const left = app.group;
			app.group = group;
			if (left) {
				for (const slot of this.slots.values()) {
					if (slot.appId === left) {
						slot.lastPaint = null;
						this.#paintSlot(slot);
					}
				}
			}
		}
		if (!group)
			return;
		if (!this.groups.has(group))
			this.groups.set(group, []);
		if (!this.groups.get(group).includes(appId))
			this.groups.get(group).push(appId);
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
		this.#joinGroup(appId, app, msg.group?.trim() || null);
		this.#send(app, { type: 'welcome', protocol: PROTOCOL, currentPage: app.current });
		this.#sendSlots(appId);
		this.#repaintAround(appId, app);
	}

	#app(appId) {
		let app = this.apps.get(appId);
		if (!app) {
			app = { conn: null, group: null, pages: new Map(), order: [], current: null };
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
		return [...this.slots.values()].filter((s) => this.#routeOf(s) === appId && (slotId === undefined || s.slotId === slotId));
	}

	#sendInput(slot, event) {
		const app = this.apps.get(this.#routeOf(slot));
		if (app?.conn)
			this.#send(app, { type: 'input', page: app.current, slotId: slot.slotId, event });
	}

	/** @param {string} id the App ID on a slot whose list changed: an app, a group, or both */
	#notifySlots(id) {
		this.#sendSlots(id);
		for (const member of this.groups.get(id) ?? [])
			this.#sendSlots(member);
	}

	#sendSlots(appId) {
		const app = this.apps.get(appId);
		if (!app?.conn)
			return;
		// the same slotId can exist on several Studio pages; apps see it once.
		// An app in a group sees the group's slots whichever member has them now.
		const unique = new Map();
		for (const slot of this.slots.values()) {
			if (slot.appId !== appId && slot.appId !== app.group)
				continue;
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

	/** Repaints an app's slots and its group's, after a change that may have moved them. */
	#repaintAround(appId, app) {
		for (const slot of this.slots.values()) {
			if (slot.appId === appId || (app.group && slot.appId === app.group)) {
				slot.lastPaint = null;
				this.#paintSlot(slot);
			}
		}
	}

	#paintSlot(slot) {
		if (!hasDisplay(slot))
			return;
		const app = this.apps.get(this.#routeOf(slot));
		const online = Boolean(app?.conn);
		const content = online ? app.pages.get(app.current)?.get(slot.slotId) : null;
		const text = content?.text ?? '';
		const image = content?.image ?? (online ? UNCLAIMED_SLOT_PNG : OFFLINE_SLOT_PNG);

		const now = this.now();
		const last = slot.lastPaint;
		if (last && last.image === image && last.text === text && now - last.at < DUPLICATE_PAINT_MS)
			return;
		slot.lastPaint = { image, text, at: now };

		const { uuid, key, actionid } = slot;
		if (slot.controller === 'Encoder') {
			this.toStudio({
				cmd: 'setFeedback', uuid, key, actionid,
				layout: { icon: { value: `data:image/png;base64,${image}` }, title: { text } },
			});
			return;
		}
		const entry = { uuid, key, actionid, type: 1, data: image, textData: text, showtext: Boolean(text) };
		this.toStudio({ cmd: 'state', param: { statelist: [entry] } });
		// keys don't display the text sent with state
		this.toStudio({ cmd: 'setTitle', param: { uuid, key, actionid, text } });
	}
}

module.exports = { Bridge, PROTOCOL };
