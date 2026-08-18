import React from 'react';
import gifUrl from '@site/static/img/armeria.gif';
import videoUrl from '@site/static/img/armeria.m4v';

interface AnimatedLogoProps {
  width?: number;
  height?: number;
}

const AnimatedLogo: React.FC<AnimatedLogoProps> = ({
  width = 282,
  height = 112,
}) => (
  <video
    className="hideOnReducedMotion"
    src={videoUrl}
    preload="none"
    autoPlay
    muted
    loop
    style={{ width, height }}
  >
    <img
      src={gifUrl}
      loading="lazy"
      width={width}
      height={height}
      alt="Armeria Logo Animation"
    />
  </video>
);

export default AnimatedLogo;
