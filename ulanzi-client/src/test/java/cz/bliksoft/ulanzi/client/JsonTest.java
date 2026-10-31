package cz.bliksoft.ulanzi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {
	@Test
	void parsesAllValueTypes() {
		assertEquals(Map.of("a", List.of(1.0, true, "x"), "b", Map.of()),
				Json.parse(" { \"a\" : [ 1 , true , \"x\" ] , \"b\" : { } } "));
		assertEquals(null, Json.parse("null"));
		assertEquals(-1.5e2, Json.parse("-1.5e2"));
	}

	@Test
	void stringEscapesRoundTrip() {
		String original = "quote\" back\\ line\n tab\t ctl\u0001 č\uD83D\uDE00";
		assertEquals(original, Json.parse(Json.write(original)));
		assertEquals("č/", Json.parse("\"\\u010d\\/\""));
	}

	@Test
	void writesCollectionsAndNull() {
		assertEquals("{\"a\":[1,null,\"b\"]}",
				Json.write(Map.of("a", java.util.Arrays.asList(1, null, "b"))));
	}

	@Test
	void rejectsMalformedInput() {
		for (String bad : List.of("", "{", "[1,", "{\"a\"}", "\"open", "tru", "1 2", "\"\\u12\"", "\"\\q\""))
			assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
	}
}
