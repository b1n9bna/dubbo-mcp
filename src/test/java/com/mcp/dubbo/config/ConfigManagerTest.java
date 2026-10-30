package com.mcp.dubbo.config;

import org.junit.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

public class ConfigManagerTest {
    @Test public void typedGettersUseSystemPropertiesAndKeepFilePrecedence() {
        ConfigManager config = ConfigManager.getInstance();
        String flag = "regression.flag", limit = "regression.limit";
        System.setProperty(flag, " true "); System.setProperty(limit, "12345");
        try {
            Assert.assertTrue(config.getBoolean(flag, false));
            Assert.assertEquals(12345, config.getInt(limit, 0));
            config.getRawProperties().setProperty(flag, "false");
            config.getRawProperties().setProperty(limit, "777");
            Assert.assertFalse(config.getBoolean(flag, true));
            Assert.assertEquals(777, config.getInt(limit, 0));
            config.getRawProperties().setProperty(limit, "invalid");
            Assert.assertEquals(42, config.getInt(limit, 42));
        } finally {
            config.getRawProperties().remove(flag); config.getRawProperties().remove(limit);
            System.clearProperty(flag); System.clearProperty(limit);
        }
    }

    @Test public void typedGettersUseEnvironmentVariables() throws Exception {
        String main = ConfigManagerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        String classes = ConfigManager.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        ProcessBuilder builder = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", main + File.pathSeparator + classes, EnvironmentProbe.class.getName());
        builder.environment().put("REGRESSION_ENV_FLAG", "true");
        builder.environment().put("DUBBO_MCP_REGRESSION_ENV_LIMIT", "31415");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            Assert.assertTrue("Configuration probe timed out", process.waitFor(10, TimeUnit.SECONDS));
            Assert.assertEquals("Environment fallback probe failed", 0, process.exitValue());
        } finally { process.destroyForcibly(); }
    }

    @Test public void invalidBooleanValuesAreRejected() {
        ConfigManager config = ConfigManager.getInstance();
        String key = "env.boolean_regression.readonly";
        for (String value : new String[]{"tru", "1", "", "true # comment"}) {
            config.getRawProperties().setProperty(key, value);
            try { config.getBoolean(key, false); Assert.fail("Invalid boolean accepted"); }
            catch (IllegalArgumentException expected) { Assert.assertTrue(expected.getMessage().contains(key)); }
            finally { config.getRawProperties().remove(key); }
        }
    }

    @Test public void malformedOrNonFileConfigurationAbortsStartup() throws Exception {
        for (boolean directory : new boolean[]{false, true}) {
            java.nio.file.Path home = java.nio.file.Files.createTempDirectory("dubbo-bad-config");
            try {
                java.nio.file.Path file = home.resolve(".dubbo-mcp/config.properties");
                java.nio.file.Files.createDirectories(file.getParent());
                if (directory) java.nio.file.Files.createDirectory(file);
                else java.nio.file.Files.write(file, "active.env=prod\nbroken=\\uZZZZ\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String classes = ConfigManager.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
                String tests = ConfigManagerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
                java.nio.file.Path log = home.resolve("log");
                Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                        "-Duser.home=" + home, "-cp", tests + File.pathSeparator + classes, LoadProbe.class.getName())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    Assert.assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                    Assert.assertNotEquals(0, process.exitValue());
                    String output = new String(java.nio.file.Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
                    Assert.assertFalse(output.contains("active=test"));
                } finally { process.destroyForcibly(); }
            } finally {
                try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(home)) {
                    for (java.nio.file.Path path : (Iterable<java.nio.file.Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                        java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }
    @Test public void utf8BomAndUnicodeEscapesArePreserved() throws Exception {
        java.nio.file.Path file=java.nio.file.Files.createTempFile("dubbo-utf8", ".properties");
        try {
            String text="\uFEFFactive.env=prod\nenv.audit.attachment.operator=张三\nescaped=\\u5F20\\u4E09\n";
            java.nio.file.Files.write(file,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ConfigManager config=new ConfigManager(file);
            Assert.assertEquals("prod",config.getActiveEnv());
            Assert.assertEquals("张三",config.getEnvAttachments("audit").get("operator"));
            Assert.assertEquals("张三",config.getProperty("escaped"));
        } finally { java.nio.file.Files.delete(file); }
    }
    @Test public void invalidUtf8AbortsConfigurationLoad() throws Exception {
        java.nio.file.Path file=java.nio.file.Files.createTempFile("dubbo-invalid-utf8", ".properties");
        try {
            java.nio.file.Files.write(file,new byte[]{(byte)0xc3,(byte)0x28});
            try {new ConfigManager(file);Assert.fail();}
            catch(IllegalStateException expected) {Assert.assertTrue(expected.getMessage().contains("startup aborted"));}
        } finally { java.nio.file.Files.delete(file); }
    }
    public static class LoadProbe {
        public static void main(String[] args) { System.out.println("active=" + ConfigManager.getInstance().getActiveEnv()); }
    }

    public static class EnvironmentProbe {
        public static void main(String[] args) {
            ConfigManager config = ConfigManager.getInstance();
            if (!config.getBoolean("regression.env.flag", false) || config.getInt("regression.env.limit", 0) != 31415)
                throw new IllegalStateException("Typed getters ignored environment values");
        }
    }
}
