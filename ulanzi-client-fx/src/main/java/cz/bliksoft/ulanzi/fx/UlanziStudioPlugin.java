package cz.bliksoft.ulanzi.fx;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cz.bliksoft.javautils.modules.ModuleBase;
import cz.bliksoft.javautils.xmlfilesystem.FileLoader;
import cz.bliksoft.javautils.xmlfilesystem.FileObject;
import cz.bliksoft.javautils.xmlfilesystem.FileSystem;
import cz.bliksoft.ulanzi.client.DeckClient;

/**
 * Connects the app to the deck, once per app ID with pages defined under
 * {@code RemoteControl/UlanziDeck/<appId>/pages}, and does nothing without any.
 * See {@code docs/xml-pages.md}.
 */
public class UlanziStudioPlugin extends ModuleBase {
	private static final Logger log = System.getLogger(UlanziStudioPlugin.class.getName());
	private static final String ROOT = "RemoteControl/UlanziDeck";
	private static final String PAGES = "pages";
	private static final String URI_ATTRIBUTE = "uri";

	private static final Map<String, DeckBinder> activeBinders = new ConcurrentHashMap<>();

	private final String root;
	private final BinderFactory binderFactory;
	private final Map<String, DeckBinder> binders = new LinkedHashMap<>();

	interface BinderFactory {
		DeckBinder create(URI uri, String appId, String group);
	}

	public UlanziStudioPlugin() {
		this(ROOT, DeckBinder::new);
	}

	UlanziStudioPlugin(String root, BinderFactory binderFactory) {
		this.root = root;
		this.binderFactory = binderFactory;
	}

	/**
	 * The binder for showing pages or adding pages built in code.
	 *
	 * @return the app ID's binder, or {@code null} if it has no pages configured
	 */
	public static DeckBinder getBinder(String appId) {
		return activeBinders.get(appId);
	}

	@Override
	public String getModuleName() {
		return "UlanziStudioPlugin";
	}

	@Override
	public void install() {
		FileObject deck = FileSystem.getFile(root);
		if (deck == null) {
			log.log(Level.DEBUG, "No deck configuration under {0}", root);
			return;
		}
		for (FileObject app : deck.getChildren()) {
			// a deck problem must never stop the app from starting, or the other app IDs
			try {
				installApp(deck, app);
			} catch (RuntimeException e) {
				log.log(Level.ERROR, "Can't start the deck remote control for " + app.getName(), e);
			}
		}
	}

	@Override
	public void cleanup() {
		binders.forEach((appId, binder) -> {
			binder.close();
			activeBinders.remove(appId, binder);
		});
		binders.clear();
	}

	private void installApp(FileObject deck, FileObject app) {
		List<DeckPage> pages = loadPages(app);
		if (pages.isEmpty()) {
			log.log(Level.DEBUG, "No deck pages configured for {0}", app.getName());
			return;
		}
		String uri = app.getAttribute(URI_ATTRIBUTE, deck.getAttribute(URI_ATTRIBUTE, DeckClient.DEFAULT_URI.toString()));
		DeckBinder binder = binderFactory.create(URI.create(uri), app.getName(), app.getAttribute("group"));
		pages.forEach(binder::register);
		binder.start();
		binders.put(app.getName(), binder);
		activeBinders.put(app.getName(), binder);
	}

	private static List<DeckPage> loadPages(FileObject app) {
		List<DeckPage> pages = new ArrayList<>();
		FileObject folder = app.getFile(PAGES);
		if (folder == null)
			return pages;
		for (FileObject node : folder.getChildren()) {
			if (!DeckPageFileLoader.TYPE.equals(node.getType())) {
				log.log(Level.WARNING, "{0} skipped: a deck page needs type {1}", node.getName(),
						DeckPageFileLoader.TYPE);
				continue;
			}
			pages.add(FileLoader.loadFile(node));
		}
		return pages;
	}
}
