package cz.bliksoft.ulanzi.fx;

import cz.bliksoft.javautils.app.ui.actions.UIActionBase;
import cz.bliksoft.ulanzi.client.PageNav;

/**
 * What a slot shows and does on one {@link DeckPage}. The action's text, icon
 * spec, enabled and visible properties drive the display.
 *
 * <p>
 * {@link UIActionBase} creates its text and icon properties on the first setter
 * call, so an action must have them set before it is bound, or later changes
 * won't reach the deck.
 */
public final class SlotBinding {
	final boolean encoder;
	UIActionBase display;
	UIActionBase press;
	UIActionBase left;
	UIActionBase right;
	UIActionBase heldLeft;
	UIActionBase heldRight;
	PageNav nav;

	private SlotBinding(boolean encoder) {
		this.encoder = encoder;
	}

	/** A key that shows the action and runs it on press. */
	public static SlotBinding button(UIActionBase action) {
		SlotBinding binding = new SlotBinding(false);
		binding.display = action;
		binding.press = action;
		return binding;
	}

	/** A key or round button that switches pages, showing the action if given. */
	public static SlotBinding navigation(PageNav nav, UIActionBase display) {
		SlotBinding binding = new SlotBinding(false);
		binding.nav = nav;
		binding.display = display;
		return binding;
	}

	/**
	 * A dial running one action per rotation step. It shows the press action if set,
	 * else {@code left}; use {@link #display} to choose another. Rotation while
	 * pressed runs the same actions unless {@link #held} is set.
	 */
	public static SlotBinding encoder(UIActionBase left, UIActionBase right) {
		SlotBinding binding = new SlotBinding(true);
		binding.left = left;
		binding.right = right;
		binding.display = left;
		return binding;
	}

	public SlotBinding press(UIActionBase action) {
		if (display == left)
			display = action;
		press = action;
		return this;
	}

	public SlotBinding held(UIActionBase heldLeft, UIActionBase heldRight) {
		this.heldLeft = heldLeft;
		this.heldRight = heldRight;
		return this;
	}

	public SlotBinding display(UIActionBase action) {
		display = action;
		return this;
	}
}
