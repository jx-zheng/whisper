package com.jxzheng.whisper.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the golden files. Numbers are doubles. */
public final class Json {

    private final String text;
    private int i;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.value();
        parser.skip();
        if (parser.i != text.length()) {
            throw new IllegalArgumentException("trailing json at " + parser.i);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    public static int integer(Object value) {
        return ((Number) value).intValue();
    }

    public static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    private Object value() {
        skip();
        char c = text.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> bool();
            case 'n' -> nul();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skip();
        if (peek('}')) {
            i++;
            return map;
        }
        while (true) {
            skip();
            String key = string();
            skip();
            expect(':');
            map.put(key, value());
            skip();
            if (peek('}')) {
                i++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        skip();
        if (peek(']')) {
            i++;
            return list;
        }
        while (true) {
            list.add(value());
            skip();
            if (peek(']')) {
                i++;
                return list;
            }
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (text.charAt(i) != '"') {
            char c = text.charAt(i++);
            if (c == '\\') {
                char e = text.charAt(i++);
                out.append(switch (e) {
                    case '"', '\\', '/' -> e;
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> throw new IllegalArgumentException("bad escape");
                });
            } else {
                out.append(c);
            }
        }
        i++;
        return out.toString();
    }

    private Object bool() {
        if (text.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("bad bool");
    }

    private Object nul() {
        if (!text.startsWith("null", i)) {
            throw new IllegalArgumentException("bad null");
        }
        i += 4;
        return null;
    }

    private Number number() {
        int start = i;
        if (text.charAt(i) == '-') {
            i++;
        }
        while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.' || text.charAt(i) == 'e' || text.charAt(i) == 'E' || text.charAt(i) == '+' || text.charAt(i) == '-')) {
            if ((text.charAt(i) == '+' || text.charAt(i) == '-') && i != start) {
                char prev = text.charAt(i - 1);
                if (prev != 'e' && prev != 'E') {
                    break;
                }
            }
            i++;
        }
        return Double.parseDouble(text.substring(start, i));
    }

    private void skip() {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
    }

    private boolean peek(char c) {
        return i < text.length() && text.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("expected " + c + " at " + i);
        }
        i++;
    }
}
