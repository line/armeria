.. _`Apache ZooKeeper`: https://zookeeper.apache.org/
.. _`com.linecorp.armeria.client.zookeeper`: apidocs/index.html?com/linecorp/armeria/client/zookeeper/package-summary.html
.. _`com.linecorp.armeria.server.zookeeper`: apidocs/index.html?com/linecorp/armeria/server/zookeeper/package-summary.html

Setting up a ZooKeeper EndpointGroup
====================================
You can put your Endpoints information into an zNode in `Apache ZooKeeper`_ Custer, as node value or as node's child value, like this:

| as node value:
|         /myProductionEndpoints -->192.168.1.10:8080:100,192.168.1.11:8080:100

| as node children:
|        /myProductionEndpoints
|                  /192.168.1.10_8080 --> 192.168.1.10:8080:100
|                  /192.168.1.11_8080 --> 192.168.1.11:8080:100

The above endpoint group can be retrieved using corresponding EndpointGroup implementations:

.. code-block:: java

     EndpointGroup nodeValueEndpoints=new  NodeValueEndpointGroup(zkConnectionStr, zNodePath, sessionTimeout);
     ..
     EndpointGroup nodeChildEndpoints=new  NodeChildEndpointGroup(zkConnectionStr, zNodePath, sessionTimeout);

After creating EndpointGroup, you can register it, and then use it in client service setup:

.. code-block:: java

     EndpointGroupRegistry.register("myProductionGroup", myEndpointGroup, WEIGHTED_ROUND_ROBIN);
     HelloService.Iface ipService = Clients.newClient("ttext+http://group:myProductionGroup/serverIp",
                                                         HelloService.Iface.class);
       ...

For more information, please refer to the API documentation of the `com.linecorp.armeria.client.zookeeper`_ package.

Registering your server into a ZooKeeper cluster
=================================================

when you deploy your server in a cloud environment, each server stand on a server node can register itself as
a cluster member in ZooKeeper cluster. Every server will represent itself as a zNode child, this node is an
EPHEMERAL node, which means when your server stopped or a network partition between your server and ZooKeeper
Cluster occurs, this node will be delete automatically by ZooKeeper Cluster, and thus will not be gained by
clients.

register your server:

.. code-block:: java

   ZooKeeperListener  listener = new ZooKeeperListener(zkConnectionStr, zNode, sessionTimeout,Endpoint.of("192.168.1.1",8080,500);
                      server.addListener(listener);
                      server.start();
                      ...

For more information, please refer to the API documentation of the `com.linecorp.armeria.server.zookeeper`_ package.
