.. _`an EPHEMERAL node`: https://zookeeper.apache.org/doc/r3.4.10/zookeeperOver.html#Nodes+and+ephemeral+nodes
.. _`Apache ZooKeeper`: https://zookeeper.apache.org/
.. _`com.linecorp.armeria.client.zookeeper`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/package-summary.html
.. _`com.linecorp.armeria.server.zookeeper`: apidocs/index.html?com/linecorp/armeria/server/zookeeper/package-summary.html
.. _`Endpoints`: apidocs/index.html?com/linecorp/armeria/client/Endpoint.html
.. _`EndpointGroup`: apidocs/index.html?com/linecorp/armeria/client/EndpointGroup.html
.. _`EndpointGroupRegistry`: apidocs/index.html?com/linecorp/armeria/client/EndpointGroupRegistry.html
.. _`ZooKeeperEndpointGroup`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/ZooKeeperEndpointGroup.html
.. _`ZooKeeperEndpointGroup.Mode`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/ZooKeeperEndpointGroup.Mode.html
.. _`ZooKeeperUpdatingListener`: apidocs/index.html?com/linecorp/armeria/server/zookeeper/ZooKeeperUpdatingListener.html

.. _advanced-zookeeper:

Service discovery with ZooKeeper
================================
You can put the list of available `Endpoints`_ into a zNode in `Apache ZooKeeper`_ cluster, as a node tree or
as a node value, like the following:

.. code-block:: yaml

    # (Recommended) When stored as a node tree:
    # Note: Only child node values are used. i.e. Child node names are ignored.
    - /myProductionEndpoints
      - /192.168.1.10_8080: 192.168.1.10:8080
      - /192.168.1.11_8080: 192.168.1.11:8080:100

    # When stored as a node value:
    - /myProductionEndpoints: 192.168.1.10:8080,192.168.1.11:8080:100


In the examples above, ``192.168.1.10`` and other IP strings are your servers' IP addresses, ``8080`` is a
service port number and ``100`` is a weight value. You can omit a weight value as it is optional.

Create a `ZooKeeperEndpointGroup`_ to retrieve this information:

.. code-block:: java

    import com.linecorp.armeria.client.endpoint.EndpointGroup;
    import com.linecorp.armeria.client.zookeeper.ZooKeeperEndpointGroup;
    import com.linecorp.armeria.client.zookeeper.ZooKeeperEndpointGroup.Mode;

    EndpointGroup myEndpointGroup = new ZooKeeperEndpointGroup(
            /* zkConnectionStr */ "myZooKeeperHost:2181",
            /* zNodePath       */ "/myProductionEndpoints",
            /* sessionTimeout  */ 10000,
            /* mode            */ Mode.IN_CHILD_NODES /* or Mode.IN_NODE_VALUE */);


And then register it to the `EndpointGroupRegistry`_, and specify it in a client URI:

.. code-block:: java

    import static com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
    import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;

    EndpointGroupRegistry.register("myProductionGroup", myEndpointGroup, WEIGHTED_ROUND_ROBIN);
    // Specify 'group:<groupName>' in the authority part of a client URI.
    HelloService.Iface helloClient = Clients.newClient(
            "tbinary+http://group:myProductionGroup/hello", HelloService.Iface.class);

For more information, please refer to the API documentation of the `com.linecorp.armeria.client.zookeeper`_ package.

Automatic service registration
------------------------------

Use `ZooKeeperUpdatingListener`_ to register your server to a ZooKeeper cluster:

.. code-block:: java

    import com.linecorp.armeria.server.ServerListener;
    import com.linecorp.armeria.server.zookeeper.ZooKeeperUpdatingListener;

    // This constructor will use server's default host name, port and weight.
    // Use the other constructors to override the defaults.
    ServerListener listener = new ZooKeeperUpdatingListener(
            /* zkConnectionStr */ "myZooKeeperHost:2181",
            /* zNode           */ "/myProductionEndpoints",
            /* sessionTimeout  */ 10000);
    server.addListener(listener);
    server.start();
    ...

When your server starts up, `ZooKeeperUpdatingListener`_ will register the server automatically to the
specified zNode as a member of the cluster. Each server will represent itself as `an EPHEMERAL node`_, which
means when a server stops or a network partition between your server and ZooKeeper cluster occurs, the node of
the server that became unreachable will be deleted automatically by ZooKeeper. As a result, the clients that
use a `ZooKeeperEndpointGroup`_ will be notified and they will update their endpoint list automatically so that
they do not attempt to connect to the unreachable servers.

For more information, please refer to the API documentation of the `com.linecorp.armeria.server.zookeeper`_ package.
