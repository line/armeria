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
      prettified = `${ch}\n${'  '.repeat(newIndentation)}`;
      break;
    case '}':
    case ']':
      newIndentation -= 1;
      prettified = `\n${'  '.repeat(newIndentation)}${ch}`;
      break;
    case ',':
      prettified = `,\n${'  '.repeat(newIndentation)}`;
      break;
    default:
      prettified = ch;
  }

  return [prettified, newIndentation];
}

// A modified version of JSON.minify() by Kyle Simpson that prettifies a JSON string without fully parsing it.
export function jsonPrettify(json: string) {
  if (json === '{}') {
    return json;
  }

  const tokenizer = /"|\n|\r/g;
  const newStr = [];
  let inString = false;
  let tmp: RegExpMatchArray | null;
  let ns = 0;
  let from = 0;

  let lc = '';
  let rc = '';

  tokenizer.lastIndex = 0;

  let indentation = 0;

  // eslint-disable-next-line no-cond-assign
  while ((tmp = tokenizer.exec(json))) {
    lc = (RegExp as any).leftContext;
    rc = (RegExp as any).rightContext;

    let substr = lc.substring(from);
    if (!inString) {
      substr = substr.replace(/(\n|\r|\s)*/g, '');
      for (let i = 0; i < substr.length; i += 1) {
        const prettified = doPrettify(substr.charAt(i), indentation);
        newStr[ns] = prettified[0];
        ns += 1;
        indentation = prettified[1];
      }
    } else {
      newStr[ns] = substr;
      ns += 1;
    }
    from = tokenizer.lastIndex;

    if (tmp[0] === '"') {
      const m = lc.match(/(\\)*$/);
      if (!inString || !m || m[0].length % 2 === 0) {
        // start of string with ", or unescaped " character found to end string
        inString = !inString;
      }
      from -= 1; // include " character in next catch
      rc = json.substring(from);
    } else if (!/\n|\r|\s/.test(tmp[0])) {
      newStr[ns] = tmp[0];
      ns += 1;
    }
  }

  for (let i = 0; i < rc.length; i += 1) {
    const prettified = doPrettify(rc.charAt(i), indentation);
    newStr[ns] = prettified[0];
    ns += 1;
    indentation = prettified[1];
  }

  return newStr.join('');
}

export function validateJsonObject(jsonObject: string, description: string) {
  let parsedJson;
  try {
    parsedJson = JSON.parse(jsonObject);
  } catch (e) {
    throw new Error(
      `Failed to parse a JSON object in the ${description}:\n${e}`,
    );
  }
  if (typeof parsedJson !== 'object') {
    throw new Error(
      `The ${description} must be a JSON object.\nYou entered: ${typeof parsedJson}`,
    );
  }
}

export function isValidJsonMimeType(applicationType: string | null) {
  if (!applicationType) {
    return false;
  }
  const isStreamingJson =
    ['x-ndjson', 'json-seq'].filter(
      (type) => applicationType.indexOf(type) >= 0,
    ).length > 0;
  if (isStreamingJson) {
    return false;
  }
  return applicationType.indexOf('json') >= 0;
}
