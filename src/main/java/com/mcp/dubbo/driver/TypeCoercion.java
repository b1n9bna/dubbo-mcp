package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSON;

import java.math.BigDecimal;

/**
 * Enterprise Type Coercion Engine:
 * Intelligently bridges loose LLM types (e.g. numeric IDs passed as strings or vice versa)
 * to strict Java Dubbo parameter types without precision loss.
 */
public final class TypeCoercion {

    private TypeCoercion() {
    }

    public static Object coerce(Object arg, String targetType) {
        if (arg == null || targetType == null || targetType.trim().isEmpty()) {
            return arg;
        }

        String type = targetType.trim();

        // 1. Target is String: convert number or boolean to String
        if ("java.lang.String".equals(type) || "String".equals(type)) {
            if (arg instanceof Number || arg instanceof Boolean) {
                return arg.toString();
            }
            return arg;
        }

        // 2. Target is Long / long: parse numeric string or convert Number
        if ("java.lang.Long".equals(type) || "long".equals(type)) {
            if (arg instanceof String) {
                String str = ((String) arg).trim();
                if (str.matches("^-?\\d+$")) {
                    try {
                        return Long.parseLong(str);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (arg instanceof Number) {
                return ((Number) arg).longValue();
            }
            return arg;
        }

        // 3. Target is Integer / int: parse numeric string or convert Number
        if ("java.lang.Integer".equals(type) || "int".equals(type)) {
            if (arg instanceof String) {
                String str = ((String) arg).trim();
                if (str.matches("^-?\\d+$")) {
                    try {
                        return Integer.parseInt(str);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (arg instanceof Number) {
                return ((Number) arg).intValue();
            }
            return arg;
        }

        // 4. Target is BigDecimal: parse from string or number
        if ("java.math.BigDecimal".equals(type) || "BigDecimal".equals(type)) {
            if (arg instanceof String) {
                String str = ((String) arg).trim();
                if (!str.isEmpty()) {
                    try {
                        return new BigDecimal(str);
                    } catch (Exception ignored) {
                    }
                }
            } else if (arg instanceof Number) {
                return new BigDecimal(arg.toString());
            }
            return arg;
        }

        // 5. Target is Double / double
        if ("java.lang.Double".equals(type) || "double".equals(type)) {
            if (arg instanceof String) {
                String str = ((String) arg).trim();
                try {
                    return Double.parseDouble(str);
                } catch (NumberFormatException ignored) {
                }
            } else if (arg instanceof Number) {
                return ((Number) arg).doubleValue();
            }
            return arg;
        }

        // 6. Target is Boolean / boolean
        if ("java.lang.Boolean".equals(type) || "boolean".equals(type)) {
            if (arg instanceof String) {
                String str = ((String) arg).trim();
                if ("true".equalsIgnoreCase(str)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(str)) return Boolean.FALSE;
            }
            return arg;
        }

        // 7. Target is JavaBean / DTO / Map / List: unpack JSON string
        if (arg instanceof String) {
            String str = ((String) arg).trim();
            if (str.startsWith("{") || str.startsWith("[")) {
                try {
                    return JSON.parse(str);
                } catch (Exception ignored) {
                }
            }
        }

        return arg;
    }
}
