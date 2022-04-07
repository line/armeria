import React from 'react';
import * as styles from './aspect-ratio.module.less';

interface AspectRatioProps {
  width: number;
  height: number;
  maxWidth: number | string;
  children: React.ReactNode;
}

const AspectRatio: React.FC<AspectRatioProps> = (props) => (
  <div
    className={styles.wrapper}
    style={{
      maxWidth:
        typeof props.maxWidth === 'number'
          ? `${props.maxWidth}px`
          : props.maxWidth,
      // @ts-ignore
      '--aspect-ratio': `(${props.width}/${props.height})`,
    }}
  >
    {props.children}
  </div>
);

export default AspectRatio;
