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
        ['armeria', 'armeria-jetty', 'armeria-kafka', 'armeria-logback', 'armeria-retrofit2',
         'armeria-tomcat', 'armeria-zipkin', 'armeria-zookeeper'].each {
            compile group: 'com.linecorp.armeria', name: it, version: '\ |release|\ '
        }

        // Logging
        runtime group: 'ch.qos.logback', name: 'logback-classic', version: '\ |logback.version|\ '
        runtime group: 'org.slf4j', name: 'log4j-over-slf4j', version: '\ |slf4j.version|\ '
    }

    // Require Java 8 to build the project.
    tasks.withType(JavaCompile) {
        sourceCompatibility '1.8'
        targetCompatibility '1.8'
    }

You may not need all Armeria modules depending on your use case. Please remove unused ones.
