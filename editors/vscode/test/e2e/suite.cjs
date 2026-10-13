// Runs inside the VS Code extension host (see run.mjs).
const assert = require('node:assert/strict');
const path = require('node:path');
const vscode = require('vscode');

async function until(what, fn, ms = 60000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 250));
  }
}

exports.run = async function run() {
  const ws = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const steps = [];
  const check = async (name, fn) => { await fn(); steps.push(name); console.log(`  ✔ ${name}`); };

  const doc = await vscode.workspace.openTextDocument(path.join(ws, 'sample.irj'));
  await vscode.window.showTextDocument(doc);

  await check('.irj files get the irij language', () => assert.equal(doc.languageId, 'irij'));

  await check('extension activates', async () => {
    const ext = vscode.extensions.all.find((e) => e.packageJSON.name === 'irij');
    assert.ok(ext);
    await until('activation', () => ext.isActive);
  });

  await check('language server answers selectionRange', async () => {
    // second `n` in `n * n`
    const ranges = await until('selection ranges', async () => {
      const r = await vscode.commands.executeCommand(
        'vscode.executeSelectionRangeProvider', doc.uri, [new vscode.Position(1, 8)]);
      return r && r.length && r[0].parent ? r : undefined;
    });
    const chain = [];
    for (let s = ranges[0]; s; s = s.parent) chain.push(doc.getText(s.range));
    assert.ok(chain.includes('n * n'), JSON.stringify(chain));
    assert.ok(chain.includes('(n -> n * n)'), JSON.stringify(chain));
  });

  await check('language server answers hover', async () => {
    const hovers = await until('hover', async () => {
      const h = await vscode.commands.executeCommand(
        'vscode.executeHoverProvider', doc.uri, new vscode.Position(3, 7));
      return h && h.length ? h : undefined;
    });
    const text = hovers.flatMap((h) => h.contents.map((c) => c.value ?? String(c))).join('\n');
    assert.match(text, /fn square/);
  });

  await check('language server publishes diagnostics', async () => {
    const broken = await vscode.workspace.openTextDocument(path.join(ws, 'broken.irj'));
    await vscode.window.showTextDocument(broken);
    await until('diagnostics', () => vscode.languages.getDiagnostics(broken.uri).length > 0);
  });

  await check('commands are registered', async () => {
    const all = await vscode.commands.getCommands(true);
    for (const c of ['irij.startRepl', 'irij.sendToRepl', 'irij.sendFileToRepl',
                     'irij.runFile', 'irij.restartLanguageServer'])
      assert.ok(all.includes(c), c);
  });

  console.log(`e2e: ${steps.length} checks passed`);
};
