#!/bin/bash -x
# todo  pom.xml in root

filename="pom.xml"
dirlist=`find . -name build.gradle | perl -p -e  's#\.\/##'| perl -p  -e 's#/build.gradle##'`
dirlist2=`find . -name build.gradle.kts | perl -p -e  's#\.\/##'| perl -p  -e 's#/build.gradle.kts##'`

>$filename

# generate head
cat >>$filename<<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.linecorp.armeria</groupId>
  <artifactId>examples</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

EOF

# generate module
echo "  <modules>" >>$filename
for tmp1 in `echo $dirlist | tr " " "\n"`
do
  echo "    <module>"$tmp1"</module>" >>$filename
done
for tmp2 in `echo $dirlist2 | tr " " "\n"`
do
  echo "    <module>"$tmp2"</module>" >>$filename
done
echo "  </modules>" >>$filename

# generate  properties, dependencyManagement, dependencies and build
cat >>$filename<<'EOF'

  <properties>
    <maven.compiler.target>17</maven.compiler.target>
    <maven.compiler.source>17</maven.compiler.source>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    <!-- dependency  version-->
    <dep.slf4j.version>1.7.36</dep.slf4j.version>
    <dep.grpc.version>1.61.0</dep.grpc.version>
    <dep.protobuf.version>3.25.1</dep.protobuf.version>
    <dep.armeria.version>1.27.0</dep.armeria.version>
    <dep.resilience4j.version>2.2.0</dep.resilience4j.version>
    <dep.micrometer.version>1.12.2</dep.micrometer.version>
    <dep.netty.version>4.1.106.Final</dep.netty.version>
    <dep.reactor-core.version>3.6.2</dep.reactor-core.version>
    <dep.jsr305.version>3.0.2</dep.jsr305.version>
    <dep.javax.annotation-api.version>1.3.2</dep.javax.annotation-api.version>
    <dep.junit.version>4.13.2</dep.junit.version>
    <dep.assertj-core.version>3.25.2</dep.assertj-core.version>
    <dep.junit5.version>5.10.1</dep.junit5.version>
    <dep.awaitility.version>4.2.0</dep.awaitility.version>
    <dep.json-unit-fluent.version>2.38.0</dep.json-unit-fluent.version>
    <!-- todo add more -->

    <!-- plugin -->
    <plug.protobuf-maven-plugin.version>0.6.1</plug.protobuf-maven-plugin.version>

  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-bom</artifactId>
        <version>${dep.resilience4j.version}</version>
        <scope>import</scope>
        <type>pom</type>
      </dependency>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${dep.junit5.version}</version>
        <scope>import</scope>
        <type>pom</type>
      </dependency>
      <dependency>
        <groupId>com.linecorp.armeria</groupId>
        <artifactId>armeria-bom</artifactId>
        <version>${dep.armeria.version}</version>
        <scope>import</scope>
        <type>pom</type>
      </dependency>
      <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-bom</artifactId>
        <version>${dep.netty.version}</version>
        <scope>import</scope>
        <type>pom</type>
      </dependency>
      <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-bom</artifactId>
        <version>${dep.micrometer.version}</version>
        <scope>import</scope>
        <type>pom</type>
      </dependency>

      <dependency>
        <groupId>com.google.code.findbugs</groupId>
        <artifactId>jsr305</artifactId>
        <version>${dep.jsr305.version}</version>
      </dependency>
      <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <version>${dep.reactor-core.version}</version>
      </dependency>
      <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-simple</artifactId>
        <version>${dep.slf4j.version}</version>
      </dependency>
      <dependency>
        <groupId>javax.annotation</groupId>
        <artifactId>javax.annotation-api</artifactId>
        <version>${dep.javax.annotation-api.version}</version>
        <scope>provided</scope>
      </dependency>
      <!--- test dependency -->
      <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <version>${dep.junit.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${dep.assertj-core.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <version>${dep.awaitility.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>net.javacrumbs.json-unit</groupId>
        <artifactId>json-unit-fluent</artifactId>
        <version>${dep.json-unit-fluent.version}</version>
        <scope>test</scope>
      </dependency>

      <!-- todo add more -->
    </dependencies>
  </dependencyManagement>


  <dependencies>
    <!--- test dependency -->
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-params</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.platform</groupId>
      <artifactId>junit-platform-commons</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.platform</groupId>
      <artifactId>junit-platform-launcher</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.vintage</groupId>
      <artifactId>junit-vintage-engine</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.google.code.findbugs</groupId>
      <artifactId>jsr305</artifactId>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.xolstice.maven.plugins</groupId>
        <artifactId>protobuf-maven-plugin</artifactId>
        <version>${plug.protobuf-maven-plugin.version}</version>
        <configuration>
          <protocArtifact>com.google.protobuf:protoc:${dep.protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
          <pluginId>grpc-java</pluginId>
          <pluginArtifact>io.grpc:protoc-gen-grpc-java:${dep.grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
        </configuration>
        <executions>
          <execution>
            <id>gen-grpc</id>
            <goals>
              <goal>compile</goal>
              <goal>compile-custom</goal>
            </goals>
            <phase>generate-resources</phase>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.0.0-M7</version>
      </plugin>
    </plugins>
    <extensions>
      <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>1.6.2</version>
      </extension>
    </extensions>
  </build>

</project>
EOF