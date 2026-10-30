package com.mcp.dubbo.router;

import com.mcp.dubbo.config.ConfigManager;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class ProfileRouterTest {

    @Test
    public void testConfigManagerEnvAttachmentsParsing() {
        ConfigManager cfg = ConfigManager.getInstance();
        cfg.getRawProperties().setProperty("env.testenv.attachment.tenantId", "tenant-100");
        cfg.getRawProperties().setProperty("env.testenv.attachment.region", "cn-hangzhou");
        try {
            Map<String, String> attachments = cfg.getEnvAttachments("testenv");
            Assert.assertNotNull(attachments);
            Assert.assertEquals("tenant-100", attachments.get("tenantId"));
            Assert.assertEquals("cn-hangzhou", attachments.get("region"));
        } finally {
            cfg.getRawProperties().remove("env.testenv.attachment.tenantId");
            cfg.getRawProperties().remove("env.testenv.attachment.region");
        }
    }

    @Test
    public void testResolveEnvAndDynamicConfiguredEnvs() {
        ConfigManager cfg = ConfigManager.getInstance();
        cfg.getRawProperties().setProperty("env.dynamic.registry", "zookeeper://127.0.0.1:2181");
        try {
            ProfileRouter router = ProfileRouter.getInstance();
            Assert.assertEquals("dynamic", router.resolveEnv("dynamic"));
            Assert.assertTrue(router.getConfiguredEnvs().contains("dynamic"));
        } finally {
            cfg.getRawProperties().remove("env.dynamic.registry");
        }
    }
}
