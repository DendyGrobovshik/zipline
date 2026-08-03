const path = require('path');

module.exports = {
  mode: 'development',
  entry: './kotlin/zipline-root-intrinsic-tests.js',
  output: {
    filename: 'collection-bundle.js',
    path: path.resolve(__dirname, 'dist'),
    globalObject: 'globalThis'
  },
  resolve: {
    modules: [
      path.resolve(__dirname, 'kotlin'),
      'node_modules'
    ]
  },
  optimization: {
    splitChunks: false
  },
  devtool: false
};