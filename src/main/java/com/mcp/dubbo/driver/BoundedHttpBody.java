package com.mcp.dubbo.driver;

import okhttp3.ResponseBody;
import java.io.*;

/** Bounds decoded bytes, including transparent gzip responses with unknown content length. */
final class BoundedHttpBody {
    static String read(ResponseBody body, int limit) throws IOException {
        if (body == null) return "";
        if (limit <= 0) throw new IllegalArgumentException("HTTP response limit must be positive");
        if (body.contentLength() > limit) throw oversized(limit);
        try (InputStream input = body.byteStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk, 0, Math.min(chunk.length, limit - bytes.size() + 1))) != -1) {
                if (bytes.size() + read > limit) throw oversized(limit);
                bytes.write(chunk, 0, read);
            }
            // Preserve OkHttp's charset/BOM handling, now operating on a bounded in-memory body.
            try (ResponseBody buffered = ResponseBody.create(body.contentType(), bytes.toByteArray())) {
                return buffered.string();
            }
        }
    }
    private static IOException oversized(int limit) {
        return new IOException("Dubbo Admin response exceeds configured byte limit (" + limit
                + "); reduce page size or adjust env.<env>.admin_max_response_bytes");
    }
}
