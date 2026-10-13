// Copies the shared TextMate grammar into the extension. The grammar is
// maintained once, in editors/textmate/ (also used by Shiki, Linguist,
// Zed); syntaxes/irij.tmLanguage.json is a build output, not committed.
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', '..', 'textmate', 'irij.tmLanguage.json');
const dest = join(here, '..', 'syntaxes', 'irij.tmLanguage.json');
mkdirSync(dirname(dest), { recursive: true });
copyFileSync(src, dest);
