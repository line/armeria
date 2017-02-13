package com.linecorp.armeria.common.zookeeper;

import java.util.Map;

/**
 * A ZooKeeper listener.
 */
public interface ZooKeeperListener {
    /**
     * notify when node's children value changed.
     * @param newChildrenValue new children values
     */
    void nodeChildChange(Map<String, String> newChildrenValue);

    /**
     * notify when a node value changed.
     * @param newValue new node value
     */
    void nodeValueChange(String newValue);

    /**
     * notify when ZooKeeper connection established.
     */
    void connected();
}
