package com.linecorp.armeria.client.zookeeper;

/**
 * Value store type. As a node value or as a node's all Children.
 */
public enum StoreType {
    IN_CHILD_NODES, IN_NODE_VALUE
}
