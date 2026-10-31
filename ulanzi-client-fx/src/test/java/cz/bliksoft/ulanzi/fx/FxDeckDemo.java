package cz.bliksoft.ulanzi.fx;

import java.util.concurrent.CountDownLatch;

import cz.bliksoft.javautils.app.ui.actions.UIActionBase;
import javafx.application.Platform;

/**
 * Tries the UI action bindings on a real deck: a counter on knob {@code 2_3}, and
 * key {@code 1_1} that resets it and swaps its own icon. Arguments: appId (default
 * {@code default}), seconds to run (default 300).
 */
public final class FxDeckDemo {
	private static final String KNOB = "D200X:2_3";
	private static final String RESET_KEY = "D200X:1_1";
	private static final String CIRCLE = "[P]:M50 5A45 45 0 1 0 50 95A45 45 0 1 0 50 5Z";
	private static final String SQUARE = "[P]:M10 10H90V90H10Z";

	private static int count;

	private FxDeckDemo() {
	}

	public static void main(String[] args) throws Exception {
		String appId = args.length > 0 ? args[0] : "default";
		long seconds = args.length > 1 ? Long.parseLong(args[1]) : 300;
		CountDownLatch started = new CountDownLatch(1);
		Platform.startup(started::countDown);
		started.await();

		UIActionBase display = UIActionBase.of("count", "0", null, null, () -> {
		});
		display.setIconSpec(CIRCLE);
		UIActionBase left = step("left", display, -1);
		UIActionBase right = step("right", display, 1);

		UIActionBase[] reset = new UIActionBase[1];
		reset[0] = UIActionBase.of("reset", "reset", null, null, () -> {
			count = 0;
			display.setText("0");
			reset[0].setIconSpec(SQUARE.equals(reset[0].iconSpecProperty().getValue()) ? CIRCLE : SQUARE);
			System.out.println("reset");
		});
		reset[0].setIconSpec(SQUARE);

		try (DeckBinder binder = new DeckBinder(appId)) {
			binder.register(new DeckPage("main")
					.bind(KNOB, SlotBinding.encoder(left, right).display(display))
					.bind(RESET_KEY, SlotBinding.button(reset[0])));
			binder.start();
			Thread.sleep(seconds * 1000);
		}
		// lets the close frame leave before the JVM exits
		Thread.sleep(200);
		Platform.exit();
	}

	private static UIActionBase step(String key, UIActionBase display, int delta) {
		return UIActionBase.of(key, key, null, null, () -> {
			count += delta;
			display.setText(Integer.toString(count));
			System.out.println("count " + count);
		});
	}
}
