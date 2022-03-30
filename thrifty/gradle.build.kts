dependencies {
    // Common thrift classes
    api(project(":thrift-common"))
    api("javax.annotation:javax.annotation-api")
    // https://mvnrepository.com/artifact/com.microsoft.thrifty/thrifty-runtime
    api("com.microsoft.thrifty:thrifty-runtime:3.0.0")
}
