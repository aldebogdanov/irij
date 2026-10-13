import * as path from 'node:path';
import * as vscode from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
} from 'vscode-languageclient/node';
import { replInput, topLevelFormAt } from './forms';

let client: LanguageClient | undefined;
let repl: vscode.Terminal | undefined;
let runner: vscode.Terminal | undefined;

function config() {
  return vscode.workspace.getConfiguration('irij');
}

function executable(): string {
  return config().get<string>('executable') || 'irij';
}

/** Workspace folder of the active Irij file, so `use <seed>` resolves
 *  the way it does for `irij run` from the project root. */
function projectDir(): string | undefined {
  const uri = vscode.window.activeTextEditor?.document.uri;
  const folder = uri && vscode.workspace.getWorkspaceFolder(uri);
  return folder?.uri.fsPath ?? (uri?.scheme === 'file' ? path.dirname(uri.fsPath) : undefined);
}

// ── Language server ───────────────────────────────────────────────────

async function startClient(): Promise<void> {
  if (!config().get<boolean>('languageServer.enabled', true)) return;
  const serverOptions: ServerOptions = {
    command: executable(),
    args: ['lsp'],
    transport: TransportKind.stdio,
    // `irij` is a shell script on Windows (irij.cmd)
    options: { shell: process.platform === 'win32' },
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: 'file', language: 'irij' },
      { scheme: 'untitled', language: 'irij' },
    ],
  };
  client = new LanguageClient('irij', 'Irij Language Server', serverOptions, clientOptions);
  try {
    await client.start();
  } catch (err) {
    client = undefined;
    const pick = await vscode.window.showErrorMessage(
      `Could not start the Irij language server (\`${executable()} lsp\`): ${err}`,
      'Open Settings',
    );
    if (pick) vscode.commands.executeCommand('workbench.action.openSettings', 'irij.executable');
  }
}

async function stopClient(): Promise<void> {
  const c = client;
  client = undefined;
  if (c) await c.stop();
}

async function restartClient(): Promise<void> {
  await stopClient();
  await startClient();
}

// ── REPL ─────────────────────────────────────────────────────────────

function ensureRepl(): vscode.Terminal {
  if (!repl || repl.exitStatus !== undefined) {
    repl = vscode.window.createTerminal({
      name: 'Irij REPL',
      shellPath: executable(),
      shellArgs: ['repl'],
      cwd: projectDir(),
      iconPath: new vscode.ThemeIcon('terminal'),
    });
  }
  return repl;
}

function startRepl(): void {
  ensureRepl().show();
}

function sendToRepl(): void {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;
  const doc = editor.document;
  let code: string;
  if (!editor.selection.isEmpty) {
    code = doc.getText(editor.selection);
  } else {
    const lines = Array.from({ length: doc.lineCount }, (_, i) => doc.lineAt(i).text);
    const form = topLevelFormAt(lines, editor.selection.active.line);
    if (!form) {
      vscode.window.showInformationMessage('Irij: no top-level form at the cursor');
      return;
    }
    code = doc.getText(new vscode.Range(form.start, 0, form.end, lines[form.end].length));
  }
  const term = ensureRepl();
  term.show(true);
  term.sendText(replInput(code), true);
}

async function sendFileToRepl(): Promise<void> {
  const doc = vscode.window.activeTextEditor?.document;
  if (!doc) return;
  const term = ensureRepl();
  term.show(true);
  if (doc.uri.scheme === 'file' && !doc.isDirty) {
    term.sendText(`:load ${doc.uri.fsPath}`, true);
  } else {
    term.sendText(replInput(doc.getText()), true);
  }
}

// ── Run file ─────────────────────────────────────────────────────────

async function runFile(uri?: vscode.Uri): Promise<void> {
  const doc = uri
    ? await vscode.workspace.openTextDocument(uri)
    : vscode.window.activeTextEditor?.document;
  if (!doc) return;
  if (doc.uri.scheme !== 'file') {
    vscode.window.showWarningMessage('Irij: save the file before running it');
    return;
  }
  if (doc.isDirty) await doc.save();
  runner?.dispose();
  runner = vscode.window.createTerminal({
    name: `Irij: ${path.basename(doc.uri.fsPath)}`,
    shellPath: executable(),
    shellArgs: ['run', doc.uri.fsPath],
    cwd: projectDir(),
  });
  runner.show(true);
}

// ── Activation ───────────────────────────────────────────────────────

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  context.subscriptions.push(
    vscode.commands.registerCommand('irij.startRepl', startRepl),
    vscode.commands.registerCommand('irij.sendToRepl', sendToRepl),
    vscode.commands.registerCommand('irij.sendFileToRepl', sendFileToRepl),
    vscode.commands.registerCommand('irij.runFile', runFile),
    vscode.commands.registerCommand('irij.restartLanguageServer', restartClient),
    vscode.window.onDidCloseTerminal((t) => {
      if (t === repl) repl = undefined;
      if (t === runner) runner = undefined;
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('irij.executable')
          || e.affectsConfiguration('irij.languageServer.enabled')) {
        void restartClient();
      }
    }),
  );
  await startClient();
}

export async function deactivate(): Promise<void> {
  await stopClient();
}
