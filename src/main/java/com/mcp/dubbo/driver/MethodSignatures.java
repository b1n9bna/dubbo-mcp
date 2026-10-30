package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

final class MethodSignatures {
    static String[] resolve(JSONArray methods, String name, String[] requested, int count) {
        boolean explicit = requested != null && requested.length == count;
        if (explicit) for (String t : requested) if (t == null || t.trim().isEmpty()) explicit = false;
        if (explicit) return requested; // Explicit Map/Object types are legitimate overloads.
        if (requested != null && requested.length != 0 && requested.length != count)
            throw new IllegalArgumentException("paramTypes count must match args count");
        Map<String, String[]> matches = new LinkedHashMap<>();
        if (methods != null) for (int i = 0; i < methods.size(); i++) {
            if (!(methods.get(i) instanceof JSONObject)) continue;
            JSONObject method = methods.getJSONObject(i);
            if (method == null || !name.equals(method.getString("name"))) continue;
            JSONArray types = method.getJSONArray("parameterTypes");
            if (types == null || types.size() != count) continue;
            String[] signature = new String[types.size()];
            for (int j = 0; j < types.size(); j++) {
                Object p = types.get(j);
                if (p instanceof String) {
                    signature[j] = (String) p;
                } else if (p instanceof JSONObject && ((JSONObject) p).containsKey("type")) {
                    signature[j] = ((JSONObject) p).getString("type");
                } else if (p != null) {
                    signature[j] = p.toString();
                } else {
                    signature[j] = "java.lang.Object";
                }
            }
            matches.put(Arrays.toString(signature), signature);
        }
        if (matches.size() == 1) return matches.values().iterator().next();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous overloaded method " + name + "; specify paramTypes: " + matches.keySet());
        if (count == 0) return new String[0];
        throw new IllegalArgumentException("Cannot resolve parameter types for " + name + "; supply explicit paramTypes");
    }
}
