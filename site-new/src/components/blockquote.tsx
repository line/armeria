import React from 'react';
import Heading from '@theme/Heading';
import useBaseUrl from '@docusaurus/useBaseUrl';

import styles from './blockquote.module.css';

interface BlockquoteProps {
  author: React.ReactNode;
  from: React.ReactNode;
  reverse?: boolean;
  children: React.ReactNode;
}

const Blockquote: React.FC<BlockquoteProps> = (props) => {
  const pattern1 = useBaseUrl('/img/pattern-1.svg');
  const pattern2 = useBaseUrl('/img/pattern-2.svg');

  return (
    <blockquote className={styles.blockquote}>
      <img
        src={props.reverse ? pattern2 : pattern1}
        className={
          props.reverse
            ? styles.backgroundPattern1Reverse
            : styles.backgroundPattern1
        }
        height={200}
        width={props.reverse ? 180 : 130}
        alt=""
        role="presentation"
        aria-hidden="true"
        loading="lazy"
      />
      <img
        src={props.reverse ? pattern1 : pattern2}
        className={
          props.reverse
            ? styles.backgroundPattern2Reverse
            : styles.backgroundPattern2
        }
        height={200}
        width={props.reverse ? 130 : 180}
        alt=""
        role="presentation"
        aria-hidden="true"
        loading="lazy"
      />

      <p className={styles.body}>
        {props.children}
        <span className={styles.quote} />
      </p>

      <Heading as="h4" className={styles.author}>
        <span className={styles.separator}>&mdash; </span>
        {props.author}
        <br />
        <span className={styles.from}>{props.from}</span>
      </Heading>
    </blockquote>
  );
};

export default Blockquote;
