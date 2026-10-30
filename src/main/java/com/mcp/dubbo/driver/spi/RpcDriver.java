package com.mcp.dubbo.driver.spi;

import java.util.List;
import java.util.Map;

/**
 * Core SPI for RPC Execution Drivers.
 * Implementations can be native Dubbo (direct ZooKeeper/Nacos),
 * Dubbo-Admin HTTP bridge, or generic HTTP/RESTful services.
 */
public interface RpcDriver {

    /**
     * Unique driver identifier (e.g. "zookeeper", "nacos", "dubbo-admin", "direct")
     */
    String getType();

    /**
     * Executes a generic RPC invocation.
     *
     * @param interfaceName Full Java interface class name (e.g. com.corp.OrderService)
     * @param method        Method name (e.g. getOrderDetail)
     * @param paramTypes    Array of parameter type class names (e.g. ["java.lang.Long"])
     * @param args          Array of invocation arguments
     * @return Invocation result object / Map / Primitive
     */
    Object invoke(String interfaceName, String method, String[] paramTypes, Object[] args) throws Exception;

    /**
     * Searches or lists registered services matching the given keyword.
     */
    List<String> listServices(String keyword);

    /**
     * Retrieves method signatures and parameter schemas for the given interface.
     */
    Map<String, Object> getServiceMetadata(String interfaceName);
}
