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
        // Mask mobile: 13812345678 -> 138****5678
        String masked = PHONE_PATTERN.matcher(jsonText).replaceAll("$1****$2");
        // Mask ID card: 110101199003072345 -> 110101********2345
        masked = ID_CARD_PATTERN.matcher(masked).replaceAll("$1********$2");
        return masked;
    }
}
