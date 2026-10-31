package cz.bliksoft.ulanzi.client;

import java.util.Map;

/**
 * Makes a rendered slot switch pages when pressed. The bridge handles the press
 * itself and the app receives no input for it.
 */
public final class PageNav {
	private final Map<String, Object> json;

	private PageNav(Map<String, Object> json) {
		this.json = json;
	}

	public static PageNav page(String pageId) {
		return new PageNav(Map.of("page", pageId));
	}

	public static PageNav next() {
		return new PageNav(Map.of("to", "next"));
	}

	public static PageNav previous() {
		return new PageNav(Map.of("to", "previous"));
	}

	Map<String, Object> toJson() {
		return json;
	}
}
