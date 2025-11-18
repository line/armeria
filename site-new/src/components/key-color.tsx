import React from 'react';

export const KeyColor = ({ name, hex, revertColor }) => {
  return (
    <div
      style={{
        background: hex,
        color: revertColor ? '#ffffff' : '#3a3a3a',
        width: '132px',
        padding: '0.4rem 0 0.5rem 0',
        margin: '8px',
        border: 'solid 1px #3a3a3a',
      }}
    >
      {name} <code style={{ background: 'none' }}>{hex}</code>
    </div>
  );
};

export const KeyColorContainer = ({ children }) => {
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'stretch',
        alignItems: 'stretch',
        textAlign: 'center',
        fontSize: '85%',
      }}
    >
      {children}
    </div>
  );
};
