package com.mcp.dubbo.auth.spi;

/**
 * Strategy interface for authentication against enterprise management consoles (e.g. dubbo-admin).
 */
public interface AuthStrategy {

    String getName();

    /**
     * Gets a valid authentication credential (Cookie or Bearer token).
     * If not available or expired, triggers the interactive login flow.
     */
    String getValidCredential(String env, String adminUrl) throws Exception;

    /**
     * Performs a lightweight keep-alive heartbeat to prevent session expiration.
     */
    boolean ping(String adminUrl, String credential);
}
