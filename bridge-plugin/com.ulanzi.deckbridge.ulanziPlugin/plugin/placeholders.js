// Dim images for slots with nothing to show, so they don't compete with slots in use:
// black with a thin frame, gray when unclaimed and dark red when the app is offline.
const zlib = require('zlib');

const SIZE = 196;
const FRAME = 4;
const BLACK = [0, 0, 0];
const DARK_GRAY = [58, 58, 58];
const DARK_RED = [96, 16, 16];

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
	let c = n;
	for (let k = 0; k < 8; k++)
		c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
	return c >>> 0;
});

function crc32(buffer) {
	let c = 0xffffffff;
	for (const b of buffer)
		c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
	const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
	const length = Buffer.alloc(4);
	length.writeUInt32BE(data.length);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(body));
	return Buffer.concat([length, body, crc]);
}

function framedImage(frameColor) {
	const raw = Buffer.alloc((SIZE * 3 + 1) * SIZE);
	let offset = 0;
	for (let y = 0; y < SIZE; y++) {
		raw[offset++] = 0;
		for (let x = 0; x < SIZE; x++) {
			const inFrame = x < FRAME || y < FRAME || x >= SIZE - FRAME || y >= SIZE - FRAME;
			for (const value of inFrame ? frameColor : BLACK)
				raw[offset++] = value;
		}
	}
	const header = Buffer.alloc(13);
	header.writeUInt32BE(SIZE, 0);
	header.writeUInt32BE(SIZE, 4);
	header[8] = 8;
	header[9] = 2;
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		chunk('IHDR', header),
		chunk('IDAT', zlib.deflateSync(raw)),
		chunk('IEND', Buffer.alloc(0)),
	]).toString('base64');
}

module.exports = {
	UNCLAIMED_SLOT_PNG: framedImage(DARK_GRAY),
	OFFLINE_SLOT_PNG: framedImage(DARK_RED),
};
