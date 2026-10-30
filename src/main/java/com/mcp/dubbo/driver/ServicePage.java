package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Pagination metadata for service discovery. Partial or unverifiable pages must not drive routing. */
final class ServicePage {
    final String parameter;
    final int current;
    final boolean more;

    private ServicePage(String parameter, int current, boolean more) {
        this.parameter = parameter; this.current = current; this.more = more;
    }

    static ServicePage inspect(Object response, JSONArray rows, int collected) {
        JSONObject meta = metadata(response);
        if (meta == null) return new ServicePage(null, 0, false);
        String parameter = null;
        Integer current = null;
        int base = 1;
        for (String key : new String[]{"number", "current", "pageNum", "currPage"}) {
            if (meta.get(key) != null) {
                current = meta.getInteger(key);
                parameter = "number".equals(key) ? "page" : key;
                base = "number".equals(key) ? 0 : 1;
                break;
            }
        }
        Long pages = firstLong(meta, "totalPages", "pages", "totalPage");
        Long total = firstLong(meta, "totalElements", "total", "totalCount");
        if ((pages != null && pages < 0) || (total != null && total < 0))
            throw incomplete("invalid page count");
        Boolean moreFlag = meta.get("last") == null ? null : !meta.getBooleanValue("last");
        if (moreFlag == null) {
            for (String key : new String[]{"hasNext", "hasNextPage"})
                if (meta.get(key) != null) { moreFlag = meta.getBoolean(key); break; }
        }
        boolean remaining = (total != null && total > collected)
                || (pages != null && pages > 1 && (current == null || current - base + 1 < pages));
        if (Boolean.FALSE.equals(moreFlag) && remaining)
            throw incomplete("conflicting pagination metadata");
        boolean more = Boolean.TRUE.equals(moreFlag) || remaining;
        if (current != null && current < base) throw incomplete("invalid page index");
        if (more && (current == null || rows.isEmpty())) throw incomplete("missing cursor or empty intermediate page");
        if (collected == rows.size() && current != null && current != base)
            throw incomplete("discovery did not start at the first page");
        return new ServicePage(parameter, current == null ? 0 : current, more);
    }

    private static Long firstLong(JSONObject object, String... keys) {
        for (String key : keys) if (object.get(key) != null) return object.getLong(key);
        return null;
    }
    private static JSONObject metadata(Object response) {
        if (response instanceof String) {
            try { response = JSON.parse((String) response); }
            catch (Exception e) { throw incomplete("invalid encoded page"); }
        }
        if (!(response instanceof JSONObject)) return null;
        JSONObject obj = (JSONObject) response;
        for (String key : new String[]{"number", "current", "pageNum", "currPage", "totalPages", "pages",
                "totalPage", "totalElements", "total", "totalCount", "last", "hasNext", "hasNextPage"})
            if (obj.containsKey(key)) return obj;
        for (String key : new String[]{"data", "result", "response", "page", "body", "payload"}) {
            JSONObject found = metadata(obj.get(key));
            if (found != null) return found;
        }
        return null;
    }
    static IllegalStateException incomplete(String reason) {
        return new IllegalStateException("Incomplete Dubbo Admin service discovery: " + reason + "; refusing automatic service selection");
    }
}
