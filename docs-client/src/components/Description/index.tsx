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

import React from 'react';
import 'github-markdown-css/github-markdown-light.css';
import Typography from '@material-ui/core/Typography';
import MarkdownWrapper from './Markdown';
import MermaidWrapper from './Mermaid';

interface DescriptionProps {
  docString?: string | JSX.Element;
  supportedMarkup: string;
}

const removeIndentDocString = (docString: string | undefined) => {
  try {
    if (!docString) {
      return '';
    }

    const lines = docString.replace(/@param .*[\n\r]*/gim, '').split('\n');
    const firstContentfulLine = lines[0].trim() ? lines[0] : lines[1];
    // @ts-ignore
    const indent = firstContentfulLine.match(/^\s*/)[0].length;

    return lines
      .map((l) => l.slice(indent))
      .join('\n')
      .trim();
  } catch (e) {
    return docString as string;
  }
};

const renderDefaultDocString = (docString: string): JSX.Element => {
  if (!docString) {
    return <div />;
  }

  const lines = docString.split(/(?:\r\n|\n|\r)/gim);
  return (
    <>
      {lines.map((line, i) => (
        // eslint-disable-next-line react/no-array-index-key
        <React.Fragment key={`${line}-${i}`}>
          {line}
          {i < lines.length - 1 ? <br /> : null}
        </React.Fragment>
      ))}
    </>
  );
};

const Description: React.FunctionComponent<DescriptionProps> = ({
  docString,
  supportedMarkup,
}) => {
  return (
    <>
      {supportedMarkup === 'MARKDOWN' && (
        <MarkdownWrapper
          docString={removeIndentDocString(docString as string)}
        />
      )}
      {supportedMarkup === 'MERMAID' && (
        <MermaidWrapper
          docString={removeIndentDocString(docString as string)}
        />
      )}
      {(!supportedMarkup || supportedMarkup === 'NONE') && (
        <Typography variant="body2">
          {renderDefaultDocString(docString as string)}
        </Typography>
      )}
    </>
  );
};

export default React.memo(Description);
