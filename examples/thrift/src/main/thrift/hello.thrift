namespace java example.armeria.thrift

service HelloService {
    HelloReply hello(1:HelloRequest request)
    HelloReply lazyHello (1:HelloRequest request)
    HelloReply blockingHello (1:HelloRequest request)
}

struct HelloRequest {
  1: required string name;
}

struct HelloReply {
  1: required string message;
}
