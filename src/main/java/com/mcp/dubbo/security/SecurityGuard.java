package com.mcp.dubbo.security;

import com.mcp.dubbo.config.ConfigManager;

import java.util.regex.Pattern;

/**
 * Enterprise safety guard:
 * 1. Blocks write/mutation operations when the target environment has readonly=true.
 * 2. Masks sensitive data (mobile numbers, Chinese ID cards) if data_masking=true.
 */
public class SecurityGuard {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)([1-9]\\d{5})\\d{8}(\\w{4})(?!\\d)");

    public static void checkSafety(String env, String interfaceName, String method) throws SecurityException {
        if (method == null || method.trim().isEmpty()) {
            throw new SecurityException("⚠️ [SecurityBlocked] Method name cannot be null or empty.");
        }

        ConfigManager config = ConfigManager.getInstance();
        boolean isReadOnly = config.getBoolean("env." + env + ".readonly", false);

        if (isReadOnly) {
            String regex = config.getProperty("security.readonly.regex",
                    "^(get|query|find|select|list|count|check|is|search|fetch|read).*$");
            Pattern allowedPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

            if (!allowedPattern.matcher(method.trim()).matches()) {
                throw new SecurityException(String.format(
                        "⚠️ [SecurityBlocked] Operation '%s.%s' is blocked because environment '%s' is strictly READ-ONLY. " +
                        "Only read methods matching '%s' are permitted.",
                        interfaceName, method, env, regex
                ));
            }
        }
    }

    public static String maskSensitiveData(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            return jsonText;
        }
        Object parsed = com.alibaba.fastjson2.JSON.parse(jsonText);
        return com.alibaba.fastjson2.JSON.toJSONString(maskValue(parsed));
    }
    private static Object maskValue(Object value) {
        if (value instanceof String) {
            String masked = PHONE_PATTERN.matcher((String)value).replaceAll("$1****$2");
            return ID_CARD_PATTERN.matcher(masked).replaceAll("$1********$2");
        }
        if (value instanceof java.util.Map) {
            java.util.Map<String,Object> result = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?,?> entry : ((java.util.Map<?,?>)value).entrySet())
                result.put(String.valueOf(entry.getKey()), maskValue(entry.getValue()));
            return result;
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (Object entry : (java.util.List<?>)value) result.add(maskValue(entry));
            return result;
        }
        return value; // Never corrupt JSON numbers or change numeric identifiers into invalid JSON.
    }
}
