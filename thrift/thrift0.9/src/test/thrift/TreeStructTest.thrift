namespace java testing.thrift.tree

struct IntLeaf {
    1: i32 value
}

struct StringLeaf {
    1: string value
}

struct Branch {
    1: list<LeafType> leafTypes
}

union LeafType {
    1: IntLeaf intLeaf,
    2: StringLeaf stringLeaf,
    3: Branch branch,
}

struct TreeRequest {
    1: LeafType base
}

service TreeService {
    string createTree(1: TreeRequest request)
}
