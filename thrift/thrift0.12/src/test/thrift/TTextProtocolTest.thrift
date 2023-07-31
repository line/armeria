// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

/*
 * Used for testing TTextProtocol.java
 *
 * Authors:
 * "Alex Roetter" <aroetter@twitter.com>
 */
namespace java testing.thrift.text

enum Letter {
  ALPHA   = 1,
  BETA    = 2,
  CHARLIE = 3,
  DELTA   = 4,
  ECHO    = 5,
}

enum Number {
  ONE   = 1,
  TWO   = 2,
  THREE = 3,
  FOUR  = 4,
  FIVE  = 5,
}

typedef Letter Moji

union TestUnion {
  1: binary f1
  2: i32 f2
}

struct SubSub {
  1: required i32 x
}

struct Sub {
  1: required i32 s;

  2: required SubSub s2;
}

struct NumberSub {
  1: required Number n;
}

struct TTextProtocolTestMsg {
  1: required i64 a;

  2: required i32 b;

  13: required i16 n;

  3: required Sub c;

  4: required list<i32> d;

  5: required list<Sub> e;

  6: required bool f;

  7: required i8 g;

  8: required map<i32, i64> h;

  9: required map<i16, list<bool>> j;

  10: required set<bool> k;

  11: required binary l;

  12: required string m;

  14: required Letter p;

  15: required set<Letter> q;

  16: required map<Sub, i64> r;

  17: required map<map<map<i64, i64>, i64>, i64> s;

  18: required list<Letter> t;

  19: required map<string, Letter> u;

  20: required Letter v;

  21: required TestUnion w;

  22: required list<TestUnion> x;

  23: Moji y;  // Does not support string values.

  27: required map<Letter, i32> aa;

  28: required map<Letter, Number> ab;

  29: required map<map<Number, i32>, map<NumberSub, map<string, list<Letter>>>> ac;
}

struct TTextProtocolTestMsgUnion {
  1: required TestUnion u;
}
