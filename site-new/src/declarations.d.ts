declare module '*.less' {
  const content: { [className: string]: string };
  export default content;
}

declare module '*.gif' {
  const src: string;
  export default src;
}

declare module '*.m4v' {
  const src: string;
  export default src;
}
