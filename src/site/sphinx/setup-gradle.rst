Setting up a project with Gradle
================================

You might want to use the following  ``build.gradle`` as a starting point if you set up a new project:

.. parsed-literal::

    apply plugin: 'java'
    apply plugin: 'idea'
    apply plugin: 'eclipse'

    repositories {
        mavenLocal()
        mavenCentral()
    }

    configurations {
        javaAgent
    }

    dependencies {
        compile group: 'com.linecorp.armeria', name: 'armeria', version: '\ |release|\ '

        // Logging
        runtime group: 'ch.qos.logback', name: 'logback-classic', version: '\ |logback_version|\ '

        // Embedded Tomcat
        [ "core", "jasper", "el", "logging-log4j" ].each { module ->
            runtime group: 'org.apache.tomcat.embed', name: "tomcat-embed-$module", version: '\ |tomcat_version|\ '
        }
        runtime group: 'org.slf4j', name: 'log4j-over-slf4j', version: '\ |slf4j_version|\ '

        // JVM agent to enable TLS ALPN extension
        javaAgent group: 'org.mortbay.jetty.alpn', name: 'jetty-alpn-agent', version: '\ |jetty_alpnAgent_version|\ '
    }

    // Require Java 8 to build the project.
    tasks.withType(JavaCompile) {
        sourceCompatibility '1.8'
        targetCompatibility '1.8'
    }

    // Copy the JVM agent that enables TLS ALPN extension to the build directory.
    task copyJavaAgents(type: Copy) {
        from configurations.javaAgent
        into "$buildDir/javaAgents"
        rename { String fileName ->
            fileName.replaceFirst("-[0-9]+\\.[0-9]+\\.[0-9]+\\.[^\\.]+\\.jar", ".jar")
        }
    }

    // Load the JVM agent that enables TLS ALPN extension for all Java executions.
    tasks.withType(JavaForkOptions) {
        dependsOn 'copyJavaAgents'
        // If using spring-boot plugin, you can use the 'agent' property:
        // See: http://jdpgrailsdev.github.io/blog/2014/04/08/spring_boot_gradle_newrelic.html
        jvmArgs "-javaagent:$buildDir/javaAgents/jetty-alpn-agent.jar"
    }
