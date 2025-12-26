# Armeria proxy server

A proxy server that balances individual requests across the healthy backends using 
[Client-side load balancing](https://line.github.io/armeria/docs/client-service-discovery).

## Demo

To start up the server, use Gradle:

```bash
$ ./gradlew run --no-daemon
```

This will start up the proxy server and three backend servers which send streaming responses.
Each backend server streams ASCII animation to the proxy server at different interval.

To see the streaming data, open up your browser and connect to the proxy server at
[`http://127.0.0.1:8080/`](http://127.0.0.1:8080/). You will see the perpetual pendulum.
(In some browsers, you might not see it due to the browser compatibility. It was tested on Safari `12.0.3`.)

If you press `F5` (or the Refresh button) on the browser, the proxy server will forward your request
to the different backend. At this time, the perpetual pendulum will move slower or faster.
