package cz.bliksoft.ulanzi.fx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cz.bliksoft.javautils.app.ui.actions.UIActionBase;
import cz.bliksoft.ulanzi.client.DeckClient;
import cz.bliksoft.ulanzi.client.DeckListener;
import cz.bliksoft.ulanzi.client.PageNav;
import cz.bliksoft.ulanzi.client.SlotInput;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;

class DeckBinderTest {
	private static final String ICON = "[P]:M0 0H100V100H0Z";

	private record Render(String page, String slotId, byte[] png, String text, PageNav nav) {
	}

	private static final class RecordingClient extends DeckClient {
		final List<Render> renders = Collections.synchronizedList(new ArrayList<>());
		final List<String> commands = Collections.synchronizedList(new ArrayList<>());

		RecordingClient(DeckListener listener) {
			super(URI.create("ws://127.0.0.1:1"), "test", listener);
		}

		@Override
		public void start() {
			commands.add("start");
		}

		@Override
		public void render(String page, String slotId, byte[] png, String text, PageNav nav) {
			renders.add(new Render(page, slotId, png, text, nav));
		}

		@Override
		public void showPage(String page) {
			commands.add("show " + page);
		}

		@Override
		public void setPageOrder(List<String> pages) {
			commands.add("order " + pages);
		}

		@Override
		public void clearPage(String page) {
			commands.add("clear " + page);
		}

		Render lastRender() {
			return renders.get(renders.size() - 1);
		}
	}

	private static final class CountingAction extends UIActionBase {
		final SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);
		final SimpleBooleanProperty visible = new SimpleBooleanProperty(true);
		final List<Integer> runs = Collections.synchronizedList(new ArrayList<>());
		private final String key;

		CountingAction(String key) {
			this.key = key;
			setText(key);
			setIconSpec(ICON);
		}

		@Override
		public void execute() {
			runs.add(runs.size());
		}

		@Override
		public String getKey() {
			return key;
		}

		@Override
		public ObservableBooleanValue enabledProperty() {
			return enabled;
		}

		@Override
		public ObservableBooleanValue visibleProperty() {
			return visible;
		}
	}

	private RecordingClient client;
	private DeckBinder binder;

	@BeforeAll
	static void startToolkit() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		try {
			Platform.startup(started::countDown);
			assertTrue(started.await(10, TimeUnit.SECONDS));
		} catch (IllegalStateException alreadyRunning) {
			// another test class started it
		}
		Platform.setImplicitExit(false);
	}

	@BeforeEach
	void setUp() {
		binder = new DeckBinder(listener -> client = new RecordingClient(listener));
	}

	@AfterEach
	void tearDown() throws Exception {
		binder.close();
		settle();
	}

	/** Waits until the FX thread has done everything queued so far. */
	private static void settle() throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(done::countDown);
		assertTrue(done.await(10, TimeUnit.SECONDS));
	}

	private static SlotInput run(String page, String slot) {
		return new SlotInput(page, slot, SlotInput.Type.RUN, null, false);
	}

	private static SlotInput rotate(String page, String slot, SlotInput.Direction direction, boolean held) {
		return new SlotInput(page, slot, SlotInput.Type.ROTATE, direction, held);
	}

	@Test
	void registerRendersImagesAtTheRightSizeAndSetsPageOrder() throws Exception {
		CountingAction action = new CountingAction("a");
		binder.register(new DeckPage("p").bind("key", SlotBinding.button(action)).bind("knob",
				SlotBinding.encoder(action, action)));
		settle();
		Render key = client.renders.get(0);
		assertEquals("p", key.page());
		assertEquals("a", key.text());
		assertEquals(196, image(key.png()).getWidth());
		assertEquals(128, image(client.renders.get(1).png()).getWidth());
		assertEquals(List.of("order [p]"), client.commands);
	}

	@Test
	void iconIsDrawnOverABlackBackground() throws Exception {
		binder.register(new DeckPage("p").bind("key", SlotBinding.button(new CountingAction("a"))));
		settle();
		BufferedImage png = image(client.lastRender().png());
		assertEquals(0xFF000000, png.getRGB(0, 0), "corner stays background");
		assertNotEquals(0xFF000000, png.getRGB(98, 98), "the icon covers the centre");
	}

	@Test
	void disabledActionIsDimmedAndDoesntRun() throws Exception {
		CountingAction action = new CountingAction("a");
		binder.register(new DeckPage("p").bind("key", SlotBinding.button(action)));
		settle();
		byte[] normal = client.lastRender().png();
		action.enabled.set(false);
		settle();
		settle();
		assertFalse(java.util.Arrays.equals(normal, client.lastRender().png()));
		binder.onInput(run("p", "key"));
		settle();
		assertEquals(0, action.runs.size());
		action.enabled.set(true);
		settle();
		settle();
		assertArrayEquals(normal, client.lastRender().png());
		binder.onInput(run("p", "key"));
		settle();
		assertEquals(1, action.runs.size());
	}

	@Test
	void hiddenActionShowsBlackWithoutTextAndDoesntRun() throws Exception {
		CountingAction action = new CountingAction("a");
		binder.register(new DeckPage("p").bind("key", SlotBinding.button(action)));
		action.visible.set(false);
		settle();
		settle();
		assertNull(client.lastRender().text());
		assertEquals(0xFF000000, image(client.lastRender().png()).getRGB(98, 98));
		binder.onInput(run("p", "key"));
		settle();
		assertEquals(0, action.runs.size());
	}

	@Test
	void textAndIconChangesRepaintEvenFromAnotherThread() throws Exception {
		CountingAction action = new CountingAction("a");
		binder.register(new DeckPage("p").bind("key", SlotBinding.button(action)));
		settle();
		action.setText("changed");
		settle();
		settle();
		assertEquals("changed", client.lastRender().text());
		byte[] before = client.lastRender().png();
		Thread other = new Thread(() -> action.setIconSpec("[P]:M0 0H100V10H0Z"));
		other.start();
		other.join();
		settle();
		settle();
		assertFalse(java.util.Arrays.equals(before, client.lastRender().png()));
	}

	@Test
	void inputGoesToTheBindingOfThePageItArrivedFor() throws Exception {
		CountingAction one = new CountingAction("one");
		CountingAction two = new CountingAction("two");
		binder.register(new DeckPage("a").bind("s", SlotBinding.button(one)));
		binder.register(new DeckPage("b").bind("s", SlotBinding.button(two)));
		binder.onInput(run("b", "s"));
		binder.onInput(run("unknown", "s"));
		binder.onInput(run("a", "unknown"));
		settle();
		assertEquals(0, one.runs.size());
		assertEquals(1, two.runs.size());
	}

	@Test
	void keyDownAndUpRunNothing() throws Exception {
		CountingAction action = new CountingAction("a");
		binder.register(new DeckPage("p").bind("s", SlotBinding.button(action)));
		binder.onInput(new SlotInput("p", "s", SlotInput.Type.DOWN, null, false));
		binder.onInput(new SlotInput("p", "s", SlotInput.Type.UP, null, false));
		settle();
		assertEquals(0, action.runs.size());
	}

	@Test
	void encoderRoutesRotationPressAndHeldRotation() throws Exception {
		CountingAction left = new CountingAction("l");
		CountingAction right = new CountingAction("r");
		CountingAction heldLeft = new CountingAction("hl");
		CountingAction heldRight = new CountingAction("hr");
		CountingAction press = new CountingAction("p");
		binder.register(new DeckPage("p").bind("s",
				SlotBinding.encoder(left, right).press(press).held(heldLeft, heldRight)));
		binder.onInput(rotate("p", "s", SlotInput.Direction.LEFT, false));
		binder.onInput(rotate("p", "s", SlotInput.Direction.RIGHT, false));
		binder.onInput(rotate("p", "s", SlotInput.Direction.RIGHT, false));
		binder.onInput(rotate("p", "s", SlotInput.Direction.LEFT, true));
		binder.onInput(run("p", "s"));
		settle();
		assertEquals(List.of(1, 2, 1, 0, 1),
				List.of(left.runs.size(), right.runs.size(), heldLeft.runs.size(), heldRight.runs.size(), press.runs.size()));
		assertEquals("p", client.lastRender().text(), "shows the press action");
	}

	@Test
	void heldRotationFallsBackToPlainActions() throws Exception {
		CountingAction left = new CountingAction("l");
		binder.register(new DeckPage("p").bind("s", SlotBinding.encoder(left, new CountingAction("r"))));
		binder.onInput(rotate("p", "s", SlotInput.Direction.LEFT, true));
		settle();
		assertEquals(1, left.runs.size());
	}

	@Test
	void burstOfDialStepsRunsEveryStepInOrder() throws Exception {
		CountingAction right = new CountingAction("r");
		binder.register(new DeckPage("p").bind("s", SlotBinding.encoder(new CountingAction("l"), right)));
		for (int i = 0; i < 2000; i++)
			binder.onInput(rotate("p", "s", SlotInput.Direction.RIGHT, false));
		settle();
		assertEquals(2000, right.runs.size());
	}

	@Test
	void failingActionDoesntStopLaterInput() throws Exception {
		CountingAction good = new CountingAction("good");
		UIActionBase bad = UIActionBase.of("bad", null, null, null, () -> {
			throw new IllegalStateException("expected by the test");
		});
		binder.register(new DeckPage("p").bind("b", SlotBinding.button(bad)).bind("g", SlotBinding.button(good)));
		binder.onInput(run("p", "b"));
		binder.onInput(run("p", "g"));
		settle();
		assertEquals(1, good.runs.size());
	}

	@Test
	void navigationIsRenderedWithOrWithoutAnAction() throws Exception {
		PageNav next = PageNav.next();
		binder.register(new DeckPage("p").bind("round", SlotBinding.navigation(next, null)).bind("key",
				SlotBinding.navigation(next, new CountingAction("a"))));
		settle();
		assertEquals(next, client.renders.get(0).nav());
		assertNull(client.renders.get(0).png());
		assertNotNull(client.renders.get(1).png());
		assertEquals(next, client.renders.get(1).nav());
	}

	@Test
	void reconnectRendersEverythingAgain() throws Exception {
		binder.register(new DeckPage("p").bind("s", SlotBinding.button(new CountingAction("a"))));
		settle();
		client.renders.clear();
		binder.onConnected("p");
		settle();
		assertEquals(1, client.renders.size());
	}

	@Test
	void unregisterStopsRepaintsAndClearsThePage() throws Exception {
		CountingAction action = new CountingAction("a");
		binder.register(new DeckPage("p").bind("s", SlotBinding.button(action)));
		settle();
		binder.unregister("p");
		settle();
		assertTrue(client.commands.contains("clear p"));
		client.renders.clear();
		action.setText("changed");
		settle();
		settle();
		assertEquals(0, client.renders.size());
	}

	@Test
	void replacingAPageDropsTheOldListeners() throws Exception {
		CountingAction old = new CountingAction("old");
		binder.register(new DeckPage("p").bind("s", SlotBinding.button(old)));
		binder.register(new DeckPage("p").bind("s", SlotBinding.button(new CountingAction("new"))));
		settle();
		client.renders.clear();
		old.setText("changed");
		settle();
		settle();
		assertEquals(0, client.renders.size());
	}

	@Test
	void badIconSpecStillRendersTheText() throws Exception {
		CountingAction action = new CountingAction("a");
		action.setIconSpec("no-such-icon-file.png");
		binder.register(new DeckPage("p").bind("s", SlotBinding.button(action)));
		settle();
		assertEquals("a", client.lastRender().text());
	}

	private static BufferedImage image(byte[] png) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(png));
	}
}
