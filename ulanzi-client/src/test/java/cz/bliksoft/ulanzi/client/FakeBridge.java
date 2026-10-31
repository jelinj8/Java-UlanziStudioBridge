package cz.bliksoft.ulanzi.client;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A minimal WebSocket server, handling one client at a time. */
final class FakeBridge implements AutoCloseable {
	private static final int OP_CONTINUATION = 0;
	private static final int OP_TEXT = 1;
	private static final int OP_CLOSE = 8;

	private final ServerSocket server;
	private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
	private final BlockingQueue<Boolean> connections = new LinkedBlockingQueue<>();
	private volatile Socket client;

	FakeBridge() throws IOException {
		server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
		Thread t = new Thread(this::acceptLoop, "fake-bridge");
		t.setDaemon(true);
		t.start();
	}

	URI uri() {
		return URI.create("ws://127.0.0.1:" + server.getLocalPort());
	}

	/** The next message from the client, failing if none arrives in time. */
	String nextMessage() throws InterruptedException {
		String message = received.poll(5, TimeUnit.SECONDS);
		if (message == null)
			throw new AssertionError("No message from client");
		return message;
	}

	boolean awaitConnection(long millis) throws InterruptedException {
		return connections.poll(millis, TimeUnit.MILLISECONDS) != null;
	}

	void send(String text) throws IOException {
		writeFrame(OP_TEXT, true, text.getBytes(StandardCharsets.UTF_8));
	}

	void sendFragmented(String first, String second) throws IOException {
		writeFrame(OP_TEXT, false, first.getBytes(StandardCharsets.UTF_8));
		writeFrame(OP_CONTINUATION, true, second.getBytes(StandardCharsets.UTF_8));
	}

	void closeWith(int code, String reason) throws IOException {
		byte[] text = reason.getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[text.length + 2];
		payload[0] = (byte) (code >> 8);
		payload[1] = (byte) code;
		System.arraycopy(text, 0, payload, 2, text.length);
		writeFrame(OP_CLOSE, true, payload);
	}

	void dropConnection() throws IOException {
		client.close();
	}

	private void acceptLoop() {
		try {
			while (true) {
				Socket socket = server.accept();
				client = socket;
				handshake(socket);
				connections.add(Boolean.TRUE);
				readFrames(socket);
			}
		} catch (IOException e) {
			// the server was closed
		}
	}

	private static void handshake(Socket socket) throws IOException {
		var in = socket.getInputStream();
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
			int b = in.read();
			if (b < 0)
				throw new IOException("Handshake ended early");
			head.write(b);
		}
		String key = head.toString(StandardCharsets.ISO_8859_1).lines()
				.filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow()
				.split(":", 2)[1].trim();
		String accept;
		try {
			accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
					.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
				+ "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
				.getBytes(StandardCharsets.ISO_8859_1));
	}

	private void readFrames(Socket socket) {
		try {
			DataInputStream in = new DataInputStream(socket.getInputStream());
			while (true) {
				int opcode = in.readUnsignedByte() & 0x0f;
				int length = in.readUnsignedByte() & 0x7f;
				if (length == 126)
					length = in.readUnsignedShort();
				else if (length == 127)
					length = (int) in.readLong();
				byte[] mask = in.readNBytes(4);
				byte[] payload = in.readNBytes(length);
				for (int i = 0; i < payload.length; i++)
					payload[i] ^= mask[i % 4];
				if (opcode == OP_CLOSE) {
					socket.close();
					return;
				}
				received.add(new String(payload, StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			// the connection ended; wait for the next one
		}
	}

	private void writeFrame(int opcode, boolean fin, byte[] payload) throws IOException {
		if (payload.length > 125)
			throw new IllegalArgumentException("FakeBridge only sends short frames");
		OutputStream out = client.getOutputStream();
		out.write((fin ? 0x80 : 0) | opcode);
		out.write(payload.length);
		out.write(payload);
		out.flush();
	}

	@Override
	public void close() throws IOException {
		server.close();
		if (client != null)
			client.close();
	}
}
