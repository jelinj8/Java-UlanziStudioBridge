// Runs plugin/app.js as Studio would, against a fake Studio and over real sockets.
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { spawn } = require('child_process');
const { once } = require('events');
const WebSocket = require('ws');

const UUID = 'com.ulanzi.ulanzistudio.deckbridge.slot';
const APP_PORT = 39381;

function nextMessage(socket, matches) {
	return new Promise((resolve) => {
		const listener = (raw) => {
			const msg = JSON.parse(raw.toString());
			if (matches(msg)) {
				socket.off('message', listener);
				resolve(msg);
			}
		};
		socket.on('message', listener);
	});
}

async function connectApp(options) {
	for (let attempt = 0; ; attempt++) {
		const socket = new WebSocket(`ws://127.0.0.1:${APP_PORT}`, options);
		try {
			await once(socket, 'open');
			return socket;
		} catch (e) {
			// the server may still be starting; a refused handshake is a real answer
			if (e.code !== 'ECONNREFUSED' || attempt >= 50)
				throw e;
			await new Promise((r) => setTimeout(r, 100));
		}
	}
}

test('bridge connects to Studio, serves an app, and refuses browsers', async (t) => {
	const studioServer = new WebSocket.Server({ host: '127.0.0.1', port: 0 });
	await once(studioServer, 'listening');
	const plugin = spawn(process.execPath, [path.join(__dirname, '..', 'plugin', 'app.js'), '127.0.0.1', String(studioServer.address().port), 'en-US', '3.4.4'], {
		env: { ...process.env, DECK_BRIDGE_PORT: String(APP_PORT) },
		stdio: 'ignore',
	});
	t.after(() => {
		plugin.kill();
		studioServer.close();
	});

	const [studio] = await once(studioServer, 'connection');
	const connected = await nextMessage(studio, (m) => m.cmd === 'connected');
	assert.equal(connected.uuid, 'com.ulanzi.ulanzistudio.deckbridge');

	const acked = nextMessage(studio, (m) => m.cmd === 'add' && m.code === 0);
	studio.send(JSON.stringify({ cmd: 'add', uuid: UUID, key: '0_0', actionid: 'a', controller: 'Keypad', device: 'D200X', param: { appId: 'demo' } }));
	await acked;

	const app = await connectApp();
	const welcome = nextMessage(app, (m) => m.type === 'welcome');
	const slots = nextMessage(app, (m) => m.type === 'slots');
	app.send(JSON.stringify({ type: 'hello', protocol: 1, appId: 'demo' }));
	assert.equal((await welcome).protocol, 1);
	assert.equal((await slots).slots[0].slotId, 'D200X:0_0');

	const painted = nextMessage(studio, (m) => m.cmd === 'state' && m.param.statelist[0].data === 'AAA');
	app.send(JSON.stringify({ type: 'render', page: 'p', slotId: 'D200X:0_0', image: 'AAA' }));
	await painted;

	const input = nextMessage(app, (m) => m.type === 'input');
	studio.send(JSON.stringify({ cmd: 'run', uuid: UUID, key: '0_0', actionid: 'a' }));
	assert.deepEqual(await input, { type: 'input', page: 'p', slotId: 'D200X:0_0', event: { type: 'run' } });

	// malformed input must not take the bridge down
	app.send('not json');
	const stillServing = nextMessage(app, (m) => m.type === 'pageChanged');
	app.send(JSON.stringify({ type: 'showPage', page: 'p' }));
	await stillServing;

	await assert.rejects(connectApp({ origin: 'https://example.com' }), /401/);

	const offline = nextMessage(studio, (m) => m.cmd === 'setTitle' && m.param.text === 'offline');
	app.close();
	await offline;
});
