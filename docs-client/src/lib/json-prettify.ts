/*
 * Copyright 2018 LINE Corporation
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

function doPrettify(ch: string, indentation: number): [string, number] {
  let prettified;
  let newIndentation = indentation;
  switch (ch) {
    case ':':
      prettified = ': ';
      break;
    case '{':
    case '[':
      newIndentation += 1;
      prettified = ch + '\n' + '  '.repeat(newIndentation);
      break;
    case '}':
    case ']':
      newIndentation -= 1;
      prettified = '\n' + '  '.repeat(newIndentation) + ch;
      break;
    case ',':
      prettified = ',\n' + '  '.repeat(newIndentation);
      break;
    default:
      prettified = ch;
  }

  return [prettified, newIndentation];
}

// A modified version of JSON.minify() by Kyle Simpson that prettifies a JSON string without fully parsing it.
export default function prettify(json: string) {
  if (json === '{}') {
    return json;
  }

  const tokenizer = /"|\n|\r/g;
  const new_str = [];
  let in_string = false;
  let tmp: RegExpMatchArray | null;
  let ns = 0;
  let from = 0;

  let lc = '';
  let rc = '';

  tokenizer.lastIndex = 0;

  let indentation = 0;

  // tslint:disable-next-line:no-conditional-assignment
  while ((tmp = tokenizer.exec(json))) {
    lc = (RegExp as any).leftContext;
    rc = (RegExp as any).rightContext;

    let substr = lc.substring(from);
    if (!in_string) {
      substr = substr.replace(/(\n|\r|\s)*/g, '');
      for (let i = 0; i < substr.length; i += 1) {
        const prettified = doPrettify(substr.charAt(i), indentation);
        new_str[ns] = prettified[0];
        ns += 1;
        indentation = prettified[1];
      }
    } else {
      new_str[ns] = substr;
      ns += 1;
    }
    from = tokenizer.lastIndex;

    if (tmp[0] === '"') {
      const m = lc.match(/(\\)*$/);
      if (!in_string || !m || m[0].length % 2 === 0) {
        // start of string with ", or unescaped " character found to end string
        in_string = !in_string;
      }
      from -= 1; // include " character in next catch
      rc = json.substring(from);
    } else if (!/\n|\r|\s/.test(tmp[0])) {
      new_str[ns] = tmp[0];
      ns += 1;
    }
  }

  for (let i = 0; i < rc.length; i += 1) {
    const prettified = doPrettify(rc.charAt(i), indentation);
    new_str[ns] = prettified[0];
    ns += 1;
    indentation = prettified[1];
  }

  return new_str.join('');
}
