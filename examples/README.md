# Armeria examples

- `annotated-http-service` <a href="https://gitpod.io/#project=annotated-http-service/https://github.com/line/armeria-examples/tree/master/annotated-http-service/src/main/java/example/armeria/server/annotated/Main.java">
                             <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                           </a> 
  - Learn how to write an HTTP service using annotations.
  - See [Annotated services](https://armeria.dev/docs/server-annotated-service).

- `annotated-http-service-kotlin` <a href="https://gitpod.io/#project=annotated-http-service-kotlin/https://github.com/line/armeria-examples/tree/master/annotated-http-service-kotlin/src/main/kotlin/example/armeria/server/annotated/kotlin/Main.kt">
                                    <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                                  </a>
  - Learn how to write an HTTP service using annotations with Kotlin Coroutines.
  - See [Kotlin coroutines support](https://armeria.dev/docs/server-annotated-service#kotlin-coroutines-support).

- `context-propagation`
  - Learn how to propagate Armeria's `RequestContext` for use in scenarios like tracing.
  - [`dagger`](https://dagger.dev/producers) provides an example using the Dagger asynchronous framework for
  automatic propagation.
  - `manual` provides an example manually propagating the context with Java's standard `CompletableFuture`.
  - [`reactor`](https://github.com/reactor/reactor-core/tree/3.3.x) provides an example using the Reactor
  asynchronous framework for automatic propagation.
  - [`rxjava`](https://github.com/ReactiveX/RxJava/tree/3.x) provides an example using the RxJava3 asynchronous
  framework for automatic propagation.

- `grpc` <a href="https://gitpod.io/#project=grpc/https://github.com/line/armeria-examples/tree/master/grpc/src/main/java/example/armeria/grpc/Main.java">
           <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
         </a> 
  - Learn how to write a gRPC service with Armeria gRPC module.
  - See [Running a gRPC service](https://armeria.dev/docs/server-grpc) and
    [Calling a gRPC service](https://armeria.dev/docs/client-grpc).
    
- `grpc-kotlin`
  - Learn how to write a gRPC service with Armeria gRPC module (Kotlin).
  - See [Running a gRPC service](https://armeria.dev/docs/server-grpc) and
    [Calling a gRPC service](https://armeria.dev/docs/client-grpc).

- `grpc-reactor` <a href="https://gitpod.io/#project=grpc-reactor/https://github.com/line/armeria-examples/tree/master/grpc-reactor/src/main/java/example/armeria/grpc/reactor/Main.java">
                   <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                 </a> 
  - Learn how to write a gRPC service with Armeria gRPC module,
    [`reactive-grpc`](https://github.com/salesforce/reactive-grpc) and
    [Project Reactor](https://projectreactor.io/) libraries for asynchronous processing
    with non-blocking back pressure.
  - See [Running a gRPC service](https://armeria.dev/docs/server-grpc) and
    [Calling a gRPC service](https://armeria.dev/docs/client-grpc).

- `proxy-server` <a href="https://gitpod.io/#project=proxy-server/https://github.com/line/armeria-examples/tree/master/proxy-server/src/main/java/example/armeria/proxy/Main.java">
                   <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                 </a> 
  - Learn how to make a proxy server which leverages client side load balancing.
  - See [Client-side load balancing](https://armeria.dev/docs/client-service-discovery)

- `saml-service-provider` <a href="https://gitpod.io/#project=sam-service-provider/https://github.com/line/armeria-examples/tree/master/saml-service-provider/src/main/java/example/armeria/server/saml/sp/Main.java">
                            <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                          </a> 
  - Learn how to authenticate users using SAML.
  - See [SAML Single Sign-on](https://armeria.dev/docs/advanced-saml).

- `server-sent-events` <a href="https://gitpod.io/#project=server-sent-events/https://github.com/line/armeria-examples/tree/master/server-sent-events/src/main/java/example/armeria/server/sse/Main.java">
                         <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                       </a> 
  - Learn how to serve Server-Sent Events.
  - See [Serving Server-Sent Events](https://armeria.dev/docs/server-sse).
  
- `spring-boot-minimal` <a href="https://gitpod.io/#project=spring-boot-minimal/https://github.com/line/armeria-examples/tree/master/spring-boot-minimal/src/main/java/example/springframework/boot/minimal/Main.java">
                          <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                        </a> 
  - Learn how to use Armeria with the minimal Spring Boot dependencies.

- `spring-boot-minimal-kotlin`
  - Learn how to use Armeria with the minimal Spring Boot dependencies (Kotlin).

- `spring-boot-tomcat` <a href="https://gitpod.io/#project=spring-boot-tomcat/https://github.com/line/armeria-examples/tree/master/spring-boot-tomcat/src/main/java/example/springframework/boot/tomcat/Main.java">
                         <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                       </a> 
  - Learn how to make Armeria serve your Spring Boot web application.

- `spring-boot-webflux` <a href="https://gitpod.io/#project=spring-boot-webflux/https://github.com/line/armeria-examples/tree/master/spring-boot-webflux/src/main/java/example/springframework/boot/webflux/Main.java">
                          <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                        </a> 
  - Learn how to make Armeria serve your Spring Boot reactive web application.
  - See [Using Armeria with Spring WebFlux](https://armeria.dev/docs/advanced-spring-webflux-integration).

- `dropwizard` <a href="https://gitpod.io/#project=dropwizard/https://github.com/line/armeria-examples/tree/master/dropwizard/src/main/java/example/dropwizard/DropwizardArmeriaApplication.java">
                 <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
               </a> 
  - Learn how to make Armeria serve your Dropwizard web application.
  - See [Using Armeria with Dropwizard](https://armeria.dev/docs/advanced-dropwizard-integration).

- `static-files` <a href="https://gitpod.io/#project=static-files/https://github.com/line/armeria-examples/tree/master/static-files/src/main/java/example/armeria/server/files/Main.java">
                   <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
                 </a> 
  - Learn how to serve static files.
  - See [Serving static files](https://armeria.dev/docs/server-http-file).
  
- `thrift` <a href="https://gitpod.io/#project=grpc/https://github.com/line/armeria-examples/tree/master/thrift/src/main/java/example/armeria/thrift/Main.java">
             <img align="absmiddle" height="20" src="https://gitpod.io/button/open-in-gitpod.svg"/>
           </a> 
  - Learn how to write a Thrift service with Armeria Thrift module.
  - See [Running a Thrift service](https://armeria.dev/docs/server-thrift) and
    [Calling a Thrift service](https://armeria.dev/docs/client-thrift).
  - Install Thrift compiler locally before generating Thrift services.
    - Use `brew install thrift` for macOS.

## Configure `-parameters` javac option 

You can omit the value of `@Param` if you compiled your code with `-parameters` javac option.
Please refer to [Configure `-parameters` javac option](http://armeria.dev/setup.html#configure-parameters-javac-option) for more information.

## How to run

- Use `run` or `bootRun` task to run an example from Gradle.
- See [Open an existing Gradle project](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start) to import an example into IntelliJ IDEA.
- See [Configure `-parameters` javac option](http://armeria.dev/setup.html#configure-parameters-javac-option) to configure IntelliJ IDEA.
- See [Build and run the application](https://www.jetbrains.com/help/idea/creating-and-running-your-first-java-application.html#run_app) to run an example from IntelliJ IDEA.

## License

All files under this directory (`examples`) belong to
[the public domain](https://en.wikipedia.org/wiki/Public_domain).
Please feel free to copy-and-paste and start your awesome project with Armeria!
