/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
// A modified version of JSON.minify() by Kyle Simpson that prettifies a JSON string without fully parsing it.
(function(global){
  if (typeof global.JSON == "undefined" || !global.JSON) {
    global.JSON = {};
  }

  global.JSON.prettify = function(json) {

    var tokenizer = /"|\n|\r/g,
        in_string = false,
        tmp, tmp2, new_str = [], ns = 0, from = 0, lc, rc;

    tokenizer.lastIndex = 0;

    var indentation = 0;

    function prettify(ch, indentation) {
      var prettified;
      switch (ch) {
        case ':':
          prettified = ': ';
          break;
        case '{':
        case '[':
          indentation++;
          prettified = ch + '\n' + '  '.repeat(indentation);
          break;
        case '}':
        case ']':
          indentation--;
          prettified = '\n' + '  '.repeat(indentation) + ch;
          break;
        case ',':
          prettified = ',\n' + '  '.repeat(indentation);
          break;
        default:
          prettified = ch;
      }

      return [ prettified, indentation ];
    }

    while (tmp = tokenizer.exec(json)) {
      lc = RegExp.leftContext;
      rc = RegExp.rightContext;

      tmp2 = lc.substring(from);
      if (!in_string) {
        tmp2 = tmp2.replace(/(\n|\r|\s)*/g,"");
        for (var i = 0; i < tmp2.length; i++) {
          var prettified = prettify(tmp2.charAt(i), indentation);
          new_str[ns++] = prettified[0];
          indentation = prettified[1];
        }
      } else {
        new_str[ns++] = tmp2;
      }
      from = tokenizer.lastIndex;

      if (tmp[0] == "\"") {
        tmp2 = lc.match(/(\\)*$/);
        if (!in_string || !tmp2 || (tmp2[0].length % 2) == 0) {	// start of string with ", or unescaped " character found to end string
          in_string = !in_string;
        }
        from--; // include " character in next catch
        rc = json.substring(from);
      }
      else if (!(/\n|\r|\s/.test(tmp[0]))) {
        new_str[ns++] = tmp[0];
      }
    }

    for (var i = 0; i < rc.length; i++) {
      var prettified = prettify(rc.charAt(i), indentation);
      new_str[ns++] = prettified[0];
      indentation = prettified[1];
    }

    return new_str.join("");
  };
})(this);
