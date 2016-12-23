package com.linecorp.armeria.client.zookeeper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.ZooKeeper;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.zookeeper.DefaultNodeValueCodec;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZKConnector;
import com.linecorp.armeria.common.zookeeper.ZooKeeperException;

/**
 * A ZooKeeper-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a ZooKeeper zNode value and updates it when the value of the zNode changes. When a
 * ZooKeeper session expires, it will automatically reconnect to the ZooKeeper with exponential retry delay,
 * starting from 1 second up to 60 seconds.
 */
public class NodeValueEndpointGroup extends ZKConnector implements EndpointGroup {
    private final NodeValueCodec nodeValueCodec;
    private List<Endpoint> prevValue;

    /**
     * Creates a new instance for client.
     *  @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     */
    public NodeValueEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout) {
        this(zkConnectionStr, zNodePath, sessionTimeout, new DefaultNodeValueCodec());
    }

    /**
     * Creates a new instance for client.
     *  @param zkConnectionStr a connection string containing a comma separated list of {@code host:port} pairs,
     *                        each corresponding to a ZooKeeper server
     * @param zNodePath       a zNode path e.g. {@code "/groups/productionGroups"}
     * @param sessionTimeout  Zookeeper session timeout in milliseconds
     */
    public NodeValueEndpointGroup(String zkConnectionStr, String zNodePath, int sessionTimeout,
                                  NodeValueCodec nodeValueCodec) {
        super(zkConnectionStr, zNodePath, sessionTimeout);
        this.nodeValueCodec = nodeValueCodec;
        connect();
    }

    @Override
    protected void postConnected(ZooKeeper zooKeeper) {
        try {
            prevValue = new ArrayList<>(doGetEndpoints(zooKeeper, getzNodePath()));
        } catch (Exception e) {
            throw new ZooKeeperException(e);
        }
    }

    private Set<Endpoint> doGetEndpoints(ZooKeeper zooKeeper, String zNodePath) {
        try {
            //wait if node has not been create by server
            while (zooKeeper.exists(zNodePath, false) == null) {
                doWait();
            }
            resetRetryDelay();
            byte[] nodeData;
            //wait if node value has not been set by server
            if ((nodeData = zooKeeper.getData(zNodePath, true, null)) == null) {
                doWait();
            }
            resetRetryDelay();
            assert nodeData != null;
            return nodeValueCodec.decodeAll(new String(nodeData, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new ZooKeeperException(ex);
        }
    }

    @Override
    protected void nodeChange(ZooKeeper zooKeeper, String zNodePath) {
        try {
            Set<Endpoint> newValue = doGetEndpoints(zooKeeper, zNodePath);
            if (!newValue.equals(prevValue)) {
                prevValue = new ArrayList<>(newValue);
            }
        } catch (Exception e) {
            throw new ZooKeeperException(e);
        }
    }

    @Override
    public void close() {
        close(true);
    }

    @Override
    public List<Endpoint> endpoints() {
        return prevValue;
    }
}

