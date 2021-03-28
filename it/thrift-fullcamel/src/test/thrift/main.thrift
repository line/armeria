namespace java com.linecorp.armeria.service.test.thrift.main


service SayHelloService {
    // Test a underscored method name
    string say_hello(1:string name)

    // Test a upcamel method name
    string SayHelloNow(1:string name)

    // Test a lowcamel method name
    string sayHelloWorld(1:string name)
}
