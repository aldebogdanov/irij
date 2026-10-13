// End-to-end: launches VS Code with this extension under development, a
// throwaway profile, and a workspace whose settings point irij.executable
// at IRIJ_EXECUTABLE (default: `irij` on PATH). Not part of `npm test` —
// it opens a VS Code window. Run with `npm run test:e2e`.
//
// VSCODE_EXECUTABLE selects an installed VS Code; otherwise test-electron
// downloads one into .vscode-test/.
import { runTests } from '@vscode/test-electron';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const work = mkdtempSync(join(tmpdir(), 'irij-vscode-e2e-'));
const ws = join(work, 'ws');
mkdirSync(join(ws, '.vscode'), { recursive: true });
writeFileSync(join(ws, '.vscode', 'settings.json'),
  JSON.stringify({ 'irij.executable': process.env.IRIJ_EXECUTABLE ?? 'irij' }));
writeFileSync(join(ws, 'sample.irj'), 'fn square\n  (n -> n * n)\n\nx := square 7\n');
writeFileSync(join(ws, 'broken.irj'), 'x := )\n');

await runTests({
  vscodeExecutablePath: process.env.VSCODE_EXECUTABLE,
  extensionDevelopmentPath: root,
  extensionTestsPath: join(root, 'test', 'e2e', 'suite.cjs'),
  launchArgs: [ws, '--disable-extensions', `--user-data-dir=${join(work, 'user')}`,
               '--skip-welcome', '--skip-release-notes'],
});
