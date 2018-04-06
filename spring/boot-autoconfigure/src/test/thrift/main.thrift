namespace java com.linecorp.armeria.spring.test.thrift.main

// Tests a non-oneway method with a return value.
service HelloService {
    string hello(1:string name)
}
