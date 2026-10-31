package cz.bliksoft.ulanzi.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON codec, so the client needs no runtime dependencies. Objects are
 * {@link Map}s, arrays {@link List}s, numbers {@link Double}s.
 */
final class Json {
	private final String text;
	private int pos;

	private Json(String text) {
		this.text = text;
	}

	static Object parse(String text) {
		Json parser = new Json(text);
		Object value = parser.value();
		parser.whitespace();
		if (parser.pos != text.length())
			throw parser.error("trailing content");
		return value;
	}

	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(value, out);
		return out.toString();
	}

	private static void write(Object value, StringBuilder out) {
		if (value == null || value instanceof Boolean || value instanceof Number) {
			out.append(value);
		} else if (value instanceof String s) {
			writeString(s, out);
		} else if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first)
					out.append(',');
				first = false;
				writeString((String) e.getKey(), out);
				out.append(':');
				write(e.getValue(), out);
			}
			out.append('}');
		} else if (value instanceof List<?> list) {
			out.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0)
					out.append(',');
				write(list.get(i), out);
			}
			out.append(']');
		} else {
			throw new IllegalArgumentException("Not a JSON value: " + value.getClass());
		}
	}

	private static void writeString(String s, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20)
						out.append(String.format("\\u%04x", (int) c));
					else
						out.append(c);
				}
			}
		}
		out.append('"');
	}

	private Object value() {
		whitespace();
		if (pos >= text.length())
			throw error("unexpected end");
		char c = text.charAt(pos);
		return switch (c) {
			case '{' -> object();
			case '[' -> array();
			case '"' -> string();
			case 't' -> literal("true", Boolean.TRUE);
			case 'f' -> literal("false", Boolean.FALSE);
			case 'n' -> literal("null", null);
			default -> number();
		};
	}

	private Map<String, Object> object() {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++;
		whitespace();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			whitespace();
			if (peek() != '"')
				throw error("expected a string key");
			String key = string();
			whitespace();
			if (peek() != ':')
				throw error("expected ':'");
			pos++;
			map.put(key, value());
			whitespace();
			char c = peek();
			pos++;
			if (c == '}')
				return map;
			if (c != ',')
				throw error("expected ',' or '}'");
		}
	}

	private List<Object> array() {
		List<Object> list = new ArrayList<>();
		pos++;
		whitespace();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			list.add(value());
			whitespace();
			char c = peek();
			pos++;
			if (c == ']')
				return list;
			if (c != ',')
				throw error("expected ',' or ']'");
		}
	}

	private String string() {
		StringBuilder out = new StringBuilder();
		pos++;
		while (true) {
			char c = peek();
			pos++;
			if (c == '"')
				return out.toString();
			if (c != '\\') {
				out.append(c);
				continue;
			}
			char escape = peek();
			pos++;
			switch (escape) {
				case '"', '\\', '/' -> out.append(escape);
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'u' -> {
					if (pos + 4 > text.length())
						throw error("bad unicode escape");
					try {
						out.append((char) Integer.parseInt(text, pos, pos + 4, 16));
					} catch (NumberFormatException e) {
						throw error("bad unicode escape");
					}
					pos += 4;
				}
				default -> throw error("bad escape");
			}
		}
	}

	private Object literal(String word, Object value) {
		if (!text.startsWith(word, pos))
			throw error("unexpected token");
		pos += word.length();
		return value;
	}

	private Double number() {
		int start = pos;
		while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0)
			pos++;
		try {
			return Double.valueOf(text.substring(start, pos));
		} catch (NumberFormatException e) {
			throw error("bad number");
		}
	}

	private char peek() {
		if (pos >= text.length())
			throw error("unexpected end");
		return text.charAt(pos);
	}

	private void whitespace() {
		while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0)
			pos++;
	}

	private IllegalArgumentException error(String message) {
		return new IllegalArgumentException("Invalid JSON at " + pos + ": " + message);
	}
}
