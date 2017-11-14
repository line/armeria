namespace java com.linecorp.armeria.benchmarks.thrift.services

// Tests a non-oneway method with a return value.
service HelloService {
    string hello(1:string name)
}
