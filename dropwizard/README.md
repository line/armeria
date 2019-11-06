Dropwizard Armeria Bundle
========================

A bundle for delegating application traffic through [Armeria](https://line.github.io/armeria/index.html) endpoints in Dropwizard applications.

Dependency Info
---------------
```xml
<dependency>
    <groupId>com.linecorp.armeria</groupId>
    <artifactId>dropwizard-armeria</artifactId>
    <version>0.95.0</version>
</dependency>
```

Usage
-----
Add an `ArmeriaBundle` to your [Application](https://javadoc.io/static/io.dropwizard/dropwizard-core/1.3.16/io/dropwizard/Application.html) class.

```java
@Override
public void initialize(Bootstrap<MyConfiguration> bootstrap) {
    // ...
    final ArmeriaBundle bundle = new AbstractArmeriaBundle<DropwizardArmeriaConfiguration>() {
        @Override
        public void onServerBuilderReady(final ServerBuilder builder) {
            builder.service("/", (ctx, res) -> HttpResponse.of(MediaType.HTML_UTF_8, "<h2>It works!</h2>"));
            builder.service("/armeria", (ctx, res) -> HttpResponse.of("Hello, Armeria!"));

            builder.annotatedService(new HelloService());

            // You can also bind asynchronous RPC services such as Thrift and gRPC:
            // builder.service(THttpService.of(...));
            // builder.service(GrpcService.builder()...build());
        }
    };
    bootstrap.addBundle(bundle);
}
```

Configuration
-------------
This bundle introduces a new Dropwizard `ServerFactory` of type `armeria` that implements the same contract of the [`simple` ServerFactory](https://www.dropwizard.io/en/stable/manual/configuration.html#simple) as well as [HTTP(S) `ConnectorFactory`](https://www.dropwizard.io/en/stable/manual/configuration.html#http) implementations of `armeria-http` or `armeria-https`. 

A minimal configuration file looks like so. 

```yaml
server:
  type: armeria
  connector:
    type: armeria-http
    port: 8080
```

### Additional `server` properties

> ***Note:*** Not all Dropwizard configurations can be passed into the Armeria server.  Currently supported parameters are:   
>   - `maxQueuedRequests`
>   - `maxThreads`
>   - `maxRequestLength`
>   - `maxRequestLength`
>   - `idleThreadTimeout`
>   - `shutdownGracePeriod`

`jerseyEnabled` - To enable / disable Dropwizard Jersey resources. Default=true. If set to false, this does not disable Dropwizard's Admin servlet for metrics or healthchecks. 

`accessLogWriter` - Used to configure [Armeria's Access Log Writer](https://line.github.io/armeria/server-access-log.html#customizing-a-log-format).  

Common (**default**) / Combined

```yaml
server:
  type: armeria
  accessLogWriter:
    type: common  # or combined
```

Custom

```yaml
server:
  type: armeria
  accessLogWriter:
    type: custom
    format: "%d - sample - %s"
```

### Additional Connector properties

The default `port` is 8082, to not interfere with Dropwizard's default application or admin servlets. 

**armeria-https**
  - `keyCertChainFile` - The path to the key cert chain file
  - `selfSigned` - True if a certificate is self-signed. Default=false. 

Support
-------
Please file bug reports and feature requests in [GitHub issues](https://github.com/line/armeria/issues).

License
-------
This library is licensed under the Apache License, Version 2.0.

See http://www.apache.org/licenses/LICENSE-2.0.html or the [LICENSE](../LICENSE.txt) file in this repository for the full license text.

Contributors
------------
Contributed by @cricket007
