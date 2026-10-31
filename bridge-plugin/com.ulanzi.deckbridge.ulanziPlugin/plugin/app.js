// Plugin main service: connects to Ulanzi Studio and serves apps on a local WebSocket.
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { Bridge } = require('./bridge');

const MAIN_UUID = 'com.ulanzi.ulanzistudio.deckbridge';
const DEFAULT_APP_PORT = 39380;
const MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
const LOG_FILE = path.join(__dirname, '..', 'bridge.log');

const [studioAddress = '127.0.0.1', studioPort = '3906'] = process.argv.slice(2);
const appPort = Number(process.env.DECK_BRIDGE_PORT) || DEFAULT_APP_PORT;

function log(text) {
	try {
		fs.appendFileSync(LOG_FILE, `${new Date().toISOString()} ${text}\n`);
	} catch {
		// logging must never take the bridge down
	}
}

// Studio doesn't relaunch a plugin that exits, so one bad message must not end it
process.on('uncaughtException', (e) => log(`uncaught: ${e.stack}`));
process.on('unhandledRejection', (e) => log(`unhandled: ${e?.stack ?? e}`));

const studio = new WebSocket(`ws://${studioAddress}:${studioPort}`);

function toStudio(message) {
	if (studio.readyState === WebSocket.OPEN)
		studio.send(JSON.stringify({ uuid: MAIN_UUID, ...message }));
}

const bridge = new Bridge({ toStudio, log });

studio.on('open', () => {
	log(`connected to Studio at ${studioAddress}:${studioPort}`);
	toStudio({ code: 0, cmd: 'connected' });
});

studio.on('message', (raw) => {
	const msg = JSON.parse(raw.toString());
	if (typeof msg.code !== 'undefined' && msg.cmdType !== 'REQUEST')
		return;
	// the main service acknowledges each host message
	toStudio({ ...msg, code: 0 });
	bridge.onStudioMessage(msg);
});

studio.on('error', (e) => log(`Studio connection error: ${e.message}`));
studio.on('close', (code) => {
	log(`Studio connection closed: ${code}`);
	process.exit(0);
});

const server = new WebSocket.Server({
	host: '127.0.0.1',
	port: appPort,
	maxPayload: MAX_MESSAGE_BYTES,
	// browsers always send Origin; refusing it stops web pages from reaching the bridge
	verifyClient: ({ req }) => !req.headers.origin,
});

server.on('listening', () => log(`serving apps on 127.0.0.1:${appPort}`));
server.on('error', (e) => log(`app server error: ${e.message}`));

server.on('connection', (socket) => {
	const conn = {
		appId: null,
		send: (text) => {
			if (socket.readyState === WebSocket.OPEN)
				socket.send(text);
		},
		close: (code, reason) => socket.close(code, reason),
	};
	socket.on('message', (raw) => {
		let msg;
		try {
			msg = JSON.parse(raw.toString());
		} catch (e) {
			log(`unparseable app message: ${e.message}`);
			return;
		}
		bridge.onAppMessage(conn, msg);
	});
	socket.on('close', () => bridge.onAppDisconnected(conn));
	socket.on('error', (e) => log(`app connection error: ${e.message}`));
});
