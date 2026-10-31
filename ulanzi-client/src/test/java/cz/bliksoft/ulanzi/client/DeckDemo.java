package cz.bliksoft.ulanzi.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * Tries the client on a real deck: two pages of coloured slots with counters
 * that follow presses and dial steps. Arguments: appId (default {@code default}),
 * seconds to run (default until killed).
 */
public final class DeckDemo {
	private static final int KEY_SIZE = 196;
	private static final int KNOB_SIZE = 128;
	private static final Map<String, Color> PAGES = Map.of("one", new Color(37, 99, 235), "two", new Color(22, 163, 74));

	private DeckDemo() {
	}

	public static void main(String[] args) throws Exception {
		String appId = args.length > 0 ? args[0] : "default";
		Map<String, Slot> slots = new ConcurrentHashMap<>();
		Map<String, Integer> counters = new ConcurrentHashMap<>();
		DeckClient[] client = new DeckClient[1];

		client[0] = new DeckClient(appId, new DeckListener() {
			@Override
			public void onConnected(String currentPage) {
				System.out.println("connected, current page: " + currentPage);
			}

			@Override
			public void onDisconnected() {
				System.out.println("disconnected");
			}

			@Override
			public void onRefused(int closeCode, String reason) {
				System.out.println("refused: " + closeCode + " " + reason);
			}

			@Override
			public void onSlots(List<Slot> list) {
				System.out.println("slots: " + list);
				slots.clear();
				list.forEach(s -> slots.put(s.slotId(), s));
				for (String page : List.of("one", "two"))
					list.forEach(s -> paint(client[0], page, s, counters));
				client[0].setPageOrder(List.of("one", "two"));
			}

			@Override
			public void onPageChanged(String page) {
				System.out.println("page: " + page);
			}

			@Override
			public void onInput(SlotInput input) {
				System.out.println("input: " + input);
				Slot slot = slots.get(input.slotId());
				if (slot == null)
					return;
				int step = switch (input.type()) {
					case RUN -> 1;
					case ROTATE -> input.direction() == SlotInput.Direction.LEFT ? -1 : 1;
					default -> 0;
				};
				if (step != 0) {
					counters.merge(input.page() + "/" + input.slotId(), step, Integer::sum);
					paint(client[0], input.page(), slot, counters);
				}
			}
		});
		client[0].start();

		if (args.length > 1)
			Thread.sleep(Long.parseLong(args[1]) * 1000);
		else
			Thread.currentThread().join();
		client[0].close();
		// lets the close frame leave before the JVM exits
		Thread.sleep(200);
	}

	private static void paint(DeckClient client, String page, Slot slot, Map<String, Integer> counters) {
		if (!slot.hasDisplay())
			return;
		int count = counters.getOrDefault(page + "/" + slot.slotId(), 0);
		try {
			client.render(page, slot.slotId(), image(slot.isEncoder() ? KNOB_SIZE : KEY_SIZE, PAGES.get(page), count),
					page, null);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] image(int size, Color background, int count) throws IOException {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(background);
		g.fillRect(0, 0, size, size);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size / 2));
		String text = Integer.toString(count);
		var metrics = g.getFontMetrics();
		g.drawString(text, (size - metrics.stringWidth(text)) / 2, (size + metrics.getAscent() - metrics.getDescent()) / 2);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
