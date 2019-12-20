# Armeria examples

- `annotated-http-service`
  - Learn how to write an HTTP service using annotations.
  - See [Annotated services](https://line.github.io/armeria/server-annotated-service.html).

- `grpc`
  - Learn how to write a gRPC service with Armeria gRPC module.
  - See [Running a gRPC service](https://line.github.io/armeria/server-grpc.html) and
    [Calling a gRPC service](https://line.github.io/armeria/client-grpc.html).

- `grpc-service-reactor`
  - Learn how to write a gRPC service with Armeria gRPC module,
    [`reactive-grpc`](https://github.com/salesforce/reactive-grpc) and
    [Project Reactor](https://projectreactor.io/) libraries for asynchronous processing
    with non-blocking back pressure.
  - See [Running a gRPC service](https://line.github.io/armeria/server-grpc.html) and
    [Calling a gRPC service](https://line.github.io/armeria/client-grpc.html).

- `proxy-server`
  - Learn how to make a proxy server which leverages client side load balancing.
  - See [Client-side load balancing](https://line.github.io/armeria/client-service-discovery.html)

- `saml-service-provider`
  - Learn how to authenticate users using SAML.
  - See [SAML Single Sign-on](https://line.github.io/armeria/advanced-saml.html).

- `server-sent-events`
  - Learn how to serve Server-Sent Events.
  - See [Serving Server-Sent Events](https://line.github.io/armeria/server-sse.html).
  
- `spring-boot-minimal`
  - Learn how to use Armeria with the minimal Spring Boot dependencies.

- `spring-boot-minimal-kotlin`
  - Learn how to use Armeria with the minimal Spring Boot dependencies written in Kotlin.

- `spring-boot-tomcat`
  - Learn how to make Armeria serve your Spring Boot web application.

- `spring-boot-webflux`
  - Learn how to make Armeria serve your Spring Boot reactive web application.
  - See [Using Armeria with Spring WebFlux](https://line.github.io/armeria/advanced-spring-webflux-integration.html).

- `static-files`
  - Learn how to serve static files.
  - See [Serving static files](https://line.github.io/armeria/server-http-file.html).

## Configure `-parameters` javac option 

You can omit the value of `@Param` if you compiled your code with `-parameters` javac option.
Please refer to [Configure `-parameters` javac option](http://line.github.io/armeria/setup.html#configure-parameters-javac-option) for more information.

## How to run

- Use `run` or `bootRun` task to run an example from Gradle.
- See [Open an existing Gradle project](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start) to import an example into IntelliJ IDEA.
- See [Configure `-parameters` javac option](http://line.github.io/armeria/setup.html#configure-parameters-javac-option) to configure IntelliJ IDEA.
- See [Build and run the application](https://www.jetbrains.com/help/idea/creating-and-running-your-first-java-application.html#run_app) to run an example from IntelliJ IDEA.

## License

All files under this directory (`examples`) belong to
[the public domain](https://en.wikipedia.org/wiki/Public_domain).
Please feel free to copy-and-paste and start your awesome project with Armeria!
