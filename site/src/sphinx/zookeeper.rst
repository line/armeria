.. _`Apache ZooKeeper`: https://zookeeper.apache.org/
.. _`com.linecorp.armeria.client.zookeeper`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/package-summary.html
.. _`com.linecorp.armeria.server.zookeeper`: apidocs/index.html?com/linecorp/armeria/server/zookeeper/package-summary.html
.. _`StoreType`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/StoreType.html

Automatic service discovery with ZooKeeper
===========================================
You can put your Endpoints information into an zNode in `Apache ZooKeeper`_ Cluster, as a node value or as the child value of a node, like the following:

| as node value:
|         /myProductionEndpoints --> 192.168.1.10:8080:100,192.168.1.11:8080:100

| as node children:
|        /myProductionEndpoints
|                  /192.168.1.10_8080 --> 192.168.1.10:8080:100
|                  /192.168.1.11_8080 --> 192.168.1.11:8080:100

As it is very simple, 192.168.1.10 and others IP strings are your servers' IP address, 8080 is the service port number, 100 is the server's weight.You can omit the server's weight as it is optional.

The above endpoint groups can be retrieved using the corresponding `StoreType`_:

.. code-block:: java

     EndpointGroup myEndpointGroup = new ZooKeeperEndpointGroup(zkConnectionStr, zNodePath, sessionTimeout,storeType);
     ..


After creating an EndpointGroup, you can register it, and then use it in client service setup:

.. code-block:: java

     EndpointGroupRegistry.register("myProductionGroup", myEndpointGroup, WEIGHTED_ROUND_ROBIN);
     HelloService.Iface helloClient = Clients.newClient("tbinary+http://group:myProductionGroup/hello",HelloService.Iface.class);
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

   ZooKeeperListener listener = new ZooKeeperListener(zkConnectionStr, zNode, sessionTimeout, Endpoint.of("192.168.1.1",8080,500);
   server.addListener(listener);
   server.start();
   ...

For more information, please refer to the API documentation of the `com.linecorp.armeria.server.zookeeper`_ package.
