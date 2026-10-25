import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://22cast.elias.dev',
  output: 'static',
  build: { inlineStylesheets: 'never' },
  vite: { build: { assetsInlineLimit: 0 } },
});
