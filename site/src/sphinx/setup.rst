.. _setup:

Setting up a project
====================

All Armeria JARs are available in `Maven Central Repository <https://search.maven.org/search?q=g:com.linecorp.armeria%20-shaded>`_
under group ID ``com.linecorp.armeria`` so that you can fetch them easily using your favorite build tool.
Add the Armeria artifacts that provide the desired functionality to your project dependencies. The following is
the list of major Armeria artifacts which might interest you:

+-----------------------------------------------+-------------------------------------------------------------------+
| Artifact ID                                   | Description                                                       |
+===============================================+===================================================================+
| ``armeria``                                   | The core library.                                                 |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-grpc``                              | gRPC client and server support.                                   |
|                                               | See :ref:`server-grpc` and :ref:`client-grpc`.                    |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-jetty``                             | Embedded Jetty Servlet container. See :ref:`server-servlet`.      |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-kafka``                             | Enables sending access logs to Kafka                              |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-logback``                           | Provides Logback ``Appender`` implementation that adds            |
|                                               | request information. See :ref:`advanced-logging`.                 |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-retrofit2``                         | Allows using Armeria instead of OkHttp as transport layer         |
|                                               | when using Retrofit. See :ref:`client-retrofit`.                  |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-rxjava``                            | RxJava plugin                                                     |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-saml``                              | SAML support                                                      |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-spring-boot-autoconfigure``         | Spring Boot integration                                           |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-spring-boot-webflux-autoconfigure`` | Spring Boot WebFlux integration.                                  |
|                                               | See :ref:`advanced-spring-webflux-integration`.                   |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-thrift``                            | Thrift client and server support.                                 |
|                                               | See :ref:`server-thrift` and :ref:`client-thrift`.                |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-tomcat``                            | Embedded Tomcat Servlet container. See :ref:`server-servlet`.     |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-zipkin``                            | Zipkin distributed tracing support. See :ref:`advanced-zipkin`.   |
+-----------------------------------------------+-------------------------------------------------------------------+
| ``armeria-zookeeper``                         | ZooKeeper-based service discovery. See :ref:`advanced-zookeeper`. |
+-----------------------------------------------+-------------------------------------------------------------------+

Setting up with Gradle
----------------------

You might want to use the following ``build.gradle`` as a starting point when you set up a new project:

.. parsed-literal::
    :class: highlight-groovy

    apply plugin: 'java'
    apply plugin: 'idea'
    apply plugin: 'eclipse'

    repositories {
        mavenCentral()
    }

    dependencies {
        // Adjust the list as you need.
        ['armeria',
         'armeria-grpc',
         'armeria-jetty',
         'armeria-kafka',
         'armeria-logback',
         'armeria-retrofit2',
         'armeria-rxjava',
         'armeria-saml',
         'armeria-thrift',
         'armeria-tomcat',
         'armeria-zipkin',
         'armeria-zookeeper'].each {
            compile "com.linecorp.armeria:${it}:\ |release|\ "
        }

        // Logging
        runtime 'ch.qos.logback:logback-classic:\ |ch.qos.logback:logback-classic:version|\ '
        runtime 'org.slf4j:log4j-over-slf4j:\ |org.slf4j:log4j-over-slf4j:version|\ '
    }

Setting up with Maven
---------------------

You might want to use the following ``pom.xml`` as a starting point when you set up a new project:

.. parsed-literal::
    :class: highlight-xml

    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      <modelVersion>4.0.0</modelVersion>

      <groupId>com.example</groupId>
      <artifactId>myproject</artifactId>
      <version>1.0-SNAPSHOT</version>
      <packaging>jar</packaging>

      <name>myproject</name>
      <url>https://example.com/</url>

      <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
      </properties>

      <dependencies>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-grpc</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-jetty</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-kafka</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-logback</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-retrofit2</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-rxjava</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-saml</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-thrift</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-tomcat</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-zipkin</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-zookeeper</artifactId>
          <version>\ |release|\ </version>
        </dependency>

        <!-- Logging -->
        <dependency>
          <groupId>ch.qos.logback</groupId>
          <artifactId>logback-classic</artifactId>
          <version>\ |ch.qos.logback:logback-classic:version|\ </version>
          <scope>runtime</scope>
        </dependency>
        <dependency>
          <groupId>org.slf4j</groupId>
          <artifactId>log4j-over-slf4j</artifactId>
          <version>\ |org.slf4j:log4j-over-slf4j:version|\ </version>
          <scope>runtime</scope>
        </dependency>
      </dependencies>
    </project>

Using Maven BOM for simpler dependency management
-------------------------------------------------

You can import ``com.linecorp.armeria:armeria-bom`` into your build rather than specifying Armeria versions in
more than once place. See `this article <https://www.baeldung.com/spring-maven-bom>`_ to learn more about what
Maven BOM is and how to use it.
