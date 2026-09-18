package top.fish1000.mcmcl.helper.protocol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON parser/writer for the JSON Lines control plane.
 */
public final class Json {
    private Json() {
    }

    public static Object parse(String text) throws ProtocolException {
        if (text == null) {
            throw new ProtocolException("JSON line is null");
        }
        Parser parser = new Parser(text);
        Object result = parser.value();
        parser.whitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected characters after JSON value");
        }
        return result;
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(value, output);
        return output.toString();
    }

    public static boolean isJsonScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    public static String requiredString(Map<String, Object> object, String key) throws ProtocolException {
        Object value = object.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ProtocolException(key + " must be a non-blank JSON string");
        }
        return string;
    }

    public static String optionalString(Map<String, Object> object, String key) throws ProtocolException {
        if (!object.containsKey(key) || object.get(key) == null) {
            return null;
        }
        Object value = object.get(key);
        if (!(value instanceof String string)) {
            throw new ProtocolException(key + " must be a JSON string when present");
        }
        return string;
    }

    public static boolean optionalBoolean(Map<String, Object> object, String key, boolean defaultValue)
            throws ProtocolException {
        if (!object.containsKey(key) || object.get(key) == null) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof Boolean bool)) {
            throw new ProtocolException(key + " must be a JSON boolean when present");
        }
        return bool;
    }

    public static String requiredInstanceId(Map<String, Object> object) throws ProtocolException {
        String instanceId = requiredString(object, "instanceId");
        if (!isSafeInstanceId(instanceId)) {
            throw new ProtocolException("instanceId must be a non-blank path segment");
        }
        return instanceId;
    }

    public static boolean isSafeInstanceId(String instanceId) {
        return !instanceId.isBlank()
                && !instanceId.equals(".")
                && !instanceId.equals("..")
                && !instanceId.contains("/")
                && !instanceId.contains("\\")
                && !instanceId.contains("\u0000");
    }

    private static void writeValue(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            writeString(string, output);
        } else if (value instanceof Number number) {
            if (number instanceof Double d && (!Double.isFinite(d))
                    || number instanceof Float f && (!Float.isFinite(f))) {
                throw new IllegalArgumentException("JSON does not support non-finite numbers");
            }
            output.append(number);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeString(key, output);
                output.append(':');
                writeValue(entry.getValue(), output);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeValue(item, output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object value() throws ProtocolException {
            whitespace();
            if (atEnd()) {
                throw error("expected a JSON value");
            }
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() throws ProtocolException {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                whitespace();
                if (atEnd() || text.charAt(index) != '"') {
                    throw error("expected an object key");
                }
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array() throws ProtocolException {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value());
                whitespace();
                if (take(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String string() throws ProtocolException {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char character = text.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character < 0x20) {
                    throw error("unescaped control character in string");
                }
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape sequence");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicodeEscape());
                    default -> throw error("invalid escape sequence: \\" + escaped);
                }
            }
            throw error("unterminated string");
        }

        private char unicodeEscape() throws ProtocolException {
            if (index + 4 > text.length()) {
                throw error("incomplete unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object number() throws ProtocolException {
            int start = index;
            if (take('-')) {
                if (atEnd()) {
                    throw error("invalid number");
                }
            }
            if (take('0')) {
                if (!atEnd() && Character.isDigit(text.charAt(index))) {
                    throw error("leading zero in number");
                }
            } else {
                if (atEnd() || !isDigitOneToNine(text.charAt(index))) {
                    throw error("invalid number");
                }
                while (!atEnd() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (take('.')) {
                if (atEnd() || !Character.isDigit(text.charAt(index))) {
                    throw error("invalid fractional number");
                }
                while (!atEnd() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (!atEnd() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (!atEnd() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                if (atEnd() || !Character.isDigit(text.charAt(index))) {
                    throw error("invalid exponent");
                }
                while (!atEnd() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String literal = text.substring(start, index);
            try {
                return new BigDecimal(literal);
            } catch (NumberFormatException e) {
                throw error("invalid number");
            }
        }

        private Object literal(String expected, Object value) throws ProtocolException {
            if (!text.startsWith(expected, index)) {
                throw error("invalid literal");
            }
            index += expected.length();
            return value;
        }

        private void whitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) throws ProtocolException {
            if (atEnd() || text.charAt(index) != expected) {
                throw error("expected '" + expected + "'");
            }
            index++;
        }

        private boolean take(char expected) {
            if (!atEnd() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean atEnd() {
            return index >= text.length();
        }

        private ProtocolException error(String message) {
            return new ProtocolException(message + " at character " + index);
        }

        private static boolean isDigitOneToNine(char character) {
            return character >= '1' && character <= '9';
        }
    }
}
