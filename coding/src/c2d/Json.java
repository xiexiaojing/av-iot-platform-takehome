package c2d;

import java.util.LinkedHashMap;
import java.util.Map;

/** 极简 JSON 读写：只支持扁平对象（本示例够用，避免引入第三方依赖）。 */
public final class Json {

    private Json() {
    }

    /** 转义并加引号。 */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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
        return sb.toString();
    }

    /** 构造扁平 JSON 对象，值可以是 String / Number / Boolean / null。 */
    public static String obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String s) {
                sb.append(quote(s));
            } else if (v instanceof Boolean b) {
                sb.append(b.booleanValue());
            } else if (v instanceof Number n) {
                sb.append(n);
            } else {
                sb.append(quote(String.valueOf(v)));
            }
        }
        return sb.append('}').toString();
    }

    /** 解析扁平 JSON 对象；解析失败时返回空 map（调用方需按缺省值处理）。 */
    public static Map<String, String> parseFlat(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null) {
            return out;
        }
        int i = 0;
        int n = body.length();
        while (i < n) {
            int keyStart = body.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = body.indexOf('"', keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String key = body.substring(keyStart + 1, keyEnd);
            int colon = body.indexOf(':', keyEnd);
            if (colon < 0) {
                break;
            }
            i = colon + 1;
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            String value;
            if (body.charAt(i) == '"') {
                int end = body.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                value = body.substring(i + 1, end);
                i = end + 1;
            } else {
                int end = i;
                while (end < n && body.charAt(end) != ',' && body.charAt(end) != '}') {
                    end++;
                }
                value = body.substring(i, end).trim();
                i = end;
            }
            out.put(key, value);
        }
        return out;
    }
}
