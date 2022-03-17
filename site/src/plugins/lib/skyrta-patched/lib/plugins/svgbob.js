/* eslint-disable import/no-extraneous-dependencies */
// Installed by gatsby-remark-draw
const PipedProcess = require('skyrta/lib/pipedprocess');
const Command = require('skyrta/lib/command');
/* eslint-enable import/no-extraneous-dependencies */

module.exports = (() => {
  /*  Options format:
        ---------------        
            {
                fontFamily: "arial"
                fontSize: 14,
                scale: 1,
                strokeWidth: 2
            }        

        Arguments format:
        -----------------
            --font-family <font-family>      text will be rendered with this font (default: 'arial')
            --font-size <font-size>          text will be rendered with this font size (default: 14)        
            --scale <scale>                  scale the entire svg (dimensions, font size, stroke width) by this factor (default: 1)
            --stroke-width <stroke-width>    stroke width for all lines (default: 2)
    */

  const defaultArguments = [];

  function lang() {
    return 'bob';
  }

  function convertOptionToArgument(option, value) {
    if (!value) {
      return [];
    }
    return [option, value.toString()];
  }

  function getArguments(options) {
    if (!options) {
      return [];
    }

    const args = defaultArguments;
    return args
      .concat(convertOptionToArgument('--font-family', options.fontFamily))
      .concat(convertOptionToArgument('--font-size', options.fontSize))
      .concat(convertOptionToArgument('--scale', options.scale))
      .concat(convertOptionToArgument('--stroke-width', options.strokeWidth));
  }

  function getCommand(input, options) {
    return new Command('svgbob_cli', getArguments(options), input);
  }

  function generate(input, options) {
    const cmd = getCommand(input, options);
    const process = new PipedProcess();
    return process.execute(cmd.exec, cmd.args, cmd.input);
  }

  return {
    generate,
    lang,
    getCommand,
  };
})();
