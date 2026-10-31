package cz.bliksoft.ulanzi.fx;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import cz.bliksoft.javautils.app.ui.actions.IUIAction;
import cz.bliksoft.javautils.app.ui.actions.UIActions;
import cz.bliksoft.javautils.xmlfilesystem.FileLoader;
import cz.bliksoft.javautils.xmlfilesystem.FileObject;
import cz.bliksoft.ulanzi.client.PageNav;

/**
 * Loads a {@link DeckPage} from a filesystem node of type {@value #TYPE}. See
 * {@code docs/xml-pages.md} for the format. A slot that can't be used is logged
 * and skipped, so the rest of its page still works.
 */
public class DeckPageFileLoader extends FileLoader {
	public static final String TYPE = "deckPage";

	private static final Logger log = System.getLogger(DeckPageFileLoader.class.getName());
	private static final String NAV_PAGE_PREFIX = "page:";

	/** Thrown for a slot definition that can't be used. */
	private static final class InvalidSlotException extends Exception {
		private static final long serialVersionUID = 1L;

		InvalidSlotException(String message) {
			super(message);
		}
	}

	@Override
	public String getSupportedType() {
		return TYPE;
	}

	@Override
	public Object loadObject(FileObject file) {
		DeckPage page = new DeckPage(file.getName());
		// getChildFiles() would leave out any node with children of its own
		for (FileObject slot : file.getChildren()) {
			try {
				page.bind(slot.getName(), loadSlot(slot));
			} catch (InvalidSlotException e) {
				log.log(Level.WARNING, "Deck page {0}, slot {1} skipped: {2}", file.getName(), slot.getName(),
						e.getMessage());
			}
		}
		return page;
	}

	private static SlotBinding loadSlot(FileObject slot) throws InvalidSlotException {
		String nav = slot.getAttribute("nav");
		if (nav != null)
			return SlotBinding.navigation(parseNav(nav), action(slot, "display"));
		String left = slot.getAttribute("left");
		String right = slot.getAttribute("right");
		if (left != null || right != null)
			return loadEncoder(slot);
		if (slot.getAttribute("action") == null)
			throw new InvalidSlotException("it needs nav, left and right, or action");
		return SlotBinding.button(action(slot, "action"));
	}

	private static SlotBinding loadEncoder(FileObject slot) throws InvalidSlotException {
		IUIAction left = action(slot, "left");
		IUIAction right = action(slot, "right");
		if (left == null || right == null)
			throw new InvalidSlotException("an encoder needs both left and right");
		SlotBinding binding = SlotBinding.encoder(left, right);
		IUIAction press = action(slot, "press");
		if (press != null)
			binding.press(press);
		IUIAction heldLeft = action(slot, "heldLeft");
		IUIAction heldRight = action(slot, "heldRight");
		if ((heldLeft == null) != (heldRight == null))
			throw new InvalidSlotException("heldLeft and heldRight go together");
		if (heldLeft != null)
			binding.held(heldLeft, heldRight);
		IUIAction display = action(slot, "display");
		if (display != null)
			binding.display(display);
		return binding;
	}

	private static PageNav parseNav(String nav) throws InvalidSlotException {
		if ("next".equals(nav))
			return PageNav.next();
		if ("previous".equals(nav))
			return PageNav.previous();
		if (nav.startsWith(NAV_PAGE_PREFIX) && nav.length() > NAV_PAGE_PREFIX.length())
			return PageNav.page(nav.substring(NAV_PAGE_PREFIX.length()));
		throw new InvalidSlotException("nav must be next, previous, or page:<id>, not " + nav);
	}

	/** The action an attribute names; {@code null} if the attribute is absent. */
	private static IUIAction action(FileObject slot, String attribute) throws InvalidSlotException {
		String key = slot.getAttribute(attribute);
		if (key == null)
			return null;
		IUIAction action = UIActions.getAction(key);
		if (action == null)
			throw new InvalidSlotException("no action with key " + key + " for " + attribute);
		return action;
	}
}
