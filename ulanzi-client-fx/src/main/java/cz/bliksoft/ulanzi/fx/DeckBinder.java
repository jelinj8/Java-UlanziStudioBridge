package cz.bliksoft.ulanzi.fx;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import cz.bliksoft.javautils.app.ui.actions.IUIAction;
import cz.bliksoft.javautils.app.ui.interfaces.IIconSpecPropertyProvider;
import cz.bliksoft.ulanzi.client.DeckClient;
import cz.bliksoft.ulanzi.client.DeckListener;
import cz.bliksoft.ulanzi.client.SlotInput;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.Property;
import javafx.beans.value.ObservableBooleanValue;

/**
 * Keeps deck slots in step with UI actions, in switchable pages. Actions run on
 * the JavaFX thread, one per input event and in order. The JavaFX toolkit must be
 * running. Methods may be called from any thread.
 */
public class DeckBinder implements DeckListener, AutoCloseable {
	private static final Logger log = System.getLogger(DeckBinder.class.getName());
	private static final int KEY_IMAGE_SIZE = 196;
	private static final int KNOB_IMAGE_SIZE = 128;

	private final DeckClient client;
	private final IconRenderer renderer = new IconRenderer();
	// FX thread only
	private final Map<String, RegisteredPage> pages = new LinkedHashMap<>();

	public DeckBinder(String appId) {
		this(listener -> new DeckClient(appId, listener));
	}

	public DeckBinder(URI uri, String appId) {
		this(listener -> new DeckClient(uri, appId, listener));
	}

	DeckBinder(Function<DeckListener, DeckClient> clientFactory) {
		client = clientFactory.apply(this);
	}

	/** Starts connecting to the bridge; returns immediately. */
	public void start() {
		client.start();
	}

	/** Adds a page or replaces one with the same ID. The first page registered shows first. */
	public void register(DeckPage page) {
		onFxThread(() -> {
			RegisteredPage previous = pages.remove(page.getId());
			if (previous != null)
				previous.detach();
			RegisteredPage registered = new RegisteredPage(page);
			pages.put(page.getId(), registered);
			registered.renderAll();
			client.setPageOrder(List.copyOf(pages.keySet()));
		});
	}

	/** Removes a page, its listeners, and its images in the bridge. */
	public void unregister(String pageId) {
		onFxThread(() -> {
			RegisteredPage registered = pages.remove(pageId);
			if (registered != null) {
				registered.detach();
				client.clearPage(pageId);
				client.setPageOrder(List.copyOf(pages.keySet()));
			}
		});
	}

	public void showPage(String pageId) {
		client.showPage(pageId);
	}

	@Override
	public void close() {
		try {
			onFxThread(() -> {
				pages.values().forEach(RegisteredPage::detach);
				pages.clear();
			});
		} catch (IllegalStateException toolkitGone) {
			log.log(Level.DEBUG, "JavaFX has already exited, so listeners stay attached");
		}
		client.close();
	}

	@Override
	public void onConnected(String currentPage) {
		// changes made while disconnected were dropped, so everything is sent again
		onFxThread(() -> {
			pages.values().forEach(RegisteredPage::renderAll);
			client.setPageOrder(List.copyOf(pages.keySet()));
		});
	}

	@Override
	public void onInput(SlotInput input) {
		onFxThread(() -> {
			RegisteredPage page = pages.get(input.page());
			SlotBinding binding = page == null ? null : page.source.bindings.get(input.slotId());
			if (binding != null)
				run(actionFor(binding, input));
		});
	}

	private static IUIAction actionFor(SlotBinding binding, SlotInput input) {
		return switch (input.type()) {
			case RUN -> binding.press;
			case ROTATE -> {
				boolean isLeft = input.direction() == SlotInput.Direction.LEFT;
				IUIAction held = isLeft ? binding.heldLeft : binding.heldRight;
				IUIAction plain = isLeft ? binding.left : binding.right;
				yield input.held() && held != null ? held : plain;
			}
			case DOWN, UP -> null;
		};
	}

	private static void run(IUIAction action) {
		if (action == null || !isTrueOrUnset(action.enabledProperty()) || !isTrueOrUnset(action.visibleProperty()))
			return;
		try {
			action.execute();
		} catch (RuntimeException e) {
			log.log(Level.ERROR, "Action " + action.getKey() + " failed", e);
		}
	}

	private static Property<String> iconSpecOf(IUIAction action) {
		return action instanceof IIconSpecPropertyProvider provider ? provider.iconSpecProperty() : null;
	}

	private static boolean isTrueOrUnset(ObservableBooleanValue value) {
		return value == null || value.get();
	}

	private static void onFxThread(Runnable work) {
		if (Platform.isFxApplicationThread())
			work.run();
		else
			Platform.runLater(work);
	}

	/** A page's listeners on its actions, so they can be removed with it. */
	private final class RegisteredPage {
		final DeckPage source;
		private final List<Runnable> detachers = new ArrayList<>();

		RegisteredPage(DeckPage source) {
			this.source = source;
			source.bindings.forEach((slotId, binding) -> {
				IUIAction action = binding.display;
				if (action == null)
					return;
				// properties may change on any thread, painting stays on the FX thread
				InvalidationListener repaint = o -> onFxThread(() -> {
					if (pages.get(source.getId()) == this)
						render(slotId, binding);
				});
				for (Observable property : new Observable[] { action.textProperty(), iconSpecOf(action),
						action.enabledProperty(), action.visibleProperty() }) {
					if (property != null) {
						property.addListener(repaint);
						detachers.add(() -> property.removeListener(repaint));
					}
				}
			});
		}

		void detach() {
			detachers.forEach(Runnable::run);
			detachers.clear();
		}

		void renderAll() {
			source.bindings.forEach(this::render);
		}

		private void render(String slotId, SlotBinding binding) {
			IUIAction action = binding.display;
			if (action == null) {
				client.render(source.getId(), slotId, null, null, binding.nav);
				return;
			}
			boolean visible = isTrueOrUnset(action.visibleProperty());
			Property<String> iconSpec = iconSpecOf(action);
			String spec = !visible || iconSpec == null ? null : iconSpec.getValue();
			String text = !visible || action.textProperty() == null ? null : action.textProperty().get();
			boolean dimmed = !isTrueOrUnset(action.enabledProperty());
			byte[] png = renderer.render(spec, binding.encoder ? KNOB_IMAGE_SIZE : KEY_IMAGE_SIZE, dimmed);
			client.render(source.getId(), slotId, png, text, binding.nav);
		}
	}
}
