package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CanonicalJson {
    private CanonicalJson() {
    }

    static Object parse(String json, String sourcePath) throws CanonicalMapFormatException {
        if (json == null) {
            throw new CanonicalMapFormatException(
                "MALFORMED_JSON", sourcePath, "JSON content must not be null"
            );
        }
        return new Parser(json, sourcePath).parse();
    }

    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(output, value, 0);
        output.append('\n');
        return output.toString();
    }

    private static void writeValue(StringBuilder output, Object value, int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            writeString(output, (String) value);
        } else if (value instanceof Boolean || value instanceof Integer
            || value instanceof Long) {
            output.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON number must be finite");
            }
            output.append(value);
        } else if (value instanceof Map) {
            writeObject(output, (Map<?, ?>) value, depth);
        } else if (value instanceof List) {
            writeArray(output, (List<?>) value, depth);
        } else {
            throw new IllegalArgumentException(
                "unsupported JSON value type: " + value.getClass().getName()
            );
        }
    }

    private static void writeObject(StringBuilder output, Map<?, ?> map, int depth) {
        output.append('{');
        if (!map.isEmpty()) output.append('\n');
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            indent(output, depth + 1);
            writeString(output, (String) entry.getKey());
            output.append(": ");
            writeValue(output, entry.getValue(), depth + 1);
            if (++index < map.size()) output.append(',');
            output.append('\n');
        }
        if (!map.isEmpty()) indent(output, depth);
        output.append('}');
    }

    private static void writeArray(StringBuilder output, List<?> values, int depth) {
        output.append('[');
        if (!values.isEmpty()) output.append('\n');
        for (int index = 0; index < values.size(); index++) {
            indent(output, depth + 1);
            writeValue(output, values.get(index), depth + 1);
            if (index + 1 < values.size()) output.append(',');
            output.append('\n');
        }
        if (!values.isEmpty()) indent(output, depth);
        output.append(']');
    }

    private static void writeString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                        "JSON strings must not contain unpaired UTF-16 surrogates"
                    );
                }
                output.append(character);
                output.append(value.charAt(++index));
                continue;
            }
            if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(
                    "JSON strings must not contain unpaired UTF-16 surrogates"
                );
            }
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        String hex = Integer.toHexString(character);
                        output.append("\\u");
                        for (int padding = hex.length(); padding < 4; padding++) {
                            output.append('0');
                        }
                        output.append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static void indent(StringBuilder output, int depth) {
        for (int index = 0; index < depth; index++) output.append("  ");
    }

    private static final class Parser {
        private final String input;
        private final String sourcePath;
        private int offset;
        private int nestingDepth;
        private int valueCount;

        private Parser(String input, String sourcePath) {
            this.input = input;
            this.sourcePath = sourcePath;
        }

        private Object parse() throws CanonicalMapFormatException {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (offset != input.length()) fail("unexpected trailing content");
            return value;
        }

        private Object parseValue() throws CanonicalMapFormatException {
            valueCount++;
            if (valueCount > CanonicalMapLimits.MAX_JSON_VALUES) {
                limit("JSON value count exceeds " + CanonicalMapLimits.MAX_JSON_VALUES);
            }
            if (offset >= input.length()) fail("unexpected end of input");
            char character = input.charAt(offset);
            if (character == '{') return parseObject();
            if (character == '[') return parseArray();
            if (character == '"') return parseString();
            if (character == 't') return parseLiteral("true", Boolean.TRUE);
            if (character == 'f') return parseLiteral("false", Boolean.FALSE);
            if (character == 'n') return parseLiteral("null", null);
            if (character == '-' || character >= '0' && character <= '9') {
                return parseNumber();
            }
            fail("unexpected character '" + character + "'");
            return null;
        }

        private Map<String, Object> parseObject() throws CanonicalMapFormatException {
            enterContainer();
            try {
                offset++;
                LinkedHashMap<String, Object> object =
                    new LinkedHashMap<String, Object>();
                skipWhitespace();
                if (consume('}')) return object;
                while (true) {
                    if (object.size() >= CanonicalMapLimits.MAX_JSON_CONTAINER_ENTRIES) {
                        limit("JSON object entry count exceeds "
                            + CanonicalMapLimits.MAX_JSON_CONTAINER_ENTRIES);
                    }
                    skipWhitespace();
                    if (offset >= input.length() || input.charAt(offset) != '"') {
                        fail("object key must be a string");
                    }
                    String key = parseString();
                    if (object.containsKey(key)) fail("duplicate object key '" + key + "'");
                    skipWhitespace();
                    require(':');
                    skipWhitespace();
                    object.put(key, parseValue());
                    skipWhitespace();
                    if (consume('}')) return object;
                    require(',');
                }
            } finally {
                nestingDepth--;
            }
        }

        private List<Object> parseArray() throws CanonicalMapFormatException {
            enterContainer();
            try {
                offset++;
                List<Object> values = new ArrayList<Object>();
                skipWhitespace();
                if (consume(']')) return values;
                while (true) {
                    if (values.size() >= CanonicalMapLimits.MAX_JSON_CONTAINER_ENTRIES) {
                        limit("JSON array entry count exceeds "
                            + CanonicalMapLimits.MAX_JSON_CONTAINER_ENTRIES);
                    }
                    skipWhitespace();
                    values.add(parseValue());
                    skipWhitespace();
                    if (consume(']')) return values;
                    require(',');
                }
            } finally {
                nestingDepth--;
            }
        }

        private String parseString() throws CanonicalMapFormatException {
            require('"');
            StringBuilder value = new StringBuilder();
            while (offset < input.length()) {
                char character = input.charAt(offset++);
                if (character == '"') {
                    validateSurrogates(value);
                    return value.toString();
                }
                if (character < 0x20) fail("unescaped control character in string");
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (offset >= input.length()) fail("truncated string escape");
                char escaped = input.charAt(offset++);
                switch (escaped) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(parseUnicodeEscape()); break;
                    default: fail("invalid string escape '\\" + escaped + "'");
                }
            }
            fail("unterminated string");
            return null;
        }

        private char parseUnicodeEscape() throws CanonicalMapFormatException {
            if (offset + 4 > input.length()) fail("truncated unicode escape");
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(offset++), 16);
                if (digit < 0) fail("invalid unicode escape");
                value = value << 4 | digit;
            }
            return (char) value;
        }

        private Object parseNumber() throws CanonicalMapFormatException {
            int start = offset;
            if (consume('-') && offset >= input.length()) fail("truncated number");
            if (consume('0')) {
                if (offset < input.length() && Character.isDigit(input.charAt(offset))) {
                    fail("leading zero in number");
                }
            } else {
                requireDigits();
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                requireDigits();
            }
            if (consume('e') || consume('E')) {
                decimal = true;
                consume('+');
                consume('-');
                requireDigits();
            }
            String number = input.substring(start, offset);
            try {
                if (decimal) {
                    double value = Double.parseDouble(number);
                    if (!Double.isFinite(value)) fail("number is not finite");
                    return Double.valueOf(value);
                }
                return Long.valueOf(number);
            } catch (NumberFormatException invalid) {
                fail("invalid number");
                return null;
            }
        }

        private Object parseLiteral(String literal, Object value)
            throws CanonicalMapFormatException {
            if (!input.regionMatches(offset, literal, 0, literal.length())) {
                fail("invalid literal");
            }
            offset += literal.length();
            return value;
        }

        private void requireDigits() throws CanonicalMapFormatException {
            int start = offset;
            while (offset < input.length() && Character.isDigit(input.charAt(offset))) offset++;
            if (start == offset) fail("expected digit");
        }

        private void require(char expected) throws CanonicalMapFormatException {
            if (!consume(expected)) fail("expected '" + expected + "'");
        }

        private boolean consume(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (offset < input.length()) {
                char character = input.charAt(offset);
                if (character != ' ' && character != '\n'
                    && character != '\r' && character != '\t') return;
                offset++;
            }
        }

        private void fail(String message) throws CanonicalMapFormatException {
            throw new CanonicalMapFormatException(
                "MALFORMED_JSON",
                sourcePath,
                message + " at character " + offset
            );
        }

        private void limit(String message) throws CanonicalMapFormatException {
            throw new CanonicalMapFormatException(
                "JSON_LIMIT_EXCEEDED",
                sourcePath,
                message + " at character " + offset
            );
        }

        private void enterContainer() throws CanonicalMapFormatException {
            if (nestingDepth >= CanonicalMapLimits.MAX_JSON_NESTING_DEPTH) {
                limit("JSON nesting depth exceeds "
                    + CanonicalMapLimits.MAX_JSON_NESTING_DEPTH);
            }
            nestingDepth++;
        }

        private void validateSurrogates(StringBuilder value)
            throws CanonicalMapFormatException {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                        fail("unpaired high surrogate in string");
                    }
                    index++;
                } else if (Character.isLowSurrogate(character)) {
                    fail("unpaired low surrogate in string");
                }
            }
        }
    }
}
