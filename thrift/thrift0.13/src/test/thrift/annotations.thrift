namespace java testing.thrift.main

include "main.thrift"

struct InnerFooStruct {
    1: string stringVal,
}

typedef main.FooUnion              TypedefedUnion
typedef set<main.FooUnion>         TypedefedSetUnion
typedef InnerFooStruct        TypedefedInnerFooStruct

typedef map<string, main.FooEnum> TypedefedEnumMap
typedef map<string, TypedefedInnerFooStruct> InnerFooStructMap
typedef list<TypedefedInnerFooStruct> InnerFooStructList
typedef set<TypedefedInnerFooStruct> InnerFooStructSet

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
    15: optional NormalFooStruct selfRef,
    16: InnerFooStruct innerFooStruct,
    17: list<list<InnerFooStruct>> listOfLists,
    18: set<set<InnerFooStruct>> setOfSets,
    19: map<string, map<string, InnerFooStruct>> mapOfMaps,
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
    16: optional InnerFooStruct innerFooStruct,
    17: optional list<list<InnerFooStruct>> listOfLists,
    18: optional set<set<InnerFooStruct>> setOfSets,
    19: optional map<string, map<string, InnerFooStruct>> mapOfMaps,
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
    16: required InnerFooStruct innerFooStruct,
    17: required list<list<InnerFooStruct>> listOfLists,
    18: required set<set<InnerFooStruct>> setOfSets,
    19: required map<string, map<string, InnerFooStruct>> mapOfMaps,
}

typedef list<list<InnerFooStruct>> TypedefedListOfLists
typedef set<set<InnerFooStruct>> TypedefedSetOfSets
typedef map<string, map<string, InnerFooStruct>> TypedefedMapOfMaps

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
    11: TypedefedUnion unionVal,
    12: TypedefedEnumMap mapVal,
    13: TypedefedSetUnion setVal,
    14: main.TypedefedListString listVal,
    16: TypedefedInnerFooStruct innerFooStruct,
    17: TypedefedListOfLists listOfLists,
    18: TypedefedSetOfSets setOfSets,
    19: TypedefedMapOfMaps mapOfMaps,
}

// Defining the inner struct before the containing struct guarantees correct metadata
// generation pre-0.17. See THRIFT-4086.
struct SecretStruct {
    1: string hello;
    2: string secret (grade = "red");
    3: InnerFooStruct innerFooStruct;
    4: TypedefedInnerFooStruct typedefedInnerFooStruct;
    5: map<string, InnerFooStruct> innerFooStructMap;
    6: InnerFooStructMap typedefedInnerFooStructMap;
    7: list<InnerFooStruct> innerFooStructList;
    8: InnerFooStructList typedefedInnerFooStructList;
    9: set<InnerFooStruct> innerFooStructSet;
    10: InnerFooStructSet typedefedInnerFooStructSet;
    11: NormalFooStruct fooStruct;
    12: TypedefedFooStruct typedefedFooStruct;
    13: i32 secretNum;
}

service SecretService {
    // annotations on method parameters are not supported for now
    SecretStruct hello(1: SecretStruct req)
}
