.. _`an EPHEMERAL node`: https://zookeeper.apache.org/doc/r3.4.10/zookeeperOver.html#Nodes+and+ephemeral+nodes
.. _`Apache ZooKeeper`: https://zookeeper.apache.org/
.. _CuratorFramework: https://curator.apache.org/apidocs/org/apache/curator/framework/CuratorFramework.html

.. _advanced-zookeeper:

Service discovery with ZooKeeper
================================
You can put the list of available :apiplural:`Endpoint` into a zNode in `Apache ZooKeeper`_ cluster
as a node tree, like the following:

.. code-block:: yaml

    # Note: Only child node values are used. i.e. Child node names are ignored.
    - /myProductionEndpoints
      - /192.168.1.10_8080: 192.168.1.10:8080
      - /192.168.1.11_8080: 192.168.1.11:8080:100

In the examples above, ``192.168.1.10`` and other IP strings are your servers' IP addresses, ``8080`` is a
service port number and ``100`` is a weight value. You can omit a weight value as it is optional.

Create a :api:`ZooKeeperEndpointGroup` to retrieve this information:

.. code-block:: java

    import com.linecorp.armeria.client.endpoint.EndpointGroup;
    import com.linecorp.armeria.client.zookeeper.ZooKeeperEndpointGroup;

    EndpointGroup myEndpointGroup = new ZooKeeperEndpointGroup(
            /* zkConnectionStr */ "myZooKeeperHost:2181",
            /* zNodePath       */ "/myProductionEndpoints",
            /* sessionTimeout  */ 10000);


And then register it to the :api:`EndpointGroupRegistry`, and specify it in a client URI:

.. code-block:: java

    import static com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
    import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;

    EndpointGroupRegistry.register("myProductionGroup", myEndpointGroup, WEIGHTED_ROUND_ROBIN);
    // Specify 'group:<groupName>' in the authority part of a client URI.
    HelloService.Iface helloClient = Clients.newClient(
            "tbinary+http://group:myProductionGroup/hello", HelloService.Iface.class);

For more information, please refer to the API documentation of the
:api:`com.linecorp.armeria.client.zookeeper` package.

Automatic service registration
------------------------------

Use :api:`ZooKeeperUpdatingListenerBuilder` to register your server to a ZooKeeper cluster:

.. code-block:: java

    import com.linecorp.armeria.server.ServerListener;
    import com.linecorp.armeria.server.zookeeper.ZooKeeperUpdatingListenerBuilder;

    ZookeeperUpdatingListener listener =
            new ZooKeeperUpdatingListenerBuilder("myZooKeeperHost:2181", "/myProductionEndpoints")
                    .sessionTimeout(10000)
                    .build();
    server.addListener(listener);
    server.start();
    ...

You can also use an existing `CuratorFramework`_ instance instead of ZooKeeper connection string:

.. code-block:: java

    import com.linecorp.armeria.server.ServerListener;
    import com.linecorp.armeria.server.zookeeper.ZooKeeperUpdatingListenerBuilder;
    import org.apache.curator.framework.CuratorFramework;

    CuratorFramework client = ...
    ZookeeperUpdatingListener listener =
            new ZooKeeperUpdatingListenerBuilder(client, "/myProductionEndpoints")
                    .nodeValueCodec(NodeValueCodec.DEFAULT)
                    .build();
    server.addListener(listener);
    server.start();
    ...

When your server starts up, :api:`ZooKeeperUpdatingListener` will register the server automatically to the
specified zNode as a member of the cluster. Each server will represent itself as `an EPHEMERAL node`_, which
means when a server stops or a network partition between your server and ZooKeeper cluster occurs, the node of
the server that became unreachable will be deleted automatically by ZooKeeper. As a result, the clients that
use a :api:`ZooKeeperEndpointGroup` will be notified and they will update their endpoint list automatically
so that they do not attempt to connect to the unreachable servers.

For more information, please refer to the API documentation of the
:api:`com.linecorp.armeria.server.zookeeper` package.
