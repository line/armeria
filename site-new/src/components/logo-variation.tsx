import React from 'react';

export const LogoVariation = ({ name, subname, src, background }) => {
  return (
    <figure style={{ margin: '8px' }}>
      <img
        src={src}
        width="232"
        height="128"
        style={{
          border: 'solid 1px #3a3a3a',
          background: background || '#ffffff',
        }}
        alt={`${name} ${subname}`}
      />
      <figcaption>
        {name}
        {subname && (
          <>
            <br />
            <span>{subname}</span>
          </>
        )}
      </figcaption>
    </figure>
  );
};
export const LogoVariationContainer = ({ children }) => {
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'stretch',
        alignItems: 'stretch',
        textAlign: 'center',
      }}
    >
      {children}
    </div>
  );
};
