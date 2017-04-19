.. _setup-gradle:

Setting up a project with Gradle
================================

You might want to use the following  ``build.gradle`` as a starting point if you set up a new project:

.. parsed-literal::

    apply plugin: 'java'
    apply plugin: 'idea'
    apply plugin: 'eclipse'

    repositories {
        mavenCentral()
    }

    configurations {
        javaAgent
    }

    dependencies {
        ['armeria',
         'armeria-grpc',
         'armeria-jetty',
         'armeria-kafka',
         'armeria-logback',
         'armeria-retrofit2',
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

    // Require Java 8 to build the project.
    tasks.withType(JavaCompile) {
        sourceCompatibility '1.8'
        targetCompatibility '1.8'
    }

.. include:: setup-common.rst
