package cz.bliksoft.ulanzi.fx;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import cz.bliksoft.javautils.app.ui.actions.IconBinder;
import cz.bliksoft.javautils.fx.tools.ImageUtils;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;

/** Renders icon specs to PNG for the deck. FX thread only. */
final class IconRenderer {
	private static final Logger log = System.getLogger(IconRenderer.class.getName());
	private static final double DISABLED_OPACITY = 0.35;
	private static final double ICON_FILL = 0.75;

	private final Map<String, byte[]> cache = new HashMap<>();

	/** @param spec the icon spec, or {@code null} or blank for a black image */
	byte[] render(String spec, int size, boolean dimmed) {
		String key = spec + '|' + size + '|' + dimmed;
		if (cache.containsKey(key))
			return cache.get(key);
		byte[] png = draw(spec, size, dimmed);
		// a failed spec isn't retried on each repaint either
		cache.put(key, png);
		return png;
	}

	private static byte[] draw(String spec, int size, boolean dimmed) {
		try {
			StackPane pane = new StackPane();
			if (spec != null && !spec.isBlank()) {
				Node icon = ImageUtils.getIconNode(spec.trim());
				if (icon != null) {
					// shapes default to black, invisible on the deck's background
					if (icon instanceof Shape shape && (shape.getStyle() == null || shape.getStyle().isBlank()))
						shape.setFill(Color.WHITE);
					IconBinder.enforceIconSize(icon, size * ICON_FILL);
					icon.setOpacity(dimmed ? DISABLED_OPACITY : 1);
					pane.getChildren().add(icon);
				}
			}
			pane.resize(size, size);
			SnapshotParameters params = new SnapshotParameters();
			params.setFill(Color.BLACK);
			WritableImage image = pane.snapshot(params, new WritableImage(size, size));
			BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(buffered, "png", out);
			return out.toByteArray();
		} catch (IOException | RuntimeException e) {
			log.log(Level.WARNING, "Can't render icon " + spec, e);
			return null;
		}
	}
}
