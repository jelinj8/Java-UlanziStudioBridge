package cz.bliksoft.ulanzi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeckClientTest {
	private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
	private FakeBridge bridge;
	private DeckClient client;

	private record Connected(String page) {
	}

	private record Disconnected() {
	}

	private record Refused(int code, String reason) {
	}

	private record PageChanged(String page) {
	}

	@BeforeEach
	void setUp() throws Exception {
		bridge = new FakeBridge();
		client = new DeckClient(bridge.uri(), "demo", new DeckListener() {
			@Override
			public void onConnected(String currentPage) {
				events.add(new Connected(currentPage));
			}

			@Override
			public void onDisconnected() {
				events.add(new Disconnected());
			}

			@Override
			public void onRefused(int closeCode, String reason) {
				events.add(new Refused(closeCode, reason));
			}

			@Override
			public void onSlots(List<Slot> slots) {
				events.add(slots);
			}

			@Override
			public void onPageChanged(String page) {
				events.add(new PageChanged(page));
			}

			@Override
			public void onInput(SlotInput input) {
				events.add(input);
			}
		});
		client.start();
		assertEquals(Map.of("type", "hello", "protocol", 1.0, "appId", "demo"), nextSent());
	}

	@AfterEach
	void tearDown() throws Exception {
		client.close();
		bridge.close();
	}

	private Object nextSent() throws InterruptedException {
		return Json.parse(bridge.nextMessage());
	}

	private Object nextEvent() throws InterruptedException {
		Object event = events.poll(5, TimeUnit.SECONDS);
		if (event == null)
			throw new AssertionError("No event");
		return event;
	}

	@Test
	void parsesBridgeMessages() throws Exception {
		bridge.send("{\"type\":\"welcome\",\"protocol\":1,\"currentPage\":null}");
		assertEquals(new Connected(null), nextEvent());
		bridge.send("{\"type\":\"welcome\",\"protocol\":1,\"currentPage\":\"p\"}");
		assertEquals(new Connected("p"), nextEvent());
		bridge.send("{\"type\":\"slots\",\"slots\":[{\"slotId\":\"s\",\"controller\":\"Encoder\",\"device\":\"Dial\",\"hasDisplay\":false}]}");
		assertEquals(List.of(new Slot("s", "Encoder", "Dial", false)), nextEvent());
		bridge.send("{\"type\":\"pageChanged\",\"page\":\"q\"}");
		assertEquals(new PageChanged("q"), nextEvent());
	}

	@Test
	void parsesInputEvents() throws Exception {
		for (String event : List.of("{\"type\":\"down\"}", "{\"type\":\"up\"}", "{\"type\":\"run\"}"))
			bridge.send("{\"type\":\"input\",\"page\":\"p\",\"slotId\":\"s\",\"event\":" + event + "}");
		assertEquals(new SlotInput("p", "s", SlotInput.Type.DOWN, null, false), nextEvent());
		assertEquals(new SlotInput("p", "s", SlotInput.Type.UP, null, false), nextEvent());
		assertEquals(new SlotInput("p", "s", SlotInput.Type.RUN, null, false), nextEvent());
		bridge.send("{\"type\":\"input\",\"page\":\"p\",\"slotId\":\"s\",\"event\":{\"type\":\"rotate\",\"direction\":\"left\",\"held\":true}}");
		assertEquals(new SlotInput("p", "s", SlotInput.Type.ROTATE, SlotInput.Direction.LEFT, true), nextEvent());
		bridge.send("{\"type\":\"input\",\"page\":\"p\",\"slotId\":\"s\",\"event\":{\"type\":\"rotate\",\"direction\":\"right\",\"held\":false}}");
		assertEquals(new SlotInput("p", "s", SlotInput.Type.ROTATE, SlotInput.Direction.RIGHT, false), nextEvent());
	}

	@Test
	void reassemblesFragmentedMessages() throws Exception {
		bridge.sendFragmented("{\"type\":\"pageCh", "anged\",\"page\":\"p\"}");
		assertEquals(new PageChanged("p"), nextEvent());
	}

	@Test
	void badMessagesAndFailingListenersDontStopTheClient() throws Exception {
		bridge.send("not json");
		bridge.send("{\"type\":\"slots\"}");
		bridge.send("[1]");
		bridge.send("{\"type\":\"pageChanged\",\"page\":\"p\"}");
		assertEquals(new PageChanged("p"), nextEvent());
	}

	@Test
	void sendsCommandsInOrder() throws Exception {
		client.render("p", "s", new byte[] { 1, 2, 3 }, "T", PageNav.next());
		client.render("p", "s", null, null, null);
		client.showPage("p");
		client.setPageOrder(List.of("a", "b"));
		client.clearPage("a");
		client.alert("s");
		assertEquals(Map.of("type", "render", "page", "p", "slotId", "s", "image", "AQID", "text", "T", "nav", Map.of("to", "next")), nextSent());
		assertEquals(Map.of("type", "render", "page", "p", "slotId", "s"), nextSent());
		assertEquals(Map.of("type", "showPage", "page", "p"), nextSent());
		assertEquals(Map.of("type", "setPageOrder", "pages", List.of("a", "b")), nextSent());
		assertEquals(Map.of("type", "clearPage", "page", "a"), nextSent());
		assertEquals(Map.of("type", "alert", "slotId", "s"), nextSent());
	}

	@Test
	void largeBurstArrivesInOrder() throws Exception {
		for (int i = 0; i < 500; i++)
			client.showPage("p" + i);
		for (int i = 0; i < 500; i++)
			assertEquals(Map.of("type", "showPage", "page", "p" + i), nextSent());
	}

	@Test
	void reconnectsAfterConnectionLossAndSaysHelloAgain() throws Exception {
		assertTrue(bridge.awaitConnection(5000));
		bridge.dropConnection();
		assertEquals(new Disconnected(), nextEvent());
		assertTrue(bridge.awaitConnection(5000));
		assertEquals(Map.of("type", "hello", "protocol", 1.0, "appId", "demo"), nextSent());
	}

	@Test
	void doesNotReconnectWhenRefused() throws Exception {
		assertTrue(bridge.awaitConnection(5000));
		bridge.closeWith(4000, "replaced");
		assertEquals(new Disconnected(), nextEvent());
		assertEquals(new Refused(4000, "replaced"), nextEvent());
		assertFalse(bridge.awaitConnection(1500));
	}

	@Test
	void doesNotReconnectAfterClose() throws Exception {
		assertTrue(bridge.awaitConnection(5000));
		client.close();
		assertFalse(bridge.awaitConnection(1500));
	}

	@Test
	void callsWhileDisconnectedAreDropped() throws Exception {
		assertTrue(bridge.awaitConnection(5000));
		bridge.dropConnection();
		assertEquals(new Disconnected(), nextEvent());
		client.showPage("dropped");
		assertTrue(bridge.awaitConnection(5000));
		assertEquals(Map.of("type", "hello", "protocol", 1.0, "appId", "demo"), nextSent());
		client.showPage("kept");
		assertEquals(Map.of("type", "showPage", "page", "kept"), nextSent());
	}
}
