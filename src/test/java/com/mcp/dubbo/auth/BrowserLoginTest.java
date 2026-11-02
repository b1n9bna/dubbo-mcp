package com.mcp.dubbo.auth;

import com.mcp.dubbo.driver.DubboAdminDriver;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class BrowserLoginTest {
    private Path directory;
    private AuthSessionManager sessions;
    private ByteArrayOutputStream output;
    private FakeSession browser;

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("browser-login-test");
        sessions = new AuthSessionManager(directory); output = new ByteArrayOutputStream(); browser = new FakeSession();
    }
    @After public void cleanup() throws Exception {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    private BrowserLogin login(BrowserLogin.Verifier verifier, BrowserLogin.Opener opener, long timeout) {
        return new BrowserLogin(sessions, verifier, opener, new PrintStream(output), timeout);
    }
    @Test public void validSavedCredentialDoesNotLaunchBrowser() throws Exception {
        sessions.saveCredential("test", "saved-secret");
        login(value -> Assert.assertEquals("saved-secret", value), timeout -> { Assert.fail("Browser launched"); return browser; }, 1000).login("test");
        Assert.assertEquals("saved-secret", sessions.getCredential("test"));
        Assert.assertFalse(output.toString("UTF-8").contains("saved-secret"));
    }
    @Test public void rejectedSavedCredentialIsReplacedOnlyAfterVerifiedCapture() throws Exception {
        sessions.saveCredential("test", "old-secret"); browser.values.add("new-secret");
        login(value -> { if (value.equals("old-secret")) throw new DubboAdminDriver.AuthenticationException("rejected"); }, timeout -> browser, 2000).login("test");
        Assert.assertEquals("new-secret", sessions.getCredential("test")); Assert.assertTrue(browser.closed);
        Assert.assertFalse(output.toString("UTF-8").contains("secret"));
    }
    @Test public void rejectsIntermediateLoginStateAndWaitsForVerifiedSession() throws Exception {
        browser.values.add("intermediate-secret"); browser.values.add("valid-secret");
        login(value -> { if (value.startsWith("intermediate")) throw new DubboAdminDriver.AuthenticationException("rejected"); }, timeout -> browser, 2000).login("test");
        Assert.assertEquals("valid-secret", sessions.getCredential("test")); Assert.assertTrue(browser.closed);
    }
    @Test public void networkFailureDoesNotLaunchBrowserOrOverwriteSavedCredential() throws Exception {
        sessions.saveCredential("test", "old-secret");
        try {
            login(value -> { throw new IOException("private server details"); }, timeout -> { Assert.fail("Browser launched"); return browser; }, 1000).login("test");
            Assert.fail();
        } catch (IOException expected) { Assert.assertFalse(expected.getMessage().contains("private")); }
        Assert.assertEquals("old-secret", sessions.getCredential("test"));
    }
    @Test public void closingBrowserCancelsWithoutSaving() throws Exception {
        browser.alive = false;
        try { login(value -> {}, timeout -> browser, 1000).login("test"); Assert.fail(); }
        catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("取消")); }
        Assert.assertNull(sessions.getCredential("test")); Assert.assertTrue(browser.closed);
    }
    @Test public void closingBrowserDuringValidationCannotReplaceCredentials() throws Exception {
        browser.values.add("new-secret");
        try { login(value -> browser.alive = false, timeout -> browser, 1000).login("test"); Assert.fail(); }
        catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("取消")); }
        Assert.assertNull(sessions.getCredential("test")); Assert.assertTrue(browser.closed);
    }
    @Test public void waitingTimeoutPreservesOldCredentialAndClosesBrowser() throws Exception {
        sessions.saveCredential("test", "old-secret");
        try {
            login(value -> { throw new DubboAdminDriver.AuthenticationException("rejected"); }, timeout -> browser, 50).login("test"); Assert.fail();
        } catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("超时")); }
        Assert.assertEquals("old-secret", sessions.getCredential("test")); Assert.assertTrue(browser.closed);
    }
    @Test public void hungValidationHasDeadlineAndCannotWriteCredentialsLater() throws Exception {
        browser.values.add("new-secret"); AtomicBoolean interrupted = new AtomicBoolean();
        long started = System.nanoTime();
        try {
            login(value -> { try { Thread.sleep(5000); } catch (InterruptedException e) { interrupted.set(true); throw e; } }, timeout -> browser, 80).login("test"); Assert.fail();
        } catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("超时")); }
        Assert.assertTrue((System.nanoTime() - started) / 1000000 < 1500);
        Assert.assertNull(sessions.getCredential("test")); Assert.assertTrue(browser.closed);
    }

    private static class FakeSession implements BrowserLogin.Session {
        final Queue<String> values = new ArrayDeque<>(); boolean alive = true, closed;
        public String readCredential(long timeout) { return values.poll(); }
        public boolean isAlive() { return alive; }
        public void close() { closed = true; }
    }
}
