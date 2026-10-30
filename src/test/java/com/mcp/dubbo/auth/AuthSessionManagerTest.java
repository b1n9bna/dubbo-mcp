package com.mcp.dubbo.auth;
import org.junit.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public class AuthSessionManagerTest {
    @Test public void externalLoginIsVisibleAndFileIsPrivate() throws Exception {
        Path dir = Files.createTempDirectory("dubbo-session-test");
        AuthSessionManager reader = new AuthSessionManager(dir), writer = new AuthSessionManager(dir);
        try {
            writer.saveCredential("test", "old-token");
            Assert.assertEquals("old-token", reader.getCredential("test"));
            writer.saveCredential("test", "new-token");
            Assert.assertEquals("new-token", reader.getCredential("test"));
            if (Files.getFileStore(dir).supportsFileAttributeView("posix"))
                Assert.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("session-test.json"))));
            writer.clearCredential("test");
            Assert.assertNull(reader.getCredential("test"));
        } finally { Files.deleteIfExists(dir.resolve("session-test.json")); Files.delete(dir); }
    }
    @Test(expected=IllegalArgumentException.class) public void traversalRejected() {
        new AuthSessionManager(Paths.get("unused")).saveCredential("../../escape", "token");
    }
    @Test(expected=IllegalArgumentException.class) public void headerInjectionRejected() {
        AuthSessionManager.normalizeCredential("{\"authorization\":\"x\\r\\nX-Test: y\"}");
    }
    @Test public void failedSaveThrowsInsteadOfSuccess() throws Exception {
        Path file = Files.createTempFile("dubbo-session-test", ".tmp");
        try {
            try { new AuthSessionManager(file).saveCredential("test", "token"); Assert.fail(); }
            catch (IllegalStateException expected) { Assert.assertTrue(expected.getMessage().contains("Cannot save")); }
        } finally { Files.delete(file); }
    }
    @Test public void readsLongInputWithoutTruncation() throws Exception {
        String value = String.join("", java.util.Collections.nCopies(6000, "x"));
        Assert.assertEquals(value, LoginCommand.readInput(new java.io.ByteArrayInputStream((value+"\n").getBytes("UTF-8")), true));
    }
}
