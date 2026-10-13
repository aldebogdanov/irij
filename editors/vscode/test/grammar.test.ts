import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import * as oniguruma from 'vscode-oniguruma';
import { INITIAL, IGrammar, Registry, parseRawGrammar } from 'vscode-textmate';

async function loadGrammar(): Promise<IGrammar> {
  const wasm = readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
  await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
  const registry = new Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (p: string[]) => new oniguruma.OnigScanner(p),
      createOnigString: (s: string) => new oniguruma.OnigString(s),
    }),
    loadGrammar: async (scope) => scope === 'source.irij'
      ? parseRawGrammar(readFileSync('syntaxes/irij.tmLanguage.json', 'utf8'), 'irij.tmLanguage.json')
      : null,
  });
  const g = await registry.loadGrammar('source.irij');
  assert.ok(g);
  return g;
}

const grammar = loadGrammar();

/** Innermost scope of the first token covering `needle` on a one-line source. */
async function scopeOf(line: string, needle: string): Promise<string> {
  const { tokens } = (await grammar).tokenizeLine(line, INITIAL);
  const at = line.indexOf(needle);
  const tok = tokens.find((t) => t.startIndex <= at && at < t.endIndex)!;
  return tok.scopes[tok.scopes.length - 1];
}

test('builtins, including ?/! names', async () => {
  assert.equal(await scopeOf('x := contains? xs 1', 'contains?'), 'support.function.builtin.irij');
  assert.equal(await scopeOf('x := validate! s v', 'validate!'), 'support.function.builtin.irij');
  assert.notEqual(await scopeOf('x := head-of xs', 'head'), 'support.function.builtin.irij');
});

test('declarations', async () => {
  assert.equal(await scopeOf('fn empty-ish? xs', 'empty-ish?'), 'entity.name.function.irij');
  assert.equal(await scopeOf('cap fs-files :: FileIO = "x"', 'fs-files'), 'entity.name.function.handler.irij');
  assert.equal(await scopeOf('pub model bank :: "b.qnt" :pure', 'model'), 'keyword.declaration.model.irij');
  assert.equal(await scopeOf('pub model bank :: "b.qnt" :pure', 'bank'), 'entity.name.function.irij');
  assert.notEqual(await scopeOf('x := model', 'model'), 'keyword.declaration.model.irij');
});

test('strings, atoms, operators, interop', async () => {
  assert.equal(await scopeOf('s := "hi ${name}"', 'name'), 'source.irij.embedded');
  assert.equal(await scopeOf('x := :not_found', ':not_found'), 'constant.other.keyword-atom.irij');
  assert.equal(await scopeOf('x <- 1', '<-'), 'keyword.operator.assign.irij');
  assert.equal(await scopeOf('$A ~> $B', '~>'), 'keyword.operator.choreography.irij');
  assert.equal(await scopeOf('x := Math/sqrt 2.0', 'Math/sqrt'), 'support.function.interop.irij');
  assert.equal(await scopeOf('x := 1 ;; note', ';; note'), 'comment.line.double-semicolon.irij');
});

test('every example and stdlib file tokenizes', async () => {
  const g = await grammar;
  const dirs = ['../../examples', '../../src/main/resources/std'];
  let files = 0;
  for (const dir of dirs) {
    for (const f of readdirSync(dir).filter((n) => n.endsWith('.irj'))) {
      let state = INITIAL;
      for (const line of readFileSync(join(dir, f), 'utf8').split('\n')) {
        const r = g.tokenizeLine(line, state);
        assert.ok(!r.stoppedEarly, `${f}: tokenizer stopped early`);
        state = r.ruleStack;
      }
      files++;
    }
  }
  assert.ok(files > 20, `only ${files} files`);
});
