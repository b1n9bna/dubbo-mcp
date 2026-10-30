package com.mcp.dubbo.driver;

import okhttp3.*;
import okio.*;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class BoundedHttpBodyTest {
    @Test public void knownLengthIsRejectedBeforeReading() throws Exception {
        try(ResponseBody body=ResponseBody.create(MediaType.parse("application/json"),new byte[1025])) {
            try { BoundedHttpBody.read(body,1024); Assert.fail(); }
            catch(IOException expected) { Assert.assertTrue(expected.getMessage().contains("byte limit")); }
        }
    }
    @Test public void unknownLengthIsLimitedAndClosed() throws Exception {
        final boolean[] closed={false};
        InputStream stream=new ByteArrayInputStream(new byte[1025]) {
            public void close() throws IOException {closed[0]=true;super.close();}
        };
        BufferedSource source=Okio.buffer(Okio.source(stream));
        try(ResponseBody body=new ResponseBody() {
            public MediaType contentType(){return MediaType.parse("application/json");}
            public long contentLength(){return -1;}
            public BufferedSource source(){return source;}
        }) {
            try {BoundedHttpBody.read(body,1024);Assert.fail();}
            catch(IOException expected){Assert.assertTrue(closed[0]);}
        }
    }
    @Test public void exactBoundAndCharsetArePreserved() throws Exception {
        byte[] bytes="中文".getBytes(StandardCharsets.UTF_8);
        try(ResponseBody body=ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"),bytes)) {
            Assert.assertEquals("中文",BoundedHttpBody.read(body,bytes.length));
        }
    }
}
