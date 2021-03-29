namespace java com.linecorp.armeria.service.test.thrift.main

// Tests a non-oneway method with a return value.
service HelloService {
    string hello(1:string name)
}

// Tests one-way method.
service OnewayHelloService {
    oneway void hello(1:string name)
}

// Tests non-oneway method with void return value.
service DevNullService {
    void consume(1:string value)
}

// Tests method wihout parameter
service TimeService {
    i64 getServerTime()
}

// Tests exception handling.
exception FileServiceException {}
service FileService {
    void create(1:string path) throws (1:FileServiceException ouch)
}

// Tests structs and lists.
struct Name {
    1: string first
    2: string middle
    3: string last
}

service NameService {
    Name removeMiddle(1:Name name)
}

service NameSortService {
    list<Name> sort(1:list<Name> names)
}

// Tests out-of-order responses
service SleepService {
    i64 sleep(1:i64 delay)
}

// Tests DocService
exception FooServiceException {
    1: string stringVal,
}

enum FooEnum {
    VAL1 = 1,
    VAL2 = 2,
    VAL3 = 3,
}

union FooUnion {
    1: string stringVal,
    2: FooEnum enumVal,
}

struct FooStruct {
    1: bool boolVal,
    2: i8 byteVal,
    3: i16 i16Val,
    4: i32 i32Val,
    5: i64 i64Val,
    6: double doubleVal,
    7: string stringVal,
    8: binary binaryVal,
    /* 9: slist slistVal, */
    10: FooEnum enumVal,
    11: FooUnion unionVal,
    12: map<string, FooEnum> mapVal,
    13: set<FooUnion> setVal,
    14: list<string> listVal,
    15: optional FooStruct selfRef
}

typedef string              TypedefedString
typedef FooStruct           TypedefedStruct
typedef FooEnum             TypedefedEnum
typedef map<string, string> TypedefedMap
typedef list<string>        TypedefedList
typedef set<string>         TypedefedSet
typedef list<list<TypedefedStruct>> NestedTypedefedStructs

service FooService {
    void bar1() throws (1: FooServiceException e),
    string bar2() throws (1: FooServiceException e),
    FooStruct bar3(1: i32 intVal, 2: FooStruct foo) throws (1: FooServiceException e),
    list<FooStruct> bar4(1: list<FooStruct> foos) throws (1: FooServiceException e),
    map<string, FooStruct> bar5(1: map<string, FooStruct> foos) throws (1: FooServiceException e),

    // To make sure typedefs are handled correctly.
    void bar6(1: TypedefedString foo1, 2: TypedefedStruct foo2, 3: TypedefedEnum foo3,
              4: TypedefedMap foo4, 5: TypedefedList foo5, 6: TypedefedSet foo6,
              7: NestedTypedefedStructs foo7, 8: list<list<TypedefedStruct>> foo8)
}

// Tests Clients.newDerivedClient()
service HeaderService {
    string header(1: string name)
}

// Tests a binary parameter
service BinaryService {
    binary process(1: binary data)
}
