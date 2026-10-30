package com.mcp.dubbo.installer;
import org.junit.*;
public class CodexTomlInstallerTest {
    @Test public void preservesUserConfigurationAndAvoidsDuplicate() {
        String config="[mcp_servers.dubbo]\ncommand = \"/custom/java\"\nargs = [\"custom\"]\n";
        Assert.assertEquals(config,CodexTomlInstaller.configure(config,"/new.jar"));
    }
    @Test public void migratesLegacyHeadingAndEscapesWindowsPath() {
        Assert.assertEquals("[mcp_servers.dubbo]\ncommand=\"java\"\n",CodexTomlInstaller.configure("[mcp.servers.dubbo]\ncommand=\"java\"\n","/new.jar"));
        Assert.assertTrue(CodexTomlInstaller.configure("", "C:\\Users\\test\\dubbo.jar").contains("C:\\\\Users"));
    }
}
