package cz.bliksoft.ulanzi.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cz.bliksoft.javautils.app.ui.actions.IUIAction;
import cz.bliksoft.javautils.app.ui.actions.UIActionBase;
import cz.bliksoft.javautils.app.ui.actions.UIActions;
import cz.bliksoft.javautils.modules.IModule;
import cz.bliksoft.javautils.xmlfilesystem.FileSystem;
import cz.bliksoft.ulanzi.client.DeckClient;
import cz.bliksoft.ulanzi.client.DeckListener;
import cz.bliksoft.ulanzi.client.PageNav;
import cz.bliksoft.ulanzi.client.SlotInput;
import javafx.application.Platform;

class UlanziStudioPluginTest {
	private static final String NS = "http://bliksoft.cz/XmlFilesystem";
	private static final List<String> runs = Collections.synchronizedList(new ArrayList<>());

	private record Render(String page, String slotId, String text, PageNav nav, boolean hasImage) {
	}

	private static final class RecordingClient extends DeckClient {
		final List<Render> renders = Collections.synchronizedList(new ArrayList<>());
		final List<String> orders = Collections.synchronizedList(new ArrayList<>());
		boolean started;

		RecordingClient(URI uri, String appId, DeckListener listener) {
			super(uri, appId, listener);
		}

		@Override
		public void start() {
			started = true;
		}

		@Override
		public void render(String page, String slotId, byte[] png, String text, PageNav nav) {
			renders.add(new Render(page, slotId, text, nav, png != null));
		}

		@Override
		public void setPageOrder(List<String> pages) {
			orders.add(String.join(",", pages));
		}

		Render slot(String slotId) {
			return renders.stream().filter(r -> r.slotId().equals(slotId)).reduce((a, b) -> b).orElse(null);
		}
	}

	private static final class Harness {
		RecordingClient client;
		URI uri;
		String appId;
		String group;
		DeckBinder binder;
		final UlanziStudioPlugin module;

		Harness(String root) {
			module = new UlanziStudioPlugin(root, (u, a, g) -> {
				uri = u;
				appId = a;
				group = g;
				binder = new DeckBinder(listener -> client = new RecordingClient(u, a, listener));
				return binder;
			});
		}
	}

	@BeforeAll
	static void setUp() throws Exception {
		FxToolkit.startOrSkip();
		for (String key : List.of("a", "b", "c", "d", "e", "f"))
			UIActions.registerAction("rc." + key, action("rc." + key), "test");
		UIActions.registerAction("rc.plain", new IUIAction() {
			@Override
			public void execute() {
				runs.add("rc.plain");
			}

			@Override
			public String getKey() {
				return "rc.plain";
			}
		}, "test");
		FileSystem.getDefault().importXml(new UlanziStudioPlugin().getFilesystemXml(), "module");
	}

	private static UIActionBase action(String key) {
		UIActionBase action = UIActionBase.of(key, key, null, null, () -> runs.add(key));
		action.setIconSpec("[P]:M0 0H100V100H0Z");
		return action;
	}

	private static void importPages(String root, String attributes, String pages) {
		String xml = "<root xmlns=\"" + NS + "\"><file name=\"" + root + "\"><file name=\"myApp\">" + attributes
				+ "<file name=\"pages\">" + pages + "</file></file></file></root>";
		FileSystem.getDefault().importXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), root);
	}

	private static String attr(String name, String value) {
		return "<attribute name=\"" + name + "\" value=\"" + value + "\"/>";
	}

	private static String slot(String id, String... attributes) {
		return "<file name=\"" + id + "\">" + String.join("", attributes) + "</file>";
	}

	private static String page(String id, String... slots) {
		return "<file name=\"" + id + "\" type=\"deckPage\">" + String.join("", slots) + "</file>";
	}

	private static void settle() throws Exception {
		for (int i = 0; i < 2; i++) {
			CountDownLatch done = new CountDownLatch(1);
			Platform.runLater(done::countDown);
			assertTrue(done.await(10, TimeUnit.SECONDS));
		}
	}

	private static Harness install(String root) throws Exception {
		Harness h = new Harness(root);
		runOnFx(h.module::install);
		settle();
		return h;
	}

	/** Modules install on the FX thread, from Application.start(). */
	private static void runOnFx(Runnable work) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
	}

	@Test
	void moduleIsDiscoverableByServiceLoader() {
		assertTrue(ServiceLoader.load(IModule.class).stream().anyMatch(p -> p.type() == UlanziStudioPlugin.class));
	}

	@Test
	void absentConfigurationDoesNothing() throws Exception {
		Harness h = install("rcAbsent");
		assertNull(h.client, "no client, so no connection");
	}

	@Test
	void emptyPagesFolderDoesNothing() throws Exception {
		importPages("rcEmpty", "", "");
		assertNull(install("rcEmpty").client);
	}

	@Test
	void nodeWithoutPageTypeIsSkipped() throws Exception {
		importPages("rcUntyped", "", "<file name=\"notAPage\">" + slot("s", attr("action", "rc.a")) + "</file>");
		assertNull(install("rcUntyped").client);
	}

	@Test
	void loadsAllSlotKindsAndStarts() throws Exception {
		importPages("rcAll", attr("uri", "ws://127.0.0.1:1"),
				page("main", //
						slot("D200X:1_1", attr("action", "rc.a")), //
						slot("D200X:2_3", attr("left", "rc.a"), attr("right", "rc.b"), attr("press", "rc.c"),
								attr("heldLeft", "rc.d"), attr("heldRight", "rc.e"), attr("display", "rc.f")), //
						slot("second:1_3", attr("nav", "next")), //
						slot("D200X:0_3", attr("nav", "page:tools"), attr("display", "rc.b")), //
						slot("D200X:0_2", attr("nav", "previous"))));
		Harness h = install("rcAll");
		assertTrue(h.client.started);
		assertEquals("myApp", h.appId);
		assertEquals(URI.create("ws://127.0.0.1:1"), h.uri);

		assertEquals("rc.a", h.client.slot("D200X:1_1").text());
		assertTrue(h.client.slot("D200X:1_1").hasImage());
		assertEquals("rc.f", h.client.slot("D200X:2_3").text(), "display is the chosen action");
		assertNotNull(h.client.slot("second:1_3").nav(), "a renamed slot works");
		assertFalse(h.client.slot("second:1_3").hasImage());
		assertEquals("rc.b", h.client.slot("D200X:0_3").text());
		assertNotNull(h.client.slot("D200X:0_2").nav());

		runs.clear();
		h.binder.onInput(new SlotInput("main", "D200X:1_1", SlotInput.Type.RUN, null, false));
		h.binder.onInput(new SlotInput("main", "D200X:2_3", SlotInput.Type.ROTATE, SlotInput.Direction.LEFT, false));
		h.binder.onInput(new SlotInput("main", "D200X:2_3", SlotInput.Type.ROTATE, SlotInput.Direction.RIGHT, false));
		h.binder.onInput(new SlotInput("main", "D200X:2_3", SlotInput.Type.RUN, null, false));
		h.binder.onInput(new SlotInput("main", "D200X:2_3", SlotInput.Type.ROTATE, SlotInput.Direction.LEFT, true));
		h.binder.onInput(new SlotInput("main", "D200X:2_3", SlotInput.Type.ROTATE, SlotInput.Direction.RIGHT, true));
		settle();
		assertEquals(List.of("rc.a", "rc.a", "rc.b", "rc.c", "rc.d", "rc.e"), runs);
		h.module.cleanup();
		settle();
	}

	@Test
	void uriDefaultsAndAppIdIsTheFolderName() throws Exception {
		importPages("rcDefaults", "", page("main", slot("s", attr("action", "rc.a"))));
		Harness h = install("rcDefaults");
		assertEquals("myApp", h.appId);
		assertEquals(DeckClient.DEFAULT_URI, h.uri);
		h.module.cleanup();
		settle();
	}

	@Test
	void pageOrderFollowsPosition() throws Exception {
		importPages("rcOrder", "",
				"<file name=\"later\" type=\"deckPage\" position=\"20\">" + slot("s", attr("action", "rc.a")) + "</file>"
						+ "<file name=\"first\" type=\"deckPage\" position=\"10\">" + slot("s", attr("action", "rc.a"))
						+ "</file>");
		Harness h = install("rcOrder");
		assertEquals("first,later", h.client.orders.get(h.client.orders.size() - 1));
		assertEquals("first", h.client.renders.get(0).page(), "the first page renders first, so it shows first");
		h.module.cleanup();
		settle();
	}

	@Test
	void badSlotsAreSkippedAndTheRestLoad() throws Exception {
		importPages("rcBad", "", page("main", //
				slot("good", attr("action", "rc.a")), //
				slot("unknownAction", attr("action", "rc.missing")), //
				slot("noAttributes"), //
				slot("badNav", attr("nav", "sideways")), //
				slot("emptyNavPage", attr("nav", "page:")), //
				slot("oneSided", attr("left", "rc.a")), //
				slot("unknownPress", attr("left", "rc.a"), attr("right", "rc.b"), attr("press", "rc.missing")), //
				slot("halfHeld", attr("left", "rc.a"), attr("right", "rc.b"), attr("heldLeft", "rc.c"))));
		Harness h = install("rcBad");
		assertEquals(List.of("good"),
				h.client.renders.stream().map(Render::slotId).distinct().collect(Collectors.toList()));
		h.module.cleanup();
		settle();
	}

	@Test
	void laterDescriptorsMergeIntoAPage() throws Exception {
		importPages("rcMerge", "", page("main", slot("D200X:1_1", attr("action", "rc.a"))));
		importPages("rcMerge", "", page("main", slot("D200X:1_2", attr("action", "rc.b"))));
		Harness h = install("rcMerge");
		assertEquals("rc.a", h.client.slot("D200X:1_1").text());
		assertEquals("rc.b", h.client.slot("D200X:1_2").text());
		h.module.cleanup();
		settle();
	}

	@Test
	void actionWithoutIconShowsTextOnlyAndRuns() throws Exception {
		importPages("rcPlain", "", page("main", slot("s", attr("action", "rc.plain"))));
		Harness h = install("rcPlain");
		assertNull(h.client.slot("s").text());
		assertTrue(h.client.slot("s").hasImage(), "a black image");
		runs.clear();
		h.binder.onInput(new SlotInput("main", "s", SlotInput.Type.RUN, null, false));
		settle();
		assertEquals(List.of("rc.plain"), runs);
		h.module.cleanup();
		settle();
	}

	@Test
	void getBinderFollowsTheLifecycle() throws Exception {
		importPages("rcActive", "", page("main", slot("s", attr("action", "rc.a"))));
		Harness h = install("rcActive");
		assertSame(h.binder, UlanziStudioPlugin.getBinder("myApp"));
		h.module.cleanup();
		settle();
		assertNull(UlanziStudioPlugin.getBinder("myApp"));
	}

	@Test
	void eachAppIdGetsItsOwnConnectionAndPages() throws Exception {
		String xml = "<root xmlns=\"" + NS + "\"><file name=\"rcMulti\">" + attr("uri", "ws://127.0.0.1:2") //
				+ "<file name=\"appA\"><file name=\"pages\">" + page("a", slot("s", attr("action", "rc.a"))) + "</file></file>" //
				+ "<file name=\"appB\">" + attr("uri", "ws://127.0.0.1:3") + "<file name=\"pages\">"
				+ page("b", slot("s", attr("action", "rc.b"))) + "</file></file>" //
				+ "<file name=\"noPages\"/>" //
				+ "<file name=\"badUri\">" + attr("uri", "not a uri") + "<file name=\"pages\">"
				+ page("c", slot("s", attr("action", "rc.c"))) + "</file></file>" //
				+ "</file></root>";
		FileSystem.getDefault().importXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rcMulti");
		Map<String, RecordingClient> clients = new ConcurrentHashMap<>();
		Map<String, URI> uris = new ConcurrentHashMap<>();
		UlanziStudioPlugin module = new UlanziStudioPlugin("rcMulti", (uri, appId, group) -> {
			uris.put(appId, uri);
			return new DeckBinder(listener -> {
				RecordingClient client = new RecordingClient(uri, appId, listener);
				clients.put(appId, client);
				return client;
			});
		});
		runOnFx(module::install);
		settle();
		assertEquals(Set.of("appA", "appB"), clients.keySet(), "noPages has nothing to do, badUri fails alone");
		assertEquals(URI.create("ws://127.0.0.1:2"), uris.get("appA"), "from the deck folder");
		assertEquals(URI.create("ws://127.0.0.1:3"), uris.get("appB"), "the app's own wins");
		assertEquals("rc.a", clients.get("appA").slot("s").text());
		assertEquals("rc.b", clients.get("appB").slot("s").text());
		assertTrue(clients.get("appA").started && clients.get("appB").started);
		assertNotNull(UlanziStudioPlugin.getBinder("appA"));
		assertNotNull(UlanziStudioPlugin.getBinder("appB"));
		assertNull(UlanziStudioPlugin.getBinder("noPages"));
		module.cleanup();
		settle();
		assertNull(UlanziStudioPlugin.getBinder("appA"));
	}

	@Test
	void groupAttributeReachesTheBinder() throws Exception {
		importPages("rcGroup", attr("group", "java"), page("main", slot("s", attr("action", "rc.a"))));
		Harness h = install("rcGroup");
		assertEquals("java", h.group);
		h.module.cleanup();
		settle();
		importPages("rcNoGroup", "", page("main", slot("s", attr("action", "rc.a"))));
		Harness none = install("rcNoGroup");
		assertNull(none.group);
		none.module.cleanup();
		settle();
	}

	@Test
	void aNestedRootPathIsFound() throws Exception {
		String xml = "<root xmlns=\"" + NS + "\"><file name=\"rcNested\"><file name=\"UlanziDeck\"><file name=\"myApp\"><file name=\"pages\">"
				+ page("main", slot("s", attr("action", "rc.a"))) + "</file></file></file></file></root>";
		FileSystem.getDefault().importXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rcNested");
		Harness h = install("rcNested/UlanziDeck");
		assertEquals("myApp", h.appId);
		h.module.cleanup();
		settle();
	}

	@Test
	void badUriDoesntStopTheApp() throws Exception {
		importPages("rcBadUri", attr("uri", "not a uri"), page("main", slot("s", attr("action", "rc.a"))));
		assertNull(install("rcBadUri").client);
	}
}
