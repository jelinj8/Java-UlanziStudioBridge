package cz.bliksoft.ulanzi.fx;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import cz.bliksoft.javautils.app.ui.actions.UIActionBase;
import cz.bliksoft.javautils.app.ui.actions.UIActions;
import cz.bliksoft.javautils.xmlfilesystem.FileSystem;
import javafx.application.Platform;

/**
 * Tries XML-defined pages on a real deck: two pages, a counter on knob
 * {@code 2_3}, key {@code 1_1} resetting it, round buttons {@code 0_3} and
 * {@code 1_3} navigating, and key {@code 0_0} naming a missing action, which is
 * skipped. Arguments: seconds to run (default 300), then {@code none} to leave
 * out the configuration, which must open no connection.
 */
public final class XmlDeckDemo {
	private static final String CIRCLE = "[P]:M50 5A45 45 0 1 0 50 95A45 45 0 1 0 50 5Z";
	private static final String SQUARE = "[P]:M10 10H90V90H10Z";

	private static final String PAGES = """
			<root xmlns="http://bliksoft.cz/XmlFilesystem">
			  <file name="RemoteControl"><file name="UlanziDeck"><file name="default">
			    <file name="pages">
			      <file name="one" type="deckPage" position="10">
			        <file name="D200X:2_3">
			          <attribute name="left" value="demo.down"/>
			          <attribute name="right" value="demo.up"/>
			          <attribute name="press" value="demo.reset"/>
			          <attribute name="display" value="demo.count"/>
			        </file>
			        <file name="D200X:1_1"><attribute name="action" value="demo.reset"/></file>
			        <file name="D200X:0_0"><attribute name="action" value="demo.missing"/></file>
			        <file name="D200X:0_3"><attribute name="nav" value="previous"/></file>
			        <file name="D200X:1_3"><attribute name="nav" value="next"/></file>
			      </file>
			      <file name="two" type="deckPage" position="20">
			        <file name="D200X:1_1"><attribute name="action" value="demo.other"/></file>
			        <file name="D200X:0_3"><attribute name="nav" value="page:one"/></file>
			        <file name="D200X:1_3"><attribute name="nav" value="next"/></file>
			      </file>
			    </file>
			  </file></file></file>
			</root>
			""";

	private static int count;

	private XmlDeckDemo() {
	}

	public static void main(String[] args) throws Exception {
		long seconds = args.length > 0 ? Long.parseLong(args[0]) : 300;
		boolean configured = args.length < 2 || !"none".equals(args[1]);

		CountDownLatch started = new CountDownLatch(1);
		Platform.startup(started::countDown);
		started.await();

		UIActionBase display = action("demo.count", "0", CIRCLE, () -> {
		});
		UIActions.registerAction("demo.count", display, "demo");
		UIActions.registerAction("demo.up", action("demo.up", "up", CIRCLE, () -> step(display, 1)), "demo");
		UIActions.registerAction("demo.down", action("demo.down", "down", CIRCLE, () -> step(display, -1)), "demo");
		UIActions.registerAction("demo.reset", action("demo.reset", "reset", SQUARE, () -> step(display, -count)), "demo");
		UIActions.registerAction("demo.other", action("demo.other", "other", CIRCLE,
				() -> System.out.println("other")), "demo");

		UlanziStudioPlugin module = new UlanziStudioPlugin();
		FileSystem.getDefault().importXml(module.getFilesystemXml(), "module");
		if (configured)
			FileSystem.getDefault().importXml(new ByteArrayInputStream(PAGES.getBytes(StandardCharsets.UTF_8)), "demo");

		// modules install on the FX thread, from Application.start()
		CountDownLatch installed = new CountDownLatch(1);
		Platform.runLater(() -> {
			module.install();
			System.out.println("binder: " + UlanziStudioPlugin.getBinder("default"));
			installed.countDown();
		});
		installed.await();

		Thread.sleep(seconds * 1000);
		module.cleanup();
		// lets the close frame leave before the JVM exits
		Thread.sleep(200);
		Platform.exit();
	}

	private static UIActionBase action(String key, String text, String icon, Runnable work) {
		UIActionBase action = UIActionBase.of(key, text, null, null, work);
		action.setIconSpec(icon);
		return action;
	}

	private static void step(UIActionBase display, int delta) {
		count += delta;
		display.setText(Integer.toString(count));
		System.out.println("count " + count);
	}
}
