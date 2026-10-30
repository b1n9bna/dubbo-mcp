package com.mcp.dubbo.installer;

import com.alibaba.fastjson2.JSON;
import com.mcp.dubbo.McpLauncher;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class AgentAutoInstallerTest {
    private void verifyInstall(boolean blockCodex, boolean blockCursor, int expectedExit) throws Exception {
        Path home=Files.createTempDirectory("dubbo-install-test");
        try {
            if (blockCodex) Files.write(home.resolve(".codex"),new byte[]{1});
            if (blockCursor) Files.write(home.resolve(".cursor"),new byte[]{1});
            String classes=Paths.get(McpLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            String json=Paths.get(JSON.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            Path output=home.resolve("output.log");
            ProcessBuilder builder=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),
                    "-Duser.home="+home, "-cp",classes+File.pathSeparator+json,McpLauncher.class.getName(),"install")
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            // Windows Claude uses APPDATA, independently of Java's user.home.
            builder.environment().put("APPDATA",home.resolve("AppData/Roaming").toString());
            Process process=builder.start();
            try {
                Assert.assertTrue(process.waitFor(10,TimeUnit.SECONDS));
                String log=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);
                Assert.assertEquals(log,expectedExit,process.exitValue());
                Assert.assertEquals(log,expectedExit==0,log.contains("MCP configuration complete."));
                if(expectedExit!=0) Assert.assertTrue(log.contains("MCP configuration failed"));
                if(blockCodex) Assert.assertTrue(Files.isRegularFile(home.resolve(".codex")));
                else Assert.assertTrue(Files.isRegularFile(home.resolve(".codex/config.toml")));
                if(blockCursor) Assert.assertTrue(Files.isRegularFile(home.resolve(".cursor")));
                else Assert.assertTrue(Files.isRegularFile(home.resolve(".cursor/mcp.json")));
            } finally { process.destroyForcibly(); }
        } finally {
            try(Stream<Path> files=Files.walk(home)) {
                for(Path path:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
    @Test public void allFailuresReturnNonzero() throws Exception { verifyInstall(true,true,1); }
    @Test public void partialFailureReturnsNonzero() throws Exception { verifyInstall(false,true,1); }
    @Test public void absentOptionalClientDoesNotFailInstallation() throws Exception { verifyInstall(false,false,0); }
}
