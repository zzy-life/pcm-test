import { build } from 'esbuild';
import { copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const output = fileURLToPath(new URL('../../app/src/main/assets/agent-renderer/', import.meta.url));
await build({
  entryPoints: [fileURLToPath(new URL('index.jsx', import.meta.url))],
  outfile: output + 'renderer.js', bundle: true, minify: true,
  format: 'iife', target: ['chrome80'], legalComments: 'external',
  define: { 'process.env.NODE_ENV': '"production"' }
});
await copyFile(new URL('index.html', import.meta.url), output + 'index.html');
