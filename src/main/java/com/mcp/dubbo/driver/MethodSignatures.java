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
            JSONObject method = methods.getJSONObject(i);
            JSONArray types = method.getJSONArray("parameterTypes");
            if (!name.equals(method.getString("name")) || types == null || types.size() != count) continue;
            String[] signature = types.toArray(new String[0]);
            matches.put(Arrays.toString(signature), signature);
        }
        if (matches.size() == 1) return matches.values().iterator().next();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous overloaded method " + name + "; specify paramTypes: " + matches.keySet());
        if (count == 0) return new String[0];
        throw new IllegalArgumentException("Cannot resolve parameter types for " + name + "; supply explicit paramTypes");
    }
}
