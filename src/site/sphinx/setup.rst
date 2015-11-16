.. _`Maven Central Repository`: http://search.maven.org/

Setting up a project
====================

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
mandatory for HTTP/2, you need to modify your ``pom.xml`` a lot more than just adding a dependency. Note that
you don't need this if you are going to use HTTP/2 over a cleartext connection.

.. parsed-literal::

    <project>
      ...
      <properties>
        <jetty.alpn.version.latest>8.1.6.v20151105</jetty.alpn.version.latest>
        <jetty.alpn.version>${jetty.alpn.version.latest}</jetty.alpn.version>
        <jetty.alpn.path>${settings.localRepository}/org/mortbay/jetty/alpn/alpn-boot/${jetty.alpn.version}/alpn-boot-${jetty.alpn.version}.jar</jetty.alpn.path>
        <argLine.bootcp>-Xbootclasspath/p:${jetty.alpn.path}</argLine.bootcp>
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
                <id>get-alpn-boot</id>
                <phase>validate</phase>
                <goals>
                  <goal>get</goal>
                </goals>
                <configuration>
                  <groupId>org.mortbay.jetty.alpn</groupId>
                  <artifactId>alpn-boot</artifactId>
                  <version>${jetty.alpn.version}</version>
                </configuration>
              </execution>
            </executions>
          </plugin>

          <!-- Add the boot classpath to the VM options when running tests -->
          <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>2.19</version>
            <configuration>
              <argLine>${argLine.bootcp}</argLine>
            </configuration>
          </plugin>
          ...
        </plugins>
      </build>
      ...
      <profiles>
        <!--
          Profiles that assign proper Jetty npn-boot and alpn-boot version.
          See: http://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-versions
        -->
        <profile>
          <id>alpn-8u05</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_05</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.0.v20141016</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u11</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_11</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.0.v20141016</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u20</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_20</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.0.v20141016</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u25</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_25</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.2.v20141202</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u31</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_31</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.3.v20150130</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u40</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_40</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.3.v20150130</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u45</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_45</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.3.v20150130</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u51</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_51</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.4.v20150727</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u60</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_60</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.5.v20150921</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u65</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_65</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.6.v20151105</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u66</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_66</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.6.v20151105</jetty.alpn.version>
          </properties>
        </profile>
      </profiles>
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
        <tomcat.version>8.0.28</tomcat.version>
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
        <version>9</version>
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
        <tomcat.version>8.0.28</tomcat.version>
        <jetty.alpn.version.latest>8.1.6.v20151105</jetty.alpn.version.latest>
        <jetty.alpn.version>${jetty.alpn.version.latest}</jetty.alpn.version>
        <jetty.alpn.path>${settings.localRepository}/org/mortbay/jetty/alpn/alpn-boot/${jetty.alpn.version}/alpn-boot-${jetty.alpn.version}.jar</jetty.alpn.path>
        <argLine.bootcp>-Xbootclasspath/p:${jetty.alpn.path}</argLine.bootcp>
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
          <version>1.1.3</version>
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
                <id>get-alpn-boot</id>
                <phase>validate</phase>
                <goals>
                  <goal>get</goal>
                </goals>
                <configuration>
                  <groupId>org.mortbay.jetty.alpn</groupId>
                  <artifactId>alpn-boot</artifactId>
                  <version>${jetty.alpn.version}</version>
                </configuration>
              </execution>
            </executions>
          </plugin>

          <!-- Add the boot classpath to the VM options when running tests -->
          <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>2.19</version>
            <configuration>
              <argLine>${argLine.bootcp}</argLine>
            </configuration>
          </plugin>
        </plugins>
      </build>

      <profiles>
        <!--
          Profiles that assign proper Jetty npn-boot and alpn-boot version.
          See: http://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-versions
        -->
        <profile>
          <id>alpn-8u05</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_05</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.0.v20141016</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u11</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_11</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.0.v20141016</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u20</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_20</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.0.v20141016</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u25</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_25</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.2.v20141202</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u31</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_31</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.3.v20150130</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u40</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_40</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.3.v20150130</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u45</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_45</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.3.v20150130</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u51</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_51</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.4.v20150727</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u60</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_60</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.5.v20150921</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u65</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_65</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.6.v20151105</jetty.alpn.version>
          </properties>
        </profile>
        <profile>
          <id>alpn-8u66</id>
          <activation>
            <property>
              <name>java.version</name>
              <value>1.8.0_66</value>
            </property>
          </activation>
          <properties>
            <jetty.alpn.version>8.1.6.v20151105</jetty.alpn.version>
          </properties>
        </profile>
      </profiles>
    </project>
