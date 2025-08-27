import { Typography } from 'antd';
import React from 'react';

import Pattern1 from '@site/static/img/pattern-1.svg';
import Pattern2 from '@site/static/img/pattern-2.svg';
import styles from './blockquote.module.css';

const { Title, Paragraph } = Typography;

interface BlockquoteProps {
  author: React.ReactNode;
  from: React.ReactNode;
  bgColor1: string;
  bgColor2: string;
  reverse?: boolean;
  children: React.ReactNode;
}

const Blockquote: React.FC<BlockquoteProps> = (props) => {
  return (
    <blockquote className={styles.blockquote}>
      {props.reverse ? (
        <Pattern2
          className={styles.backgroundPattern1Reverse}
          height={200}
          aria-hidden
          title=" "
        />
      ) : (
        <Pattern1
          className={styles.backgroundPattern1}
          height={200}
          aria-hidden
        />
      )}
      {props.reverse ? (
        <Pattern1
          className={styles.backgroundPattern2Reverse}
          height={200}
          aria-hidden
        />
      ) : (
        <Pattern2
          className={styles.backgroundPattern2}
          height={200}
          aria-hidden
        />
      )}
      <Paragraph
        className={styles.body}
        style={{ backgroundColor: props.bgColor1 }}
      >
        {props.children}
        <span
          className={styles.quote}
          style={{ borderColor: props.bgColor2 }}
        />
      </Paragraph>
      <Title level={4} className={styles.author}>
        <span className={styles.separator}>&mdash; </span>
        {props.author}
        <br />
        <span className={styles.from}>{props.from}</span>
      </Title>
    </blockquote>
  );
};

export default Blockquote;
