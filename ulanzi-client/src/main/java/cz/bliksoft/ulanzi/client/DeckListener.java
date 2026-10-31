package cz.bliksoft.ulanzi.client;

import java.util.List;

/**
 * Receives events from a {@link DeckClient}. Methods run one at a time on the
 * client's network thread, so slow work belongs elsewhere: dials can send more
 * than 50 events per second.
 */
public interface DeckListener {
	/**
	 * The bridge accepted this app.
	 *
	 * @param currentPage the page the bridge is showing, or {@code null} if it holds
	 *                    no pages for this app (first connection, or the bridge
	 *                    restarted), in which case the app must render again
	 */
	default void onConnected(String currentPage) {
	}

	/** The connection was lost. The client keeps trying to reconnect. */
	default void onDisconnected() {
	}

	/**
	 * The bridge refused this client and it won't reconnect: another connection took
	 * over this appId, or the protocol version isn't supported.
	 */
	default void onRefused(int closeCode, String reason) {
	}

	/** The full list of slots assigned to this app. */
	default void onSlots(List<Slot> slots) {
	}

	default void onPageChanged(String page) {
	}

	default void onInput(SlotInput input) {
	}
}
