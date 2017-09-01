.. _`Maven Central Repository`: https://search.maven.org/

.. _setup-maven:

Setting up a project with Maven
===============================

Armeria is distributed via `Maven Central Repository`_. Add the following dependency to your ``pom.xml``:

.. parsed-literal::

    <project>
      ...
      <properties>
        <!-- Compiler options -->
        <maven.compiler.compilerVersion>1.8</maven.compiler.compilerVersion>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>

        <!-- Dependency versions -->
        <armeria.version>\ |release|\ </armeria.version>
      </properties>

      <dependencies>
        ...
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-grpc</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-jetty</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-kafka</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-logback</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-retrofit2</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-thrift</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-tomcat</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-zipkin</artifactId>
          <version>${armeria.version}</version>
        </dependency>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-zookeeper</artifactId>
          <version>${armeria.version}</version>
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
        ...
      </dependencies>
      ...
    </project>

.. include:: setup-common.rst
