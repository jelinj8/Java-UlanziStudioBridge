// Hardware probe: logs every raw message from Ulanzi Studio to probe.log and
// paints test images, so the real protocol can be read off the connected devices.
//
// Key press: paints the next test size via `state`.
// Dial press: paints the next test size, alternating `state` and `setFeedback`.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const WebSocket = require('ws');

const MAIN_UUID = 'com.ulanzi.ulanzistudio.deckprobe';
const SIZES = [64, 72, 96, 128, 196, 256];
const LOG_FILE = path.join(__dirname, '..', 'probe.log');

const [address = '127.0.0.1', port = '3906', language = '', version = ''] = process.argv.slice(2);

function log(direction, text) {
	fs.appendFileSync(LOG_FILE, `${new Date().toISOString()} ${direction} ${text}\n`);
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
	let c = n;
	for (let k = 0; k < 8; k++)
		c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
	return c >>> 0;
});

function crc32(buf) {
	let c = 0xffffffff;
	for (const b of buf)
		c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
	const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(body));
	return Buffer.concat([len, body, crc]);
}

/** Solid image with a white border and a center cross, so scaling and cropping are visible. */
function testImage(size, hue) {
	const raw = Buffer.alloc((size * 3 + 1) * size);
	const fill = [[220, 38, 38], [22, 163, 74], [37, 99, 235], [202, 138, 4], [147, 51, 234], [8, 145, 178]][hue];
	const center = Math.floor(size / 2);
	let o = 0;
	for (let y = 0; y < size; y++) {
		raw[o++] = 0;
		for (let x = 0; x < size; x++) {
			const edge = x < 2 || y < 2 || x >= size - 2 || y >= size - 2;
			const cross = Math.abs(x - center) < 1 || Math.abs(y - center) < 1;
			const [r, g, b] = edge || cross ? [255, 255, 255] : fill;
			raw[o++] = r;
			raw[o++] = g;
			raw[o++] = b;
		}
	}
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(size, 0);
	ihdr.writeUInt32BE(size, 4);
	ihdr[8] = 8;
	ihdr[9] = 2;
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		pngChunk('IHDR', ihdr),
		pngChunk('IDAT', zlib.deflateSync(raw)),
		pngChunk('IEND', Buffer.alloc(0)),
	]).toString('base64');
}

log('START', JSON.stringify({ argv: process.argv.slice(2), address, port, language, version, node: process.version }));

const ws = new WebSocket(`ws://${address}:${port}`);
const slots = new Map();

function send(message) {
	const text = JSON.stringify(message);
	// images are shortened so the log stays readable
	log('OUT', text.replace(/"(data|value)":"[^"]{64,}"/g, '"$1":"<base64>"'));
	ws.send(text);
}

function paintState(msg, size, hue) {
	const { uuid, key, actionid } = msg;
	send({
		cmd: 'state',
		uuid: MAIN_UUID,
		param: {
			statelist: [{ uuid, key, actionid, type: 1, data: testImage(size, hue), textData: `${size}`, showtext: true }],
		},
	});
}

function paintFeedback(msg, size, hue) {
	const { uuid, key, actionid } = msg;
	send({
		cmd: 'setFeedback',
		uuid,
		key,
		actionid,
		layout: { icon: { value: `data:image/png;base64,${testImage(size, hue)}` }, title: { text: `fb ${size}` } },
	});
}

function nextStep(msg) {
	const id = `${msg.uuid}___${msg.key}___${msg.actionid}`;
	const step = slots.get(id) ?? 0;
	slots.set(id, step + 1);
	return { index: step % SIZES.length, step };
}

function handle(msg) {
	switch (msg.cmd) {
		case 'run': {
			const { index } = nextStep(msg);
			paintState(msg, SIZES[index], index);
			break;
		}
		case 'dialdown': {
			const { index, step } = nextStep(msg);
			if (step % 2 === 0)
				paintState(msg, SIZES[index], index);
			else
				paintFeedback(msg, SIZES[index], index);
			break;
		}
		case 'add':
		case 'setactive':
			if (msg.cmd === 'add' || msg.active)
				paintState(msg, 72, 0);
			break;
	}
}

ws.on('open', () => send({ code: 0, cmd: 'connected', uuid: MAIN_UUID }));

ws.on('message', (raw) => {
	const text = raw.toString();
	log('IN ', text);
	let msg;
	try {
		msg = JSON.parse(text);
	} catch (e) {
		log('ERR', `unparseable message: ${e.message}`);
		return;
	}
	if (typeof msg.code !== 'undefined' && msg.cmdType !== 'REQUEST')
		return;
	// the main service acknowledges each host message
	send({ cmd: msg.cmd, uuid: MAIN_UUID, code: 0, ...msg });
	handle(msg);
});

ws.on('error', (e) => log('ERR', e.message));
ws.on('close', (code) => {
	log('CLOSE', `${code}`);
	process.exit(0);
});
