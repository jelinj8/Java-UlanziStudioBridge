package cz.bliksoft.ulanzi.fx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

import javafx.application.Platform;

/**
 * Starts JavaFX for test classes, or skips them where it can't run, as on the
 * headless CI runner. The outcome is remembered because after a failed start,
 * calling {@code Platform} again can block forever.
 */
final class FxToolkit {
	private static boolean attempted;
	private static String failure;

	private FxToolkit() {
	}

	static synchronized void startOrSkip() throws InterruptedException {
		if (!attempted) {
			attempted = true;
			failure = start();
		}
		if (failure != null)
			Assumptions.abort("JavaFX can't run here: " + failure);
	}

	private static String start() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		try {
			Platform.startup(started::countDown);
		} catch (LinkageError | RuntimeException e) {
			return e.toString();
		}
		if (!started.await(10, TimeUnit.SECONDS))
			return "it didn't start in time";
		Platform.setImplicitExit(false);
		return null;
	}
}
