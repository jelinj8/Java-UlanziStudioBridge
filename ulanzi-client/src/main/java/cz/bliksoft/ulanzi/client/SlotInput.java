package cz.bliksoft.ulanzi.client;

/**
 * Input from a slot.
 *
 * <p>
 * Keys send {@link Type#DOWN}, {@link Type#RUN}, {@link Type#UP}, with RUN on
 * press rather than on release. Dials send {@link Type#RUN} and
 * {@link Type#ROTATE} only, one ROTATE per step.
 *
 * @param page      the page showing when the input happened
 * @param slotId    the slot
 * @param type      the kind of input
 * @param direction rotation direction, {@code null} unless {@link Type#ROTATE}
 * @param held      whether the dial was pressed while rotating
 */
public record SlotInput(String page, String slotId, Type type, Direction direction, boolean held) {
	public enum Type {
		DOWN, UP, RUN, ROTATE
	}

	public enum Direction {
		LEFT, RIGHT
	}
}
