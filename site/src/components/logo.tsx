import React from 'react';

import * as styles from './logo.module.less';

interface LogoProps {
  className?: string;
  style?: React.CSSProperties;
  width?: string;
  height?: string;
  primaryColor?: string;
  secondaryColor?: string;
  tertiaryColor?: string;
  textColor?: string;
  notext?: boolean;
  role?: string;
  label?: string;
  ariaHidden?: boolean;
}

const Logo: React.FC<LogoProps> = (props) => {
  const primaryStyle: React.CSSProperties = props.primaryColor
    ? { fill: props.primaryColor }
    : {};
  const secondaryStyle: React.CSSProperties = props.secondaryColor
    ? { fill: props.secondaryColor }
    : {};
  const tertiaryStyle: React.CSSProperties = props.tertiaryColor
    ? { fill: props.tertiaryColor }
    : {};
  const textStyle: React.CSSProperties = props.textColor
    ? { fill: props.textColor }
    : {};

  const logoStyle: React.CSSProperties = {
    ...props.style,
  };

  logoStyle.width = props.width || logoStyle.width || '200px';
  logoStyle.height = props.height || logoStyle.height;
  logoStyle.overflow = logoStyle.overflow || 'hidden';
  logoStyle.verticalAlign = logoStyle.verticalAlign || 'middle';

  const label = props.label || 'Armeria project logo';

  return (
    <span
      role={props.role || 'img'}
      aria-label={label}
      aria-hidden={props.ariaHidden || false}
    >
      <svg
        className={props.className || ''}
        style={logoStyle}
        viewBox={props.notext ? '20 70 70 70' : '20 70 257 70'}
        version="1.1"
      >
        <title>{label}</title>
        <g id="layer1" transform="translate(0,-87)">
          <g
            id="g4208"
            transform="matrix(1.3217499,0,0,1.3217499,-49.437761,-33.946772)"
          >
            {props.notext ? (
              ''
            ) : (
              <>
                <g
                  transform="matrix(0.35277777,0,0,-0.35277777,124.77564,163.15531)"
                  id="g12"
                >
                  <path
                    id="path14"
                    className={styles.text}
                    style={textStyle}
                    d="M 0,0 -12.169,-30.421 H 12.27 Z m 15.515,-38.635 h -31.03 l -6.693,-16.63 h -9.632 l 26.77,65 H 5.679 l 26.77,-65 H 22.207 Z"
                  />
                </g>
                <g
                  transform="matrix(0.35277777,0,0,-0.35277777,142.85613,182.65161)"
                  id="g24"
                >
                  <path
                    id="path26"
                    className={styles.text}
                    style={textStyle}
                    d="M 0,0 H -9.024 V 48.572 H 0 v -6.084 c 2.637,4.462 6.795,7.302 12.169,7.302 3.042,0 5.679,-0.711 7.402,-2.13 L 15.82,39.243 C 14.197,40.358 11.865,41.17 9.532,41.17 3.651,41.17 0,36.099 0,27.987 Z"
                  />
                </g>
                <g
                  transform="matrix(0.35277777,0,0,-0.35277777,156.1523,182.65161)"
                  id="g28"
                >
                  <path
                    id="path30"
                    className={styles.text}
                    style={textStyle}
                    d="M 0,0 H -9.024 V 48.572 H 0 v -6.084 c 2.942,4.462 7.809,7.302 13.994,7.302 7.605,0 13.284,-3.651 16.225,-9.837 2.637,5.678 8.011,9.837 15.819,9.837 11.256,0 18.557,-7.606 18.557,-20.079 V 0 h -9.026 v 28.292 c 0,8.011 -4.36,13.081 -11.56,13.081 -7.301,0 -11.762,-5.07 -11.762,-13.081 V 0 h -8.923 v 28.292 c 0,8.011 -4.462,13.081 -11.662,13.081 C 4.361,41.373 0,36.303 0,28.292 Z"
                  />
                </g>
                <g
                  transform="matrix(0.35277777,0,0,-0.35277777,206.84412,182.65161)"
                  id="g32"
                >
                  <path
                    id="path34"
                    className={styles.text}
                    style={textStyle}
                    d="M 0,0 H -9.023 V 48.572 H 0 v -6.084 c 2.637,4.462 6.795,7.302 12.169,7.302 3.042,0 5.679,-0.711 7.402,-2.13 L 15.82,39.243 C 14.197,40.358 11.865,41.17 9.532,41.17 3.651,41.17 0,36.099 0,27.987 Z"
                  />
                </g>
                <path
                  id="path36"
                  className={styles.text}
                  style={textStyle}
                  d="m 219.48271,182.65175 h -3.18346 v -17.13512 h 3.18346 z m -3.57681,-21.87469 c 0,-1.07315 0.89429,-1.96744 1.96744,-1.96744 1.0728,0 1.96709,0.89429 1.96709,1.96744 0,1.0728 -0.89429,1.96709 -1.96709,1.96709 -1.07315,0 -1.96744,-0.89429 -1.96744,-1.96709"
                />
                <g
                  transform="matrix(0.35277777,0,0,-0.35277777,237.35923,174.06609)"
                  id="g38"
                >
                  <path
                    id="path40"
                    className={styles.text}
                    style={textStyle}
                    d="m 0,0 c 0,9.532 -6.59,17.036 -15.717,17.036 -9.127,0 -15.819,-7.504 -15.819,-17.036 0,-9.431 6.692,-17.138 15.819,-17.138 C -6.59,-17.138 0,-9.431 0,0 m 0,-16.731 c -3.751,-5.375 -9.632,-8.823 -17.035,-8.823 -13.183,0 -23.526,10.85 -23.526,25.554 0,14.399 9.938,25.453 23.526,25.453 7.403,0 13.284,-3.245 17.035,-8.518 v 7.3 H 9.025 V -24.337 H 0 Z"
                  />
                </g>
                <g
                  transform="matrix(0.35277777,0,0,-0.35277777,191.35962,167.80602)"
                  id="g42"
                >
                  <path
                    id="path44"
                    className={styles.text}
                    style={textStyle}
                    d="M 0,0 C 7.525,0 13.059,-4.97 14.549,-13.817 H -14.749 C -13.35,-5.849 -7.817,0 0,0 m -0.102,7.708 c -13.79,0 -24.031,-11.358 -24.031,-25.453 0,-14.096 10.647,-25.554 25.452,-25.554 7.807,0 15.243,3.623 19.871,9.904 l -6.557,4.637 c -4.034,-4.486 -7.534,-6.53 -13.01,-6.53 -9.107,0 -15.441,6.327 -16.579,14.878 H 23.25 c 0,0 0.292,2.159 0.292,3.577 0,13.69 -10.055,24.541 -23.644,24.541"
                  />
                </g>
              </>
            )}
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,80.962517,162.47851)"
              id="g46"
            >
              <path
                id="path48"
                className={styles.secondary}
                style={secondaryStyle}
                d="M 0,0 -20.785,-12 V -36 L 0,-48 20.785,-36 v 24 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,95.627238,162.47851)"
              id="g50"
            >
              <path
                id="path52"
                className={styles.secondary}
                style={secondaryStyle}
                d="M 0,0 V 24 L -20.785,12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,66.297787,170.94517)"
              id="g54"
            >
              <path
                id="path56"
                className={styles.secondary}
                style={secondaryStyle}
                d="M 0,0 V 24 L -20.784,12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,88.294897,192.11184)"
              id="g58"
            >
              <path
                id="path60"
                className={styles.secondary}
                style={secondaryStyle}
                d="M 0,0 V 24 L -20.785,12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,66.297787,154.01184)"
              id="g62"
            >
              <path
                id="path64"
                className={styles.secondary}
                style={secondaryStyle}
                d="m 0,0 v -24 l 20.785,12 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,95.627238,162.47851)"
              id="g66"
            >
              <path
                id="path68"
                className={styles.secondary}
                style={secondaryStyle}
                d="m 0,0 v -24 l 20.785,12 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,73.630137,183.64517)"
              id="g70"
            >
              <path
                id="path72"
                className={styles.secondary}
                style={secondaryStyle}
                d="m 0,0 v -24 l 20.785,12 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,73.630137,149.77851)"
              id="g74"
            >
              <path
                id="path76"
                className={styles.primary}
                style={primaryStyle}
                d="M 0,0 -20.785,-12 0,-24 20.785,-12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,95.627238,154.01184)"
              id="g78"
            >
              <path
                id="path80"
                className={styles.primary}
                style={primaryStyle}
                d="M 0,0 -20.785,12 -41.569,0 -20.785,-12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,88.294897,166.71184)"
              id="g82"
            >
              <path
                id="path84"
                className={styles.primary}
                style={primaryStyle}
                d="m 0,0 v 0 -24 l 20.785,12 V 12 L 0,24 -20.785,12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,73.630137,166.71184)"
              id="g86"
            >
              <path
                id="path88"
                className={styles.primary}
                style={primaryStyle}
                d="M 0,0 20.785,12 0,24 -20.785,12 V -12 L 0,-24 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,88.294897,192.11184)"
              id="g90"
            >
              <path
                id="path92"
                className={styles.primary}
                style={primaryStyle}
                d="M 0,0 20.785,12 V 36 L 0,24 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,95.627238,170.94517)"
              id="g94"
            >
              <path
                id="path96"
                className={styles.primary}
                style={primaryStyle}
                d="m 0,0 v -24 l 20.785,12 v 24 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,66.297787,187.8785)"
              id="g98"
            >
              <path
                id="path100"
                className={styles.primary}
                style={primaryStyle}
                d="M 0,0 20.785,-12 V 12 L 0,24 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,58.965587,166.71184)"
              id="g102"
            >
              <path
                id="path104"
                className={styles.primary}
                style={primaryStyle}
                d="m 0,0 v -24 l 20.784,-12 v 24 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,80.962517,179.41184)"
              id="g106"
            >
              <path
                id="path108"
                className={styles.primary}
                style={primaryStyle}
                d="M 0,0 -20.785,12 V -12 L 0,-24 20.785,-12 v 24 z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,88.294897,158.24517)"
              id="g110"
            >
              <path
                id="path112"
                className={styles.tertiary}
                style={tertiaryStyle}
                d="M 0,0 -20.785,12 -41.569,0 -20.785,-12 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,95.627238,170.94517)"
              id="g114"
            >
              <path
                id="path116"
                className={styles.tertiary}
                style={tertiaryStyle}
                d="M 0,0 -20.785,-12 V -36 L 0,-24 Z"
              />
            </g>
            <g
              transform="matrix(0.35277777,0,0,-0.35277777,66.297787,170.94517)"
              id="g118"
            >
              <path
                id="path120"
                className={styles.tertiary}
                style={tertiaryStyle}
                d="M 0,0 20.785,-12 V -36 L 0,-24 Z"
              />
            </g>
          </g>
        </g>
      </svg>
    </span>
  );
};

export default Logo;
