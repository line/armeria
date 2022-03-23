import { Typography } from 'antd';
import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Helmet } from 'react-helmet';
import ReactMixitup from 'react-mixitup';
import shuffleSeed from 'shuffle-seed';
import { OutboundLink } from 'gatsby-plugin-google-analytics';

import * as styles from './thank-you.module.less';

const { Paragraph } = Typography;

interface ThankYouProps {
  usernames: string[];
  message?: (seed: number) => React.ReactNode;
  href?: (username: string) => string;
  avatarUrls?: { [username: string]: string };
  size?: number;
}

const ThankYou: React.FC<ThankYouProps> = (props) => {
  const wrapperRef = useRef(null);
  const lastConfettiFireTime = useRef(0);
  const [seed, setSeed] = useState(currentSeed());
  const [periodicConfettiFire, setPeriodicConfettiFire] = useState(false);
  const numActiveConfetti = useRef(0);
  const [message, setMessage] = useState(getMessage(seed));

  function currentSeed() {
    return new Date().getTime();
  }

  function getMessage(newSeed: number) {
    return props.message ? (
      props.message(newSeed)
    ) : (
      <Paragraph>
        This release was possible thanks to the following contributors who
        shared their brilliant ideas and awesome pull requests:
      </Paragraph>
    );
  }

  function defaultHref(username: string) {
    return `https://github.com/${username}`;
  }

  // Update the seed every minute to give everyone a chance to appear first.
  useEffect(() => {
    const timerId = setInterval(() => {
      const newSeed = currentSeed();
      setSeed(newSeed);
      setMessage(getMessage(newSeed));
    }, 30 * 1000);
    return () => {
      clearInterval(timerId);
    };
  });

  // Fire confetti periodically when a cursor hovers over an avatar.
  const confettiFireInterval = 5000;
  useEffect(() => {
    if (!periodicConfettiFire || !window) {
      return () => {};
    }

    const timerId = setInterval(() => {
      const currentTime = new Date().getTime();
      if (currentTime - lastConfettiFireTime.current >= confettiFireInterval) {
        fireConfetti();
      }
    }, 250);
    return () => {
      clearInterval(timerId);
    };
  });

  // Fire confetti on mouse enter/exit.
  const onMouseEnterCallback = useCallback(() => {
    fireConfetti();
    setPeriodicConfettiFire(true);
  }, []);
  const onMouseExitCallback = useCallback(
    () => setPeriodicConfettiFire(false),
    [],
  );

  function fireConfetti() {
    // @ts-ignore
    const confetti: (any) => void = window.confetti;
    if (!confetti || numActiveConfetti.current >= 20) {
      return;
    }

    numActiveConfetti.current += 1;
    setTimeout(() => {
      numActiveConfetti.current -= 1;
    }, 5000);

    lastConfettiFireTime.current = new Date().getTime();
    confetti({
      disableForReducedMotion: true,
      particleCount: 100,
      startVelocity: 70 + (Math.random() - 0.5) * 30,
      angle: 45,
      origin: { x: 0, y: 1 },
    });
    confetti({
      disableForReducedMotion: true,
      particleCount: 100,
      startVelocity: 70 + (Math.random() - 0.5) * 30,
      angle: 135,
      origin: { x: 1, y: 1 },
    });
  }

  const usernames = shuffleSeed.shuffle(props.usernames.sort(), seed);
  const size = props.size || 64;
  const upscaledSize = size * 2;

  function getAvatarUrl(username: string) {
    let avatarUrl;
    if (props.avatarUrls) {
      avatarUrl = props.avatarUrls[username];
      if (avatarUrl) {
        avatarUrl = `${avatarUrl}${
          avatarUrl.indexOf('?') >= 0 ? '&' : '?'
        }size=${upscaledSize}`;
      }
    }

    return (
      avatarUrl || `https://github.com/${username}.png?size=${upscaledSize}`
    );
  }

  return (
    <>
      <Helmet>
        <script src="https://cdn.jsdelivr.net/npm/canvas-confetti@1.2.0/dist/confetti.browser.min.js" />
      </Helmet>
      {message}
      <div className={styles.wrapper}>
        <ReactMixitup
          ref={wrapperRef}
          items={usernames}
          renderCells={(items) =>
            items.map(({ key, ref, style }) => {
              return (
                <span
                  key={key}
                  ref={ref}
                  style={style}
                  className={styles.avatar}
                >
                  <OutboundLink href={(props.href || defaultHref)(key)}>
                    <img
                      src={getAvatarUrl(key)}
                      width={size}
                      height={size}
                      alt={`@${key}`}
                      title={`@${key}`}
                      loading="lazy"
                      onMouseEnter={onMouseEnterCallback}
                      onMouseLeave={onMouseExitCallback}
                    />
                  </OutboundLink>
                </span>
              );
            })
          }
        />
      </div>
    </>
  );
};

export default ThankYou;
