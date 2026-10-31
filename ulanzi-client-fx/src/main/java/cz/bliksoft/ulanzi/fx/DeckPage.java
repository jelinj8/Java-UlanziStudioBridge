package cz.bliksoft.ulanzi.fx;

import java.util.LinkedHashMap;
import java.util.Map;

/** A named set of slot bindings. Fill it before {@link DeckBinder#register}. */
public final class DeckPage {
	private final String id;
	final Map<String, SlotBinding> bindings = new LinkedHashMap<>();

	public DeckPage(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	/** @param slotId the slot's ID, by default {@code <device>:<key>} such as {@code D200X:0_0} */
	public DeckPage bind(String slotId, SlotBinding binding) {
		bindings.put(slotId, binding);
		return this;
	}
}
