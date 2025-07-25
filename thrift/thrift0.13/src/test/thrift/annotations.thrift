namespace java testing.thrift.main

include "main.thrift"

typedef map<string, main.TypedefedInnerFooStruct> InnerFooStructMap
typedef list<main.TypedefedInnerFooStruct> InnerFooStructList
typedef set<main.TypedefedInnerFooStruct> InnerFooStructSet

// Defining the inner struct before the containing struct guarantees correct metadata
// generation pre-0.17. See THRIFT-4086.
struct NormalFooStruct {
    1: bool boolVal,
    2: i8 byteVal,
    3: i16 i16Val,
    4: i32 i32Val,
    5: i64 i64Val,
    6: double doubleVal,
    7: string stringVal,
    8: binary binaryVal,
    /* 9: slist slistVal, */
    10: main.FooEnum enumVal,
    11: main.FooUnion unionVal,
    12: map<string, main.FooEnum> mapVal,
    13: set<main.FooUnion> setVal,
    14: list<string> listVal,
    15: optional main.FooStruct selfRef,
    16: main.InnerFooStruct innerFooStruct,
    17: list<list<main.InnerFooStruct>> listOfLists,
    18: set<set<main.InnerFooStruct>> setOfSets,
    19: map<string, map<string, main.InnerFooStruct>> mapOfMaps,
}

struct SecretStruct {
    1: string hello;
    2: string secret (grade = "red");
    3: main.InnerFooStruct innerFooStruct;
    4: main.TypedefedInnerFooStruct typedefedInnerFooStruct;
    5: map<string, main.InnerFooStruct> innerFooStructMap;
    6: InnerFooStructMap typedefedInnerFooStructMap;
    7: list<main.InnerFooStruct> innerFooStructList;
    8: InnerFooStructList typedefedInnerFooStructList;
    9: set<main.InnerFooStruct> innerFooStructSet;
    10: InnerFooStructSet typedefedInnerFooStructSet;
    11: NormalFooStruct fooStruct;
    12: TypedefedFooStruct typedefedFooStruct;
    13: i32 secretNum;
}

service SecretService {
    // annotations on method parameters are not supported for now
    SecretStruct hello(1: SecretStruct req)
}

struct OptionalFooStruct {
    1: optional bool boolVal,
    2: optional i8 byteVal,
    3: optional i16 i16Val,
    4: optional i32 i32Val,
    5: optional i64 i64Val,
    6: optional double doubleVal,
    7: optional string stringVal,
    8: optional binary binaryVal,
    /* 9: slist slistVal, */
    10: optional main.FooEnum enumVal,
    11: optional main.FooUnion unionVal,
    12: optional map<string, main.FooEnum> mapVal,
    13: optional set<main.FooUnion> setVal,
    14: optional list<string> listVal,
    15: optional OptionalFooStruct selfRef,
    16: optional main.InnerFooStruct innerFooStruct,
    17: optional list<list<main.InnerFooStruct>> listOfLists,
    18: optional set<set<main.InnerFooStruct>> setOfSets,
    19: optional map<string, map<string, main.InnerFooStruct>> mapOfMaps,
}

struct RequiredFooStruct {
    1: required bool boolVal,
    2: required i8 byteVal,
    3: required i16 i16Val,
    4: required i32 i32Val,
    5: required i64 i64Val,
    6: required double doubleVal,
    7: required string stringVal,
    8: required binary binaryVal,
    /* 9: slist slistVal, */
    10: required main.FooEnum enumVal,
    11: required main.FooUnion unionVal,
    12: required map<string, main.FooEnum> mapVal,
    13: required set<main.FooUnion> setVal,
    14: required list<string> listVal,
    16: required main.InnerFooStruct innerFooStruct,
    17: required list<list<main.InnerFooStruct>> listOfLists,
    18: required set<set<main.InnerFooStruct>> setOfSets,
    19: required map<string, map<string, main.InnerFooStruct>> mapOfMaps,
}

typedef list<list<main.InnerFooStruct>> TypedefedListOfLists
typedef set<set<main.InnerFooStruct>> TypedefedSetOfSets
typedef map<string, map<string, main.InnerFooStruct>> TypedefedMapOfMaps

struct TypedefedFooStruct {
    1: main.TypedefedBool boolVal,
    2: main.TypedefedByte byteVal,
    3: main.TypedefedI16 i16Val,
    4: main.TypedefedI32 i32Val,
    5: main.TypedefedI64 i64Val,
    6: main.TypedefedDouble doubleVal,
    7: main.TypedefedString stringVal,
    8: main.TypedefedBinary binaryVal,
    /* 9: slist slistVal, */
    10: main.TypedefedEnum enumVal,
    11: main.TypedefedUnion unionVal,
    12: main.TypedefedEnumMap mapVal,
    13: main.TypedefedSetUnion setVal,
    14: main.TypedefedListString listVal,
    16: main.TypedefedInnerFooStruct innerFooStruct,
    17: TypedefedListOfLists listOfLists,
    18: TypedefedSetOfSets setOfSets,
    19: TypedefedMapOfMaps mapOfMaps,
}
