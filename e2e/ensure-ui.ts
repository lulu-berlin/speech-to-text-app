// The Gradle e2e path builds the UI through this script (build.gradle.kts `e2eFrontend`); a direct
// Playwright run reaches the same decision from global-setup.ts.
import { fileURLToPath } from 'node:url';
import { buildUi } from './lib/ui-build.ts';
import { ensureUiBuilt } from './lib/ui-dist.ts';

const root = fileURLToPath(new URL('..', import.meta.url));
await ensureUiBuilt({ root, build: () => buildUi({ root }), log: (line) => console.log(line) });
