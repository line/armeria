Visit [the official web site](https://line.github.io/armeria/) for more information.

# Armeria

_Armeria_ is an open-source asynchronous RPC/API client/server library built on top of
[Java 8](https://java.oracle.com/), [Netty 4.1](https://netty.io/), [HTTP/2](https://http2.github.io/),
[Thrift](https://thrift.apache.org/) and [gRPC](https://grpc.io/). Its primary goal is to help engineers build
high-performance asynchronous microservices that use HTTP/2 as a session layer protocol.

It is open-sourced and licensed under [Apache License 2.0](https://tldrlegal.com/license/apache-license-2.0-(apache-2.0))
by [LINE Corporation](https://linecorp.com/en/), who uses it in production.

## How to build

We use [Gradle](https://gradle.org/) and [Java 11 or later](https://java.oracle.com/) to build Armeria. The following command will compile Armeria and generate
JARs and web site:

```bash
$ ./gradlew build
```

## How to ask a question

Just [create a new issue](https://github.com/line/armeria/issues/new) to ask a question, and browse
[the list of previously answered questions](https://github.com/line/armeria/issues?q=label%3Aquestion).

We also have [a Slack workspace](https://line-slacknow.herokuapp.com/line-armeria/).

## How to contribute

See [`CONTRIBUTING.md`](CONTRIBUTING.md).
