namespace java com.linecorp.armeria.service.test.thrift.main

// Tests a underscored method name
service SayHelloService {
    string say_hello(1:string name)
}