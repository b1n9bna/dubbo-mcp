package com.mcp.dubbo.auth;

import com.alibaba.fastjson2.*;
import okhttp3.HttpUrl;
import org.junit.*;

public class ChromeLoginSessionTest {
    @Test public void credentialsAreReadOnlyFromExactAdminOrigin() {
        HttpUrl admin = HttpUrl.parse("https://admin.example.com/dubbo-admin/");
        Assert.assertTrue(ChromeLoginSession.sameOrigin(admin, "https://admin.example.com:443/dubbo-admin/#/service"));
        for (String url : new String[]{"http://admin.example.com/", "https://admin.example.com:8443/", "https://admin.example.com.evil.test/", "https://sso.example.com/", "file:///tmp/admin", null})
            Assert.assertFalse(String.valueOf(url), ChromeLoginSession.sameOrigin(admin, url));
    }
    @Test public void devtoolsEndpointsCannotRedirectToOtherHostsOrPorts() {
        Assert.assertEquals("ws://127.0.0.1:1234/devtools/page/abc", ChromeLoginSession.localSocket("ws://localhost:1234/devtools/page/abc", 1234, "/devtools/page/"));
        for (String url : new String[]{"ws://evil.test:1234/devtools/page/abc", "ws://127.0.0.1:9999/devtools/page/abc", "ws://user@127.0.0.1:1234/devtools/page/abc", "ws://127.0.0.1:1234/devtools/browser/abc", "ws://127.0.0.1:1234/devtools/page/abc?secret=x", "wss://127.0.0.1:1234/devtools/page/abc", null})
            Assert.assertNull(ChromeLoginSession.localSocket(url, 1234, "/devtools/page/"));
    }
    @Test public void assemblesCookiePathsInSpecificityOrderAndKeepsAuthorization() {
        JSONArray cookies = JSON.parseArray("[{\"name\":\"session\",\"value\":\"root\",\"path\":\"/\"},{\"name\":\"session\",\"value\":\"admin\",\"path\":\"/dubbo-admin\"}]");
        JSONObject credential = JSON.parseObject(ChromeLoginSession.credential("test-token", cookies));
        Assert.assertEquals("session=admin; session=root", credential.getString("cookie"));
        Assert.assertEquals("test-token", credential.getString("authorization"));
        Assert.assertNull(ChromeLoginSession.credential("", new JSONArray()));
    }
    @Test public void refusesHeaderInjectionInCapturedValues() {
        try { ChromeLoginSession.credential("token\r\nInjected: secret", new JSONArray()); Assert.fail(); }
        catch (IllegalArgumentException expected) { }
        JSONArray cookies = JSON.parseArray("[{\"name\":\"session\",\"value\":\"bad\\r\\nvalue\",\"path\":\"/\"}]");
        try { ChromeLoginSession.credential("", cookies); Assert.fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void missingExplicitBrowserGivesActionableFailure() throws Exception {
        try { ChromeLoginSession.findBrowser("/definitely/missing/dubbo-login-browser"); Assert.fail(); }
        catch (java.io.IOException expected) { Assert.assertTrue(expected.getMessage().contains("不可用")); }
    }
    @Test public void closingLastPageCancelsEvenIfBrowserProcessIsAlive() throws Exception {
        ChromeLoginSession.PagePresence pages = new ChromeLoginSession.PagePresence();
        pages.observe(JSON.parseArray("[{\"type\":\"page\"}]"), 0);
        pages.observe(JSON.parseArray("[{\"type\":\"service_worker\"}]"), 1000000000L);
        try { pages.observe(new JSONArray(), 2000000000L); Assert.fail(); }
        catch (java.io.IOException expected) { Assert.assertTrue(expected.getMessage().contains("取消")); }
    }
    @Test public void briefPageReplacementDuringSsoDoesNotCancel() throws Exception {
        ChromeLoginSession.PagePresence pages = new ChromeLoginSession.PagePresence();
        pages.observe(new JSONArray(), 0);
        pages.observe(new JSONArray(), 5000000000L);
        pages.observe(JSON.parseArray("[{\"type\":\"page\"}]"), 6000000000L);
        pages.observe(new JSONArray(), 6100000000L);
        pages.observe(JSON.parseArray("[{\"type\":\"page\"}]"), 6200000000L);
        pages.observe(new JSONArray(), 6300000000L);
        pages.observe(new JSONArray(), 6400000000L);
    }
    @Test public void refusesSymlinkedProfileRootWithoutTouchingOtherBrowserData() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("isolated-browser-home");
        String previous = System.getProperty("user.home");
        try {
            java.nio.file.Path other = java.nio.file.Files.createDirectory(home.resolve("other-browser"));
            java.nio.file.Path root = java.nio.file.Files.createDirectory(home.resolve(".dubbo-mcp"));
            java.nio.file.Files.createSymbolicLink(root.resolve("browser"), other);
            System.setProperty("user.home", home.toString());
            try { ChromeLoginSession.open("test", "http://127.0.0.1:12345/", "/missing/browser", 100); Assert.fail(); }
            catch (java.io.IOException expected) { Assert.assertTrue(expected.getMessage().contains("符号链接")); }
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.list(other)) { Assert.assertEquals(0, paths.count()); }
        } finally {
            System.setProperty("user.home", previous);
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(home)) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) java.nio.file.Files.delete(path);
            }
        }
    }
}
