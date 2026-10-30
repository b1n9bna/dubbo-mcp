package com.mcp.dubbo.transport;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Reads LF/CRLF/CR-delimited UTF-8 frames without retaining an unbounded input line. */
final class BoundedMessageReader implements Closeable {
    static final class Frame {
        final String text;
        final boolean oversized;
        Frame(String text, boolean oversized) { this.text=text; this.oversized=oversized; }
    }
    private final InputStream input;
    private final int limit;
    private boolean skipLF;
    BoundedMessageReader(InputStream input, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Message limit must be positive");
        this.input=new BufferedInputStream(input); this.limit=limit;
    }
    Frame readFrame() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(Math.min(limit,8192));
        boolean oversized=false, seen=false;
        int b;
        while ((b=input.read()) != -1) {
            if (skipLF) { skipLF=false; if (b=='\n') continue; }
            seen=true;
            if (b=='\n' || b=='\r') { skipLF=b=='\r'; break; }
            if (!oversized) {
                if (bytes.size()==limit) { oversized=true; bytes.reset(); }
                else bytes.write(b);
            }
            // Once oversized, discard to the next delimiter so the following frame can recover.
        }
        if (!seen && b==-1) return null;
        return new Frame(oversized?null:new String(bytes.toByteArray(),StandardCharsets.UTF_8),oversized);
    }
    @Override public void close() throws IOException { input.close(); }
}
