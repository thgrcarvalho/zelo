package io.github.thgrcarvalho.zelo.domain.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic JSON serialization for hashing.
 *
 * <p>The audit hash chain must be reproducible: the same logical payload has to
 * hash to the same value at write time and again at verification time, even
 * though the payload makes a round trip through Postgres {@code jsonb} (which
 * reorders object keys and normalizes whitespace and number representations) in
 * between. This canonicalizer removes every one of those degrees of freedom:</p>
 *
 * <ul>
 *   <li>object keys are emitted in Unicode code-point order;</li>
 *   <li>numbers are normalized via {@link java.math.BigDecimal#stripTrailingZeros()}
 *       so {@code 1}, {@code 1.0} and {@code 1.00} all canonicalize identically;</li>
 *   <li>strings use a single fixed JSON escaping;</li>
 *   <li>no insignificant whitespace is emitted.</li>
 * </ul>
 *
 * <p>It is intentionally hand-rolled rather than relying on Jackson configuration
 * flags — the moat depends on this being explicit and stable.</p>
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // Parse JSON numbers losslessly so number normalization is exact.
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private CanonicalJson() {
    }

    /** Canonicalize an in-memory JSON tree. */
    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    /** Parse {@code json} (e.g. read back from a jsonb column) and canonicalize it. */
    public static String canonicalize(String json) {
        try {
            return canonicalize(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + json, e);
        }
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            sb.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeString(names.get(i), sb);
                sb.append(':');
                write(node.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isTextual()) {
            writeString(node.textValue(), sb);
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNumber()) {
            // Normalize so scale/representation differences (incl. those a jsonb
            // round trip can introduce) never change the hash.
            sb.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            writeString(node.asText(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
