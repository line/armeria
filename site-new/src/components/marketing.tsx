import React, { ReactNode } from 'react';
import { Fade } from 'react-awesome-reveal';
import clsx from 'clsx';
import BrowserOnly from '@docusaurus/BrowserOnly';

import styles from './marketing.module.css';

interface HighlightProps {
  nowrap?: boolean;
  children?: ReactNode;
}

interface MarketingProps {
  className?: string;
  reverse?: boolean;
  children?: ReactNode;
}

interface MarketingBlockProps {
  className?: string;
  noreveal?: boolean;
  children?: ReactNode;
}

const Highlight: React.FC<HighlightProps> = (props) => (
  <span
    className={clsx(props.nowrap ? styles.highlightNoWrap : styles.highlight)}
  >
    {props.children}
  </span>
);

const Marketing: React.FC<MarketingProps> = (props) => {
  return (
    <div className={clsx(styles.wrapper, props.className || '')}>
      <div
        className={clsx(
          props.reverse ? styles.blockListReverse : styles.blockList,
        )}
      >
        {props.children}
      </div>
    </div>
  );
};

const MarketingBlock: React.FC<MarketingBlockProps> = (props) => {
  return (
    <div className={clsx(styles.block, props.className || '')}>
      {props.noreveal ? (
        props.children
      ) : (
        <BrowserOnly fallback={props.children}>
          {() => <Fade>{props.children}</Fade>}
        </BrowserOnly>
      )}
    </div>
  );
};

export { Highlight, Marketing, MarketingBlock };
