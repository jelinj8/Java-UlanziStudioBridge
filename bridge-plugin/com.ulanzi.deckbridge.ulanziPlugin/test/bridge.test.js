const test = require('node:test');
const assert = require('node:assert');
const { Bridge, PROTOCOL } = require('../plugin/bridge');

const UUID = 'com.ulanzi.ulanzistudio.deckbridge.slot';

function setup() {
	const studio = [];
	let time = 1000;
	const bridge = new Bridge({ toStudio: (m) => studio.push(m), log: () => {}, now: () => time });
	return { bridge, studio, tick: (ms) => { time += ms; } };
}

function connect(bridge, appId = 'app') {
	const conn = {
		sent: [],
		closed: null,
		send(text) { this.sent.push(JSON.parse(text)); },
		close(code) { this.closed = code; },
	};
	bridge.onAppMessage(conn, { type: 'hello', protocol: PROTOCOL, appId });
	return conn;
}

function slot(key, { device = 'D200X', controller = 'Keypad', param = { appId: 'app' }, actionid = `id-${key}` } = {}) {
	return { cmd: 'add', uuid: UUID, key, actionid, device, controller, param };
}

function event(cmd, key, extra = {}) {
	return { cmd, uuid: UUID, key, actionid: `id-${key}`, ...extra };
}

const inputs = (conn) => conn.sent.filter((m) => m.type === 'input');

test('hello is rejected without a supported protocol or an appId', () => {
	const { bridge } = setup();
	const a = { send() {}, close(code) { this.closed = code; } };
	bridge.onAppMessage(a, { type: 'hello', protocol: 999, appId: 'x' });
	assert.equal(a.closed, 4001);
	const b = { send() {}, close(code) { this.closed = code; } };
	bridge.onAppMessage(b, { type: 'hello', protocol: PROTOCOL });
	assert.equal(b.closed, 4002);
});

test('a newer connection replaces the older one and keeps pages', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const first = connect(bridge);
	bridge.onAppMessage(first, { type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'AAA' });
	const second = connect(bridge);
	assert.equal(first.closed, 4000);
	assert.equal(second.sent[0].currentPage, 'p');
	// the old connection closing afterwards must not mark the app offline
	bridge.onAppDisconnected(first);
	studio.length = 0;
	bridge.onAppMessage(second, { type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'BBB' });
	assert.equal(studio[0].param.statelist[0].data, 'BBB');
});

test('slots are grouped by appId and default to device and key', () => {
	const { bridge } = setup();
	const conn = connect(bridge);
	bridge.onStudioMessage(slot('0_0'));
	bridge.onStudioMessage(slot('0_1', { param: { appId: 'other' } }));
	bridge.onStudioMessage(slot('0_0', { device: 'Dial', actionid: 'id-dial' }));
	const last = conn.sent.filter((m) => m.type === 'slots').pop();
	assert.deepEqual(last.slots.map((s) => [s.slotId, s.hasDisplay]), [['D200X:0_0', true], ['Dial:0_0', false]]);
});

test('key input carries the showing page and ignores an unmatched keyup', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'AAA' });
	bridge.onStudioMessage(event('keyup', '0_0'));
	bridge.onStudioMessage(event('keydown', '0_0'));
	bridge.onStudioMessage(event('run', '0_0'));
	bridge.onStudioMessage(event('keyup', '0_0'));
	bridge.onStudioMessage(event('keyup', '0_0'));
	assert.deepEqual(inputs(conn).map((m) => [m.page, m.event.type]), [['p', 'down'], ['p', 'run'], ['p', 'up']]);
});

test('dial press becomes run, dialup is dropped, and rotation is split', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('2_3', { controller: 'Encoder' }));
	const conn = connect(bridge);
	bridge.onStudioMessage(event('dialdown', '2_3'));
	bridge.onStudioMessage(event('dialup', '2_3'));
	bridge.onStudioMessage(event('dialrotate', '2_3', { rotateEvent: 'left' }));
	bridge.onStudioMessage(event('dialrotate', '2_3', { rotateEvent: 'hold-right' }));
	assert.deepEqual(inputs(conn).map((m) => m.event), [
		{ type: 'run' },
		{ type: 'rotate', direction: 'left', held: false },
		{ type: 'rotate', direction: 'right', held: true },
	]);
});

test('paints use state and setTitle on keys, setFeedback on dials, nothing on Dial devices', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0'));
	bridge.onStudioMessage(slot('2_3', { controller: 'Encoder' }));
	bridge.onStudioMessage(slot('0_1', { device: 'Dial' }));
	const conn = connect(bridge);
	studio.length = 0;
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'AAA', text: 'T' });
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'D200X:2_3', image: 'BBB', text: 'U' });
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'Dial:0_1', image: 'CCC' });
	const cmds = studio.map((m) => m.cmd);
	assert.ok(cmds.includes('state') && cmds.includes('setTitle') && cmds.includes('setFeedback'));
	assert.ok(!JSON.stringify(studio).includes('CCC'));
	const feedback = studio.find((m) => m.cmd === 'setFeedback');
	assert.deepEqual(feedback.layout, { icon: { value: 'data:image/png;base64,BBB' }, title: { text: 'U' } });
});

test('D200X round buttons report no display and are not painted, but still give input', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_3'));
	bridge.onStudioMessage(slot('2_3', { controller: 'Encoder' }));
	const conn = connect(bridge);
	const listed = conn.sent.filter((m) => m.type === 'slots').pop().slots;
	assert.deepEqual(listed.map((s) => [s.slotId, s.hasDisplay]), [['D200X:0_3', false], ['D200X:2_3', true]]);
	studio.length = 0;
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'D200X:0_3', image: 'AAA', text: 'T' });
	// the first render makes the page current, which repaints the knob
	assert.ok(studio.length > 0);
	assert.ok(!JSON.stringify(studio).includes('"0_3"'));
	bridge.onStudioMessage(event('run', '0_3'));
	assert.equal(inputs(conn).length, 1);
});

test('duplicate setactive within the window paints once, later setactive repaints', () => {
	const { bridge, studio, tick } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'AAA' });
	studio.length = 0;
	bridge.onStudioMessage(event('setactive', '0_0', { active: true }));
	bridge.onStudioMessage(event('setactive', '0_0', { active: true }));
	assert.equal(studio.length, 0);
	tick(5000);
	bridge.onStudioMessage(event('setactive', '0_0', { active: true }));
	assert.equal(studio[0].param.statelist[0].data, 'AAA');
});

test('rendering to another page only updates the cache until it is shown', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'a', slotId: 'D200X:0_0', image: 'AAA' });
	studio.length = 0;
	bridge.onAppMessage(conn, { type: 'render', page: 'b', slotId: 'D200X:0_0', image: 'BBB' });
	assert.equal(studio.length, 0);
	bridge.onAppMessage(conn, { type: 'showPage', page: 'b' });
	assert.equal(studio[0].param.statelist[0].data, 'BBB');
	assert.equal(conn.sent.pop().page, 'b');
});

test('next and previous follow first-render order and wrap', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_3', { param: { appId: 'app', navMode: 'next' } }));
	bridge.onStudioMessage(slot('1_3', { param: { appId: 'app', navMode: 'previous' } }));
	const conn = connect(bridge);
	for (const page of ['a', 'b', 'c'])
		bridge.onAppMessage(conn, { type: 'render', page, slotId: 'x', image: 'I' });
	const changes = () => conn.sent.filter((m) => m.type === 'pageChanged').map((m) => m.page);
	bridge.onStudioMessage(event('run', '0_3'));
	bridge.onStudioMessage(event('run', '0_3'));
	bridge.onStudioMessage(event('run', '0_3'));
	bridge.onStudioMessage(event('run', '1_3'));
	assert.deepEqual(changes(), ['a', 'b', 'c', 'a', 'c']);
	assert.equal(inputs(conn).length, 0);
});

test('setPageOrder overrides next order', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_3', { param: { appId: 'app', navMode: 'next' } }));
	const conn = connect(bridge);
	for (const page of ['a', 'b', 'c'])
		bridge.onAppMessage(conn, { type: 'render', page, slotId: 'x', image: 'I' });
	bridge.onAppMessage(conn, { type: 'setPageOrder', pages: ['a', 'c', 'b'] });
	bridge.onStudioMessage(event('run', '0_3'));
	assert.equal(conn.sent.pop().page, 'c');
});

test('nav from render goes to a page and is per page; unknown target is ignored', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'a', slotId: 'D200X:0_0', image: 'I', nav: { page: 'b' } });
	bridge.onAppMessage(conn, { type: 'render', page: 'b', slotId: 'D200X:0_0', image: 'I' });
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(conn.sent.pop().page, 'b');
	// the same slot on page b is an ordinary slot
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(inputs(conn).length, 1);
	bridge.onAppMessage(conn, { type: 'render', page: 'b', slotId: 'D200X:0_0', image: 'I', nav: { page: 'nope' } });
	const before = conn.sent.length;
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(conn.sent.length, before);
});

test('navigating to an unknown page shows an alert on the slot', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('1_1', { param: { appId: 'app', navMode: 'page', navPage: 'nope' } }));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'a', slotId: 'x', image: 'I' });
	studio.length = 0;
	bridge.onStudioMessage(event('run', '1_1'));
	assert.deepEqual(studio, [{ cmd: 'showAlert', uuid: UUID, key: '1_1', actionid: 'id-1_1' }]);
});

test('inspector nav overrides nav from render', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_0', { param: { appId: 'app', navMode: 'page', navPage: 'c' } }));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'a', slotId: 'D200X:0_0', image: 'I', nav: { page: 'b' } });
	bridge.onAppMessage(conn, { type: 'render', page: 'b', slotId: 'x', image: 'I' });
	bridge.onAppMessage(conn, { type: 'render', page: 'c', slotId: 'x', image: 'I' });
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(conn.sent.pop().page, 'c');
});

test('disconnect shows offline and keeps the cache for reconnect', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'AAA' });
	studio.length = 0;
	bridge.onAppDisconnected(conn);
	assert.equal(studio[0].param.statelist[0].type, 0);
	assert.equal(studio[1].param.text, 'offline');
	studio.length = 0;
	connect(bridge);
	assert.equal(studio[0].param.statelist[0].data, 'AAA');
});

test('settings moving a slot to another app update both apps', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const a = connect(bridge, 'app');
	const b = connect(bridge, 'other');
	bridge.onStudioMessage(event('paramfromplugin', '0_0', { param: { appId: 'other' } }));
	assert.deepEqual(a.sent.filter((m) => m.type === 'slots').pop().slots, []);
	assert.equal(b.sent.filter((m) => m.type === 'slots').pop().slots.length, 1);
});

test('unchanged settings send nothing', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	const before = conn.sent.length;
	studio.length = 0;
	bridge.onStudioMessage(event('paramfromapp', '0_0', { param: { appId: 'app' } }));
	assert.equal(conn.sent.length, before);
	assert.equal(studio.length, 0);
});

function connectInGroup(bridge, appId, group = 'java') {
	const conn = {
		sent: [],
		send(text) { this.sent.push(JSON.parse(text)); },
		close(code) { this.closed = code; },
	};
	bridge.onAppMessage(conn, { type: 'hello', protocol: PROTOCOL, appId, group });
	return conn;
}

function groupSetup() {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0', { param: { appId: 'java' } }));
	const a = connectInGroup(bridge, 'a');
	const b = connectInGroup(bridge, 'b');
	bridge.onAppMessage(a, { type: 'render', page: 'pa', slotId: 'D200X:0_0', image: 'AAA', text: 'from a' });
	bridge.onAppMessage(b, { type: 'render', page: 'pb', slotId: 'D200X:0_0', image: 'BBB', text: 'from b' });
	const painted = () => studio.filter((m) => m.cmd === 'state').pop().param.statelist[0];
	return { bridge, studio, a, b, painted };
}

test('group slots follow the focused app, showing its content and sending it input', () => {
	const { bridge, a, b, painted } = groupSetup();
	assert.equal(painted().data, 'AAA', 'the first member starts with them');
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(inputs(a).length, 1);
	assert.equal(inputs(a)[0].page, 'pa');
	assert.equal(inputs(b).length, 0);

	bridge.onAppMessage(b, { type: 'focus' });
	assert.equal(painted().data, 'BBB');
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(inputs(a).length, 1);
	assert.deepEqual(inputs(b).pop(), { type: 'input', page: 'pb', slotId: 'D200X:0_0', event: { type: 'run' } });

	bridge.onAppMessage(a, { type: 'focus' });
	assert.equal(painted().data, 'AAA');
});

test('an unfocused member renders to its cache without touching the deck', () => {
	const { bridge, studio, b, a, painted } = groupSetup();
	studio.length = 0;
	bridge.onAppMessage(b, { type: 'render', page: 'pb', slotId: 'D200X:0_0', image: 'NEW' });
	assert.equal(studio.length, 0);
	bridge.onAppMessage(b, { type: 'focus' });
	assert.equal(painted().data, 'NEW');
	bridge.onAppMessage(a, { type: 'focus' });
});

test('nav in a group slot acts on the focused app, including its pages', () => {
	const { bridge, a, b } = groupSetup();
	bridge.onAppMessage(b, { type: 'render', page: 'pb2', slotId: 'x', image: 'I' });
	bridge.onAppMessage(b, { type: 'render', page: 'pb', slotId: 'D200X:0_0', image: 'B', nav: { page: 'pb2' } });
	bridge.onAppMessage(b, { type: 'focus' });
	const before = a.sent.length;
	bridge.onStudioMessage(event('run', '0_0'));
	assert.equal(b.sent.pop().page, 'pb2');
	assert.equal(a.sent.length, before);
});

test('when the focused app disconnects, the slots go to the next one, then offline', () => {
	const { bridge, studio, a, b, painted } = groupSetup();
	bridge.onAppMessage(b, { type: 'focus' });
	bridge.onAppDisconnected(b);
	assert.equal(painted().data, 'AAA');
	bridge.onAppDisconnected(a);
	assert.equal(painted().type, 0);
	assert.equal(studio.filter((m) => m.cmd === 'setTitle').pop().param.text, 'offline');
});

test('a reconnecting member keeps its focus order and its pages', () => {
	const { bridge, b, painted } = groupSetup();
	bridge.onAppMessage(b, { type: 'focus' });
	bridge.onAppDisconnected(b);
	assert.equal(painted().data, 'AAA');
	connectInGroup(bridge, 'b');
	assert.equal(painted().data, 'BBB');
});

test('every member sees the group slots whichever has them', () => {
	const { bridge, a, b } = groupSetup();
	const slotsOf = (conn) => conn.sent.filter((m) => m.type === 'slots').pop().slots.map((s) => s.slotId);
	assert.deepEqual(slotsOf(a), ['D200X:0_0']);
	assert.deepEqual(slotsOf(b), ['D200X:0_0']);
	bridge.onStudioMessage(slot('0_1', { param: { appId: 'java' } }));
	assert.deepEqual(slotsOf(a), ['D200X:0_0', 'D200X:0_1']);
	assert.deepEqual(slotsOf(b), ['D200X:0_0', 'D200X:0_1']);
});

test('slots with a member\'s own App ID stay with it regardless of focus', () => {
	const { bridge, a, b } = groupSetup();
	bridge.onStudioMessage(slot('0_1', { param: { appId: 'a' } }));
	bridge.onAppMessage(b, { type: 'focus' });
	bridge.onStudioMessage(event('run', '0_1'));
	assert.equal(inputs(a).pop().slotId, 'D200X:0_1');
	assert.equal(inputs(b).length, 0);
});

test('focus from an app outside any group changes nothing', () => {
	const { bridge, studio } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	studio.length = 0;
	bridge.onAppMessage(conn, { type: 'focus' });
	assert.equal(studio.length, 0);
});

test('reconnecting in another group leaves the old one', () => {
	const { bridge, painted } = groupSetup();
	connectInGroup(bridge, 'a', 'other');
	assert.equal(painted().data, 'BBB');
});

test('clear removes slots', () => {
	const { bridge } = setup();
	bridge.onStudioMessage(slot('0_0'));
	const conn = connect(bridge);
	bridge.onStudioMessage({ cmd: 'clear', param: [{ uuid: UUID, key: '0_0', actionid: 'id-0_0' }] });
	assert.deepEqual(conn.sent.filter((m) => m.type === 'slots').pop().slots, []);
});

test('messages before hello are ignored', () => {
	const { bridge, studio } = setup();
	const conn = { appId: null, send() { throw new Error('unexpected'); }, close() {} };
	bridge.onAppMessage(conn, { type: 'render', page: 'p', slotId: 'x', image: 'I' });
	assert.equal(studio.length, 0);
});
