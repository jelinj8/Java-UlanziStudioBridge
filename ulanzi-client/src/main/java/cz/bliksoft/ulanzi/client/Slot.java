package cz.bliksoft.ulanzi.client;

/**
 * A deck slot assigned to this app.
 *
 * @param slotId     identifier used in {@link DeckClient#render} and input events
 * @param controller {@code Keypad} or {@code Encoder}
 * @param device     device model, e.g. {@code D200X} or {@code Dial}
 * @param hasDisplay whether anything rendered to this slot can be seen
 */
public record Slot(String slotId, String controller, String device, boolean hasDisplay) {
	public boolean isEncoder() {
		return "Encoder".equals(controller);
	}
}
