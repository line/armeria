/*
 * Copyright 2022 LINE Corporation
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
/*
 *  Copyright (c) 2021 GraphQL Contributors.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 */

import React, { useCallback, useState } from 'react';
import { UnControlled as CodeMirror } from 'react-codemirror2';

import 'codemirror/lib/codemirror.css';
import './index.css';
import { GraphQLSchema } from 'graphql';

import 'codemirror/addon/display/placeholder';

import 'codemirror/addon/hint/show-hint';
import 'codemirror/addon/hint/show-hint.css';

import 'codemirror/addon/edit/closebrackets';

import 'codemirror/addon/lint/lint';

import 'codemirror-graphql/hint';
import 'codemirror-graphql/lint';
import 'codemirror-graphql/mode';
import { EditorChange } from 'codemirror';

interface Props {
  height: number;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
  schema: GraphQLSchema | undefined;
}

// Forked from GraphiQL 1.5.16
// https://github.com/graphql/graphiql/blob/8ac05f8b141b6f5cb4449c62ad67a34115490ac8/packages/graphiql/src/components/QueryEditor.tsx
const QueryEditor: React.FunctionComponent<Props> = (props) => {
  const [container, setContainer] = useState<HTMLElement | undefined>();

  const onKeyUp = useCallback(
    (editor: CodeMirror.Editor, event: KeyboardEvent) => {
      const code = event.keyCode;
      if (!editor) {
        return;
      }
      if (
        (code >= 65 && code <= 90) || // letters
        (!event.shiftKey && code >= 48 && code <= 57) || // numbers
        (event.shiftKey && code === 189) || // underscore
        (event.shiftKey && code === 222) // "
      ) {
        editor.execCommand('autocomplete');
      }
    },
    [],
  );

  return (
    <>
      <CodeMirror
        autoCursor={false}
        value={props.value}
        options={{
          placeholder: props.placeholder,
          lineNumbers: false,
          tabSize: 2,
          mode: 'graphql',
          smartIndent: true,
          autoCloseBrackets: true,
          showCursorWhenSelecting: true,
          lint: {
            // @ts-ignore
            schema: props.schema,
          },
          hintOptions: {
            schema: props.schema,
            closeOnUnfocus: true,
            completeSingle: false,
            container,
          },
          gutters: ['CodeMirror-linenumbers'],
          extraKeys: {
            'Cmd-Space': (editor: CodeMirror.Editor) =>
              editor.showHint({
                completeSingle: true,
                closeOnUnfocus: true,
                container,
              }),
            'Ctrl-Space': (editor: CodeMirror.Editor) =>
              editor.showHint({
                completeSingle: true,
                closeOnUnfocus: true,
                container,
              }),
            'Alt-Space': (editor: CodeMirror.Editor) =>
              editor.showHint({
                completeSingle: true,
                closeOnUnfocus: true,
                container,
              }),
            'Shift-Space': (editor: CodeMirror.Editor) =>
              editor.showHint({
                completeSingle: true,
                closeOnUnfocus: true,
                container,
              }),
            'Shift-Alt-Space': (editor: CodeMirror.Editor) =>
              editor.showHint({
                completeSingle: true,
                closeOnUnfocus: true,
                container,
              }),
          },
        }}
        editorDidMount={(editor: CodeMirror.Editor) => {
          editor.setSize('100%', props.height);
          setContainer(editor.getWrapperElement());
        }}
        onChange={(
          _editor: CodeMirror.Editor,
          _data: EditorChange,
          value: string,
        ) => {
          return props.onChange(value);
        }}
        onKeyUp={onKeyUp}
      />
    </>
  );
};

export default React.memo(QueryEditor);
