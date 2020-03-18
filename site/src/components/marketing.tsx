import React from 'react';
// @ts-ignore
import Reveal from 'react-reveal/Fade';

import styles from './marketing.module.less';

interface HighlightProps {
  nowrap?: boolean;
}

interface MarketingProps {
  className?: string;
  reverse?: boolean;
}

interface MarketingBlockProps {
  className?: string;
  noreveal?: boolean;
}

const Highlight: React.FC<HighlightProps> = props => (
  <span className={props.nowrap ? styles.highlightNoWrap : styles.highlight}>
    {props.children}
  </span>
);

const Marketing: React.FC<MarketingProps> = props => {
  return (
    <div className={`${styles.wrapper} ${props.className || ''}`}>
      <div
        className={props.reverse ? styles.blockListReverse : styles.blockList}
      >
        {props.children}
      </div>
    </div>
  );
};

const MarketingBlock: React.FC<MarketingBlockProps> = props => {
  return (
    <div className={`${styles.block} ${props.className || ''}`}>
      {props.noreveal ? props.children : <Reveal>{props.children}</Reveal>}
    </div>
  );
};

export { Highlight, Marketing, MarketingBlock };
