import React from 'react';
import emoji from 'react-easy-emoji';

interface EmojiProps {
  text: string;
}

const Emoji: React.FC<EmojiProps> = (props) => svgEmoji(props.text);

function svgEmoji(input: string) {
  try {
    return emoji(input, {
      // baseUrl shouldn't end with '/'.
      // https://github.com/appfigures/react-easy-emoji/issues/25
      baseUrl: 'https://twemoji.maxcdn.com/2/svg',
      ext: '.svg',
      size: '',
    });
  } catch (e) {
    return null;
  }
}

export default Emoji;
