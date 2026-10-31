package cz.bliksoft.ulanzi.fx;

import java.util.concurrent.CountDownLatch;

import cz.bliksoft.javautils.app.ui.actions.UIActionBase;
import cz.bliksoft.ulanzi.client.DeckClient;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/**
 * Tries focus routing on a real deck. Run two of these with different names: each
 * opens a window, joins group {@code default}, and shows its own counter on slots
 * {@code D200X:1_1} and {@code D200X:2_3}. Clicking a window should move those
 * slots to it. Arguments: app name, seconds to run (default 300).
 */
public final class FocusDemo {
	private static final String CIRCLE = "[P]:M50 5A45 45 0 1 0 50 95A45 45 0 1 0 50 5Z";
	private static final String SQUARE = "[P]:M10 10H90V90H10Z";

	private static int count;

	private FocusDemo() {
	}

	public static void main(String[] args) throws Exception {
		String name = args[0];
		long seconds = args.length > 1 ? Long.parseLong(args[1]) : 300;
		CountDownLatch started = new CountDownLatch(1);
		Platform.startup(started::countDown);
		started.await();

		String icon = name.hashCode() % 2 == 0 ? CIRCLE : SQUARE;
		UIActionBase display = action(name + ".display", name + " 0", icon, () -> {
		});
		UIActionBase up = action(name + ".up", "up", icon, () -> step(name, display, 1));
		UIActionBase down = action(name + ".down", "down", icon, () -> step(name, display, -1));
		UIActionBase press = action(name + ".press", name, icon, () -> {
			System.out.println(name + " key pressed");
			step(name, display, 1);
		});

		try (DeckBinder binder = new DeckBinder(DeckClient.DEFAULT_URI, name, "default")) {
			binder.register(new DeckPage("main")
					.bind("D200X:1_1", SlotBinding.button(press))
					.bind("D200X:2_3", SlotBinding.encoder(down, up).display(display)));
			binder.start();
			Platform.runLater(() -> {
				Stage stage = new Stage();
				stage.setTitle(name);
				stage.setScene(new Scene(new Label("  Click here to give " + name + " the deck  "), 320, 80));
				stage.focusedProperty().addListener((o, was, focused) -> System.out.println(name + " focused: " + focused));
				stage.show();
			});
			Thread.sleep(seconds * 1000);
		}
		// lets the close frame leave before the JVM exits
		Thread.sleep(200);
		Platform.exit();
	}

	private static UIActionBase action(String key, String text, String icon, Runnable work) {
		UIActionBase action = UIActionBase.of(key, text, null, null, work);
		action.setIconSpec(icon);
		return action;
	}

	private static void step(String name, UIActionBase display, int delta) {
		count += delta;
		display.setText(name + " " + count);
		System.out.println(name + " count " + count);
	}
}
