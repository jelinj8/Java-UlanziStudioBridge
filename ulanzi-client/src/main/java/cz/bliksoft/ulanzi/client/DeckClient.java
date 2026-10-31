package cz.bliksoft.ulanzi.client;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connects an app to the Deck Bridge plugin and reconnects when the connection
 * is lost. Safe to use from any thread.
 *
 * <p>
 * Calls made while disconnected are dropped. The bridge keeps rendered pages
 * across reconnects; {@link DeckListener#onConnected} says when they are gone.
 */
public class DeckClient implements AutoCloseable {
	public static final URI DEFAULT_URI = URI.create("ws://127.0.0.1:39380");

	private static final Logger log = System.getLogger(DeckClient.class.getName());
	private static final int PROTOCOL = 1;
	private static final long MIN_RECONNECT_MS = 500;
	private static final long MAX_RECONNECT_MS = 15_000;
	private static final int FIRST_REFUSAL_CODE = 4000;

	private final URI uri;
	private final String appId;
	private final DeckListener listener;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "deck-client-reconnect");
		t.setDaemon(true);
		return t;
	});

	private final Object lock = new Object();
	private WebSocket socket;
	private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);
	private long reconnectDelayMs = MIN_RECONNECT_MS;
	private boolean started;
	private boolean closed;
	private boolean refused;

	public DeckClient(String appId, DeckListener listener) {
		this(DEFAULT_URI, appId, listener);
	}

	public DeckClient(URI uri, String appId, DeckListener listener) {
		this.uri = uri;
		this.appId = appId;
		this.listener = listener;
	}

	/** Starts connecting; returns immediately. */
	public void start() {
		synchronized (lock) {
			if (started || closed)
				return;
			started = true;
		}
		connect();
	}

	/**
	 * Replaces a slot's content on a page. The first render makes its page current.
	 *
	 * @param png  PNG image, or {@code null} for the plugin icon
	 * @param text title, or {@code null} for none
	 * @param nav  page navigation on press, or {@code null} for none
	 */
	public void render(String page, String slotId, byte[] png, String text, PageNav nav) {
		Map<String, Object> message = message("render");
		message.put("page", page);
		message.put("slotId", slotId);
		if (png != null)
			message.put("image", Base64.getEncoder().encodeToString(png));
		if (text != null)
			message.put("text", text);
		if (nav != null)
			message.put("nav", nav.toJson());
		send(message);
	}

	public void showPage(String page) {
		Map<String, Object> message = message("showPage");
		message.put("page", page);
		send(message);
	}

	/** Sets the order for next/previous navigation. */
	public void setPageOrder(List<String> pages) {
		Map<String, Object> message = message("setPageOrder");
		message.put("pages", pages);
		send(message);
	}

	/** Frees a page and its images in the bridge. */
	public void clearPage(String page) {
		Map<String, Object> message = message("clearPage");
		message.put("page", page);
		send(message);
	}

	/** Shows Studio's error indicator on a slot. */
	public void alert(String slotId) {
		Map<String, Object> message = message("alert");
		message.put("slotId", slotId);
		send(message);
	}

	@Override
	public void close() {
		WebSocket current;
		synchronized (lock) {
			if (closed)
				return;
			closed = true;
			current = socket;
			socket = null;
		}
		scheduler.shutdownNow();
		if (current != null)
			current.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
	}

	private static Map<String, Object> message(String type) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", type);
		return message;
	}

	private void send(Map<String, Object> message) {
		synchronized (lock) {
			if (socket != null)
				enqueue(socket, message);
		}
	}

	/** WebSocket allows one send at a time, so each waits for the one before. */
	private void enqueue(WebSocket target, Map<String, Object> message) {
		String text = Json.write(message);
		sendTail = sendTail.handle((r, e) -> null).thenCompose(ignored -> target.sendText(text, true));
		sendTail.exceptionally(e -> {
			log.log(Level.DEBUG, "Send failed: {0}", e.getMessage());
			return null;
		});
	}

	private void connect() {
		synchronized (lock) {
			if (closed)
				return;
		}
		http.newWebSocketBuilder().buildAsync(uri, new SocketListener()).whenComplete((ws, e) -> {
			if (e != null) {
				log.log(Level.DEBUG, "Connection to {0} failed: {1}", uri, e.getMessage());
				scheduleReconnect();
			}
		});
	}

	private void scheduleReconnect() {
		long delay;
		synchronized (lock) {
			if (closed || refused)
				return;
			delay = reconnectDelayMs;
			reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_RECONNECT_MS);
		}
		scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
	}

	private void notifyListener(Runnable call) {
		try {
			call.run();
		} catch (RuntimeException e) {
			log.log(Level.ERROR, "Deck listener failed", e);
		}
	}

	private void dispatch(String text) {
		if (!(Json.parse(text) instanceof Map<?, ?> msg)) {
			log.log(Level.WARNING, "Unexpected message: {0}", text);
			return;
		}
		String type = String.valueOf(msg.get("type"));
		switch (type) {
			case "welcome" -> {
				synchronized (lock) {
					reconnectDelayMs = MIN_RECONNECT_MS;
				}
				notifyListener(() -> listener.onConnected((String) msg.get("currentPage")));
			}
			case "slots" -> {
				List<Slot> slots = parseSlots((List<?>) msg.get("slots"));
				notifyListener(() -> listener.onSlots(slots));
			}
			case "pageChanged" -> notifyListener(() -> listener.onPageChanged((String) msg.get("page")));
			case "input" -> {
				SlotInput input = parseInput(msg);
				if (input != null)
					notifyListener(() -> listener.onInput(input));
			}
			default -> log.log(Level.DEBUG, "Unknown message type: {0}", type);
		}
	}

	private static List<Slot> parseSlots(List<?> items) {
		List<Slot> slots = new ArrayList<>();
		for (Object item : items) {
			Map<?, ?> s = (Map<?, ?>) item;
			slots.add(new Slot((String) s.get("slotId"), (String) s.get("controller"), (String) s.get("device"),
					Boolean.TRUE.equals(s.get("hasDisplay"))));
		}
		return List.copyOf(slots);
	}

	private static SlotInput parseInput(Map<?, ?> msg) {
		Map<?, ?> event = (Map<?, ?>) msg.get("event");
		String page = (String) msg.get("page");
		String slotId = (String) msg.get("slotId");
		String type = String.valueOf(event.get("type"));
		return switch (type) {
			case "down" -> new SlotInput(page, slotId, SlotInput.Type.DOWN, null, false);
			case "up" -> new SlotInput(page, slotId, SlotInput.Type.UP, null, false);
			case "run" -> new SlotInput(page, slotId, SlotInput.Type.RUN, null, false);
			case "rotate" -> new SlotInput(page, slotId, SlotInput.Type.ROTATE,
					"left".equals(event.get("direction")) ? SlotInput.Direction.LEFT : SlotInput.Direction.RIGHT,
					Boolean.TRUE.equals(event.get("held")));
			default -> {
				log.log(Level.DEBUG, "Unknown input type: {0}", type);
				yield null;
			}
		};
	}

	private final class SocketListener implements WebSocket.Listener {
		private final StringBuilder partial = new StringBuilder();

		@Override
		public void onOpen(WebSocket ws) {
			synchronized (lock) {
				if (closed) {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
					return;
				}
				socket = ws;
				sendTail = CompletableFuture.completedFuture(null);
				Map<String, Object> hello = message("hello");
				hello.put("protocol", PROTOCOL);
				hello.put("appId", appId);
				enqueue(ws, hello);
			}
			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				try {
					dispatch(text);
				} catch (RuntimeException e) {
					log.log(Level.WARNING, "Bad message from bridge: " + text, e);
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			if (statusCode >= FIRST_REFUSAL_CODE) {
				synchronized (lock) {
					refused = true;
				}
				log.log(Level.WARNING, "Refused by bridge ({0}): {1}", statusCode, reason);
				lost(ws);
				notifyListener(() -> listener.onRefused(statusCode, reason));
			} else {
				lost(ws);
			}
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			log.log(Level.DEBUG, "Connection error: {0}", error.getMessage());
			lost(ws);
		}

		private void lost(WebSocket ws) {
			synchronized (lock) {
				if (socket != ws)
					return;
				socket = null;
			}
			notifyListener(listener::onDisconnected);
			scheduleReconnect();
		}
	}
}
