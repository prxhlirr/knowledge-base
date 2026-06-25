package com.boyang.search.utils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Keeps transport encoding out of searchable document text.
 */
public final class DocumentTextNormalizer {
    private static final Pattern ENCODED_CHINESE = Pattern.compile("(?i)(?:%E[0-9A-F](?:%[0-9A-F]{2}){2})");
    private static final Pattern MOSTLY_PERCENT_BYTES = Pattern.compile("(?i)^(?:%[0-9A-F]{2}|[._\\-+\\s])+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[\\\\/:*?\"<>|]");

    private DocumentTextNormalizer() {
    }

    public static String normalizeFilename(String value) {
        String text = decodeWhenUseful(trimToEmpty(value), true);
        text = normalizeUnicode(text);
        text = UNSAFE_FILENAME.matcher(text).replaceAll("_");
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    public static String normalizeMetadataText(String value) {
        String text = decodeWhenUseful(trimToEmpty(value), true);
        text = normalizeUnicode(text);
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    public static String normalizeContentIfFullyEncoded(String value) {
        String text = trimToEmpty(value);
        if (!looksFullyEncoded(text)) {
            return text;
        }
        return decodeWhenUseful(text, true);
    }

    public static boolean hasEncodedChinese(String value) {
        return value != null && ENCODED_CHINESE.matcher(value).find();
    }

    private static String decodeWhenUseful(String value, boolean allowSecondPass) {
        if (!hasEncodedChinese(value) && !value.contains("%25")) {
            return value;
        }
        String best = value;
        String current = value;
        int passes = allowSecondPass ? 2 : 1;
        for (int i = 0; i < passes; i++) {
            String decoded = percentDecodePathSegment(current);
            if (decoded.equals(current)) {
                break;
            }
            if (isBetterDecodedText(best, decoded)) {
                best = decoded;
            }
            current = decoded;
        }
        return best;
    }

    private static boolean looksFullyEncoded(String value) {
        return value != null
                && !value.isEmpty()
                && hasEncodedChinese(value)
                && MOSTLY_PERCENT_BYTES.matcher(value).matches();
    }

    private static boolean isBetterDecodedText(String original, String decoded) {
        return decoded != null
                && !decoded.equals(original)
                && cjkCount(decoded) > cjkCount(original);
    }

    private static int cjkCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= '\u4e00' && ch <= '\u9fff') || (ch >= '\u3400' && ch <= '\u4dbf')) {
                count++;
            }
        }
        return count;
    }

    private static String percentDecodePathSegment(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                bytes.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
                continue;
            }
            flushBytes(bytes, out);
            out.append(ch);
        }
        flushBytes(bytes, out);
        return out.toString();
    }

    private static void flushBytes(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) {
            return;
        }
        out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        bytes.reset();
    }

    private static boolean isHex(char ch) {
        return (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F');
    }

    private static String normalizeUnicode(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
