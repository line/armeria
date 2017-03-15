.. _`Apache ZooKeeper`: https://zookeeper.apache.org/
.. _`com.linecorp.armeria.client.zookeeper`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/package-summary.html
.. _`com.linecorp.armeria.server.zookeeper`: apidocs/index.html?com/linecorp/armeria/server/zookeeper/package-summary.html
.. _`Mode`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/ZooKeeperEndpointGroup.Mode.html

Automatic service discovery with ZooKeeper
===========================================
You can put your Endpoints information into a zNode in `Apache ZooKeeper`_ cluster, as a node value or as the child values of a node, like the following:

| as node value:
|         /myProductionEndpoints --> 192.168.1.10:8080,192.168.1.11:8080:100

| as node children:
|        /myProductionEndpoints
|                  /192.168.1.10_8080 --> 192.168.1.10:8080
|                  /192.168.1.11_8080 --> 192.168.1.11:8080:100

In the examples above, 192.168.1.10 and other IP strings are your servers' IP addresses, 8080 is the service port number, 100 is the server's weight. You can omit the server's weight as it is optional.

The above endpoint groups can be retrieved using the corresponding `Mode`_:

.. code-block:: java

     EndpointGroup myEndpointGroup = new ZooKeeperEndpointGroup(zkConnectionStr, zNodePath, sessionTimeout, mode);
     ..


After creating an EndpointGroup, you can register it, and then use it in client service setup:

.. code-block:: java

     import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;

     EndpointGroupRegistry.register("myProductionGroup", myEndpointGroup, WEIGHTED_ROUND_ROBIN);
     HelloService.Iface helloClient = Clients.newClient("tbinary+http://group:myProductionGroup/hello", HelloService.Iface.class);
       ...

For more information, please refer to the API documentation of the `com.linecorp.armeria.client.zookeeper`_ package.

Automatic service registration with ZooKeeper
=================================================

When you deploy your servers in a cloud environment, each server can automatically register itself as
a cluster member in a ZooKeeper cluster. Each server will represent itself as an
EPHEMERAL node, which means when your server stops or a network partition between your server and ZooKeeper
cluster occurs, the node of the server that became unreachable will be deleted automatically by ZooKeeper cluster. As a result, the clients that use a ZooKeeperEndpointGroup will be notified and they will update their endpoint list automatically so that they do not attempt to connect to the unreachable servers.

Register your server:

.. code-block:: java

   // This constructor will use server's default host name,
   // port and weight to register, use the other constructor to specify the desired.
   ServerListener listener = new ZooKeeperUpdatingListener(zkConnectionStr, zNode, sessionTimeout);
   server.addListener(listener);
   server.start();
   ...

For more information, please refer to the API documentation of the `com.linecorp.armeria.server.zookeeper`_ package.
