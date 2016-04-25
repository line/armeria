.. _`Maven Central Repository`: http://search.maven.org/

Setting up a project with Maven
===============================

Armeria is distributed via `Maven Central Repository`_. Add the following dependency to your ``pom.xml``:

.. parsed-literal::

    <project>
      ...
      <dependencies>
        ...
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria</artifactId>
          <version>\ |release|\ </version>
        </dependency>
        ...
      </dependencies>
      ...
    </project>

Enabling HTTP/2 over TLS
------------------------
Due to the lack of out-of-the-box ALPN (Application Level Protocol Negotiation) support in Java 8, which is
mandatory for HTTP/2, you need to modify your ``pom.xml`` more than just adding a dependency. Note that
you don't need this if you are going to use HTTP/2 over a cleartext connection (h2c).

.. parsed-literal::

    <project>
      ...
      <properties>
        <jetty.alpnAgent.version>\ |jetty_alpnAgent_version|\ </jetty.alpnAgent.version>
        <jetty.alpnAgent.path>${settings.localRepository}/org/mortbay/jetty/alpn/jetty-alpn-agent/${jetty.alpnAgent.version}/jetty-alpn-agent-${jetty.alpnAgent.version}.jar</jetty.alpnAgent.path>
        <argLine.alpnAgent>-javaagent:${jetty.alpnAgent.path}</argLine.alpnAgent>
      </properties>
      ...
      <build>
        <plugins>
          ...
          <!-- Download the alpn-boot.jar in advance to add it to the boot classpath. -->
          <plugin>
            <artifactId>maven-dependency-plugin</artifactId>
            <version>2.10</version>
            <executions>
              <execution>
                <id>get-jetty-alpn-agent</id>
                <phase>validate</phase>
                <goals>
                  <goal>get</goal>
                </goals>
                <configuration>
                  <groupId>org.mortbay.jetty.alpn</groupId>
                  <artifactId>jetty-alpn-agent</artifactId>
                  <version>${jetty.alpnAgent.version}</version>
                </configuration>
              </execution>
            </executions>
          </plugin>

          <!-- Add the boot classpath to the VM options when running tests -->
          <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>2.19</version>
            <configuration>
              <argLine>${argLine.alpnAgent}</argLine>
            </configuration>
          </plugin>
          ...
        </plugins>
      </build>
      ...
    </project>

Enabling the embedded Tomcat
----------------------------
If you want to embed Tomcat into Armeria, you'll have to add the optional dependencies as well:

.. parsed-literal::

    <project>
      ...
      <properties>
        ...
        <slf4j.version>\ |slf4j_version|\ </slf4j.version>
        <tomcat.version>\ |tomcat_version|\ </tomcat.version>
        ...
      </properties>
      ...
      <dependencies>
        ...
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-core</artifactId>
          <version>${tomcat.version}</version>
        </dependency>
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-jasper</artifactId>
          <version>${tomcat.version}</version>
        </dependency>
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-el</artifactId>
          <version>${tomcat.version}</version>
        </dependency>
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-logging-log4j</artifactId>
          <version>${tomcat.version}</version>
        </dependency>
        <dependency>
          <groupId>org.slf4j</groupId>
          <artifactId>log4j-over-slf4j</artifactId>
          <version>${slf4j.version}</version>
        </dependency>
        ...
      </dependencies>
      ...
    </project>

An example POM
--------------
You might want to use the following  ``pom.xml`` as a template if you are starting a new project:

.. parsed-literal::

    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">

      <modelVersion>4.0.0</modelVersion>
      <parent>
        <groupId>org.sonatype.oss</groupId>
        <artifactId>oss-parent</artifactId>
        <version>\ |oss_parent_version|\ </version>
      </parent>

      <groupId>com.example</groupId>
      <artifactId>myproject</artifactId>
      <version>0.1.0.Final-SNAPSHOT</version>
      <packaging>jar</packaging>
      <name>My Armeria project</name>

      <properties>
        <!-- Project options -->
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>

        <!-- Compiler options -->
        <maven.compiler.compilerVersion>1.8</maven.compiler.compilerVersion>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <maven.compiler.fork>true</maven.compiler.fork>
        <maven.compiler.debug>true</maven.compiler.debug>
        <maven.compiler.optimize>true</maven.compiler.optimize>
        <maven.compiler.showDeprecation>true</maven.compiler.showDeprecation>
        <maven.compiler.showWarnings>true</maven.compiler.showWarnings>

        <!-- Dependency versions -->
        <armeria.version>\ |release|\ </armeria.version>
        <logback.version>\ |logback_version|\ </logback.version>
        <slf4j.version>\ |slf4j_version|\ </slf4j.version>
        <tomcat.version>\ |tomcat_version|\ </tomcat.version>
        <jetty.alpnAgent.version>\ |jetty_alpnAgent_version|\ </jetty.alpnAgent.version>
        <jetty.alpnAgent.path>${settings.localRepository}/org/mortbay/jetty/alpn/jetty-alpn-agent/${jetty.alpnAgent.version}/jetty-alpn-agent-${jetty.alpnAgent.version}.jar</jetty.alpnAgent.path>
        <argLine.alpnAgent>-javaagent:${jetty.alpnAgent.path}=${jetty.alpnAgent.option}</argLine.alpnAgent>
      </properties>

      <dependencies>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria</artifactId>
          <version>${armeria.version}</version>
        </dependency>

        <!-- Embedded Tomcat -->
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-core</artifactId>
          <version>${tomcat.version}</version>
        </dependency>
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-jasper</artifactId>
          <version>${tomcat.version}</version>
          <scope>runtime</scope>
        </dependency>
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-el</artifactId>
          <version>${tomcat.version}</version>
          <scope>runtime</scope>
        </dependency>
        <dependency>
          <groupId>org.apache.tomcat.embed</groupId>
          <artifactId>tomcat-embed-logging-log4j</artifactId>
          <version>${tomcat.version}</version>
          <scope>runtime</scope>
        </dependency>
        <dependency>
          <groupId>org.slf4j</groupId>
          <artifactId>log4j-over-slf4j</artifactId>
          <version>${slf4j.version}</version>
          <scope>runtime</scope>
        </dependency>

        <!-- Logback -->
        <dependency>
          <groupId>ch.qos.logback</groupId>
          <artifactId>logback-classic</artifactId>
          <version>${logback.version}</version>
          <scope>runtime</scope>
        </dependency>
      </dependencies>

      <build>
        <plugins>
          <!-- Download the alpn-boot.jar in advance to add it to the boot classpath. -->
          <plugin>
            <artifactId>maven-dependency-plugin</artifactId>
            <version>2.10</version>
            <executions>
              <execution>
                <id>get-jetty-alpn-agent</id>
                <phase>validate</phase>
                <goals>
                  <goal>get</goal>
                </goals>
                <configuration>
                  <groupId>org.mortbay.jetty.alpn</groupId>
                  <artifactId>jetty-alpn-agent</artifactId>
                  <version>${jetty.alpnAgent.version}</version>
                </configuration>
              </execution>
            </executions>
          </plugin>

          <!-- Add the boot classpath to the VM options when running tests -->
          <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>2.19</version>
            <configuration>
              <argLine>${argLine.alpnAgent}</argLine>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </project>
