import { CopyOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import React, { useCallback, useRef, useState } from 'react';
import CopyToClipboard from 'react-copy-to-clipboard';
import {
  PrismLight as Prism,
  SyntaxHighlighterProps,
} from 'react-syntax-highlighter';

// Prism styles
import prismTheme from 'react-syntax-highlighter/dist/esm/styles/prism/cb';

// Prism syntaxes
/* eslint-disable import/no-extraneous-dependencies */
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import cpp from 'react-syntax-highlighter/dist/esm/languages/prism/cpp';
import graphql from 'react-syntax-highlighter/dist/esm/languages/prism/graphql';
import groovy from 'react-syntax-highlighter/dist/esm/languages/prism/groovy';
import http from 'react-syntax-highlighter/dist/esm/languages/prism/http';
import java from 'react-syntax-highlighter/dist/esm/languages/prism/java';
import javascript from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import kotlin from 'react-syntax-highlighter/dist/esm/languages/prism/kotlin';
import markup from 'react-syntax-highlighter/dist/esm/languages/prism/markup';
import protobuf from 'react-syntax-highlighter/dist/esm/languages/prism/protobuf';
import scala from 'react-syntax-highlighter/dist/esm/languages/prism/scala';
import yaml from 'react-syntax-highlighter/dist/esm/languages/prism/yaml';
// @ts-ignore
import shellSession from 'refractor/lang/shell-session';
/* eslint-enable import/no-extraneous-dependencies */

import * as styles from './code-block.module.less';

// Register 'none' language.
const none = (prism: any) => {
  // eslint-disable-next-line no-param-reassign
  prism.languages.none = {};
};
none.displayName = 'none';
none.aliases = [] as string[];

const supportedLanguages = {
  bash,
  cpp,
  graphql,
  groovy,
  http,
  java,
  javascript,
  json,
  kotlin,
  markup,
  none,
  protobuf,
  scala,
  'shell-session': shellSession,
  xml: markup,
  yaml,
};

Object.entries(supportedLanguages).forEach(([name, func]) => {
  Prism.registerLanguage(name, func);
});

function filterLanguage(language?: string) {
  // eslint-disable-next-line no-param-reassign
  language = (language || 'none').toLowerCase();
  const isSupported = Object.prototype.hasOwnProperty.call(
    supportedLanguages,
    language,
  );
  if (!isSupported) {
    // eslint-disable-next-line no-console
    console.warn(`Unsupported code block language: ${language}`);
  }
  return isSupported ? language : 'none';
}

function getLineStyle(show?: any) {
  if (show === 1 || show === 'true')
    return { marginRight: '0.5em', minWidth: '1.4em' };
  return { display: 'none' };
}

// Override some CSS properties from the Prism style.
const newPrismTheme = Object.entries(prismTheme).reduce(
  (result, [key, value]) => {
    // @ts-ignore
    // eslint-disable-next-line no-param-reassign
    result[key] = {
      // @ts-ignore
      ...value,
      fontFamily: undefined,
      lineHeight: undefined,
      MozTabSize: 2,
      OTabSize: 2,
      tabSize: 2,
      background: undefined,
      textShadow: undefined,
      marginTop: undefined,
      marginBottom: undefined,
      padding: undefined,
    };
    return result;
  },
  {},
);

const lineHighlights = {
  backgroundColor: '#616161',
  display: 'block',
  width: '760px',
};

function getTargetLines(lines: string): string[] {
  const range = (start, end, length = end - start + 1) =>
    Array.from({ length }, (_, i) => start + i);

  const targetLines = lines.split(',').reduce((acc, cur) => {
    if (!cur.includes('-')) {
      acc.push(parseInt(cur, 10));
      return acc;
    }
    const [start, end] = cur.split('-');
    acc.push(...range(parseInt(start, 10), parseInt(end, 10)));
    return acc;
  }, []);

  return targetLines;
}

interface CodeBlockProps extends SyntaxHighlighterProps {
  filename?: string;
  highlight?: string;
  showlineno?: any;
}

const CodeBlock: React.FC<CodeBlockProps> = (props) => {
  const [copied, setCopied] = useState(false);
  const timeoutRef = useRef(null);
  const targetLines = props.highlight ? getTargetLines(props.highlight) : [];

  const onCopyCallback = useCallback(() => {
    setCopied(true);
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }
    timeoutRef.current = setTimeout(() => setCopied(false), 1000);
  }, []);

  const code = process(props.children);
  if (code.length === 0) {
    return null;
  }

  const codeToCopy =
    props.language === 'bash' ? removeDollarPrefixes(code) : code;
  const applyHighlightStyle = (lineNumber: number) => {
    if (lineNumber !== 0 && targetLines.includes(`${lineNumber}`)) {
      return { style: lineHighlights };
    }
    return {};
  };

  return (
    <div
      className={`${styles.wrapper} ${
        props.filename ? styles.hasFilename : ''
      }`}
    >
      {props.filename && (
        <div className={styles.filename}>{props.filename}</div>
      )}
      <CopyToClipboard text={codeToCopy} onCopy={onCopyCallback}>
        <Button
          className={styles.clipboardButton}
          aria-label="Copy to clipboard"
          type="ghost"
        >
          {copied ? 'Copied!' : <CopyOutlined />}
        </Button>
      </CopyToClipboard>
      <Prism
        wrapLines
        {...props}
        style={newPrismTheme}
        language={filterLanguage(props.language)}
        showLineNumbers={targetLines.length > 0 ? true : props.showlineno}
        lineNumberStyle={getLineStyle(props.showlineno)}
        lineProps={(lineNumber) => applyHighlightStyle(lineNumber)}
      >
        {code}
      </Prism>
    </div>
  );
};

// Removes the leading and trailing empty lines and trims extra white spaces.
function process(code: React.ReactNode) {
  let skippedLeadingEmptyLines = false;
  let lastLineIdx = 0;
  let indentation = Number.MAX_SAFE_INTEGER;
  let numRemovedLines = 0;

  function processNonEmptyLine(line: string, i: number) {
    lastLineIdx = i - numRemovedLines;
    indentation = Math.min(indentation, Math.max(0, line.search(/[^ \t]/)));
    return [line.trimRight()];
  }

  const codeStr = `${code}`;
  const lines = codeStr
    .split(/\r*\n/)
    .flatMap((line, i) => {
      if (!skippedLeadingEmptyLines) {
        if (line.match(/^[ \t]*$/)) {
          numRemovedLines += 1;
          return [];
        }

        skippedLeadingEmptyLines = true;
        return processNonEmptyLine(line, i);
      }

      if (line.match(/^[ \t]*$/)) {
        return [''];
      }

      return processNonEmptyLine(line, i);
    })
    .slice(0, lastLineIdx + 1);

  if (lines.length === 0) {
    return '';
  }

  return (
    indentation !== 0 ? lines.map((line) => line.substring(indentation)) : lines
  ).join('\n');
}

function removeDollarPrefixes(code: string): string {
  return code
    .split('\n')
    .map((line) => (line.startsWith('$ ') ? line.substring(2) : line))
    .join('\n');
}

export default CodeBlock;
