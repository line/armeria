Visit [the official web site](https://line.github.io/armeria/) for more information.

# Armeria

_Armeria_ is an open-source asynchronous RPC/API client/server library built on top of [Java 8](https://java.oracle.com/), [Netty 4.1](https://netty.io/), [HTTP/2](https://http2.github.io/), and [Thrift](https://thrift.apache.org/). Its primary goal is to help engineers build high-performance asynchronous Thrift microservices that use HTTP/2 as a session layer protocol, although it is designed to be protocol-agnostic and highly extensible (for example, you can serve a directory of static files via HTTP/2 and run Java EE web applications).

It is open-sourced and licensed under [Apache License 2.0](https://tldrlegal.com/license/apache-license-2.0-(apache-2.0)) by [LINE Corporation](https://linecorp.com/en/), who uses it in production.

## How to build

We use [Gradle](https://gradle.org/) to build Armeria. The following command will compile Armeria and generate JARs and web site:

```bash
$ ./gradlew build
```

## How to ask a question

Just [create a new issue](https://github.com/line/armeria/issues/new) to ask a question, and browse [the list of previously answered questions](https://github.com/line/armeria/issues?q=label%3Aquestion-answered).

We also have [a Slack workspace](https://line-slacknow.herokuapp.com/line-armeria/).

## How to contribute

See [`CONTRIBUTING.md`](CONTRIBUTING.md).
