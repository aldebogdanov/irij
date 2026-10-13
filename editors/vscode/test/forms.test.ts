import { test } from 'node:test';
import assert from 'node:assert/strict';
import { replInput, topLevelFormAt } from '../src/forms';

const src = [
  ';; header comment',       // 0
  'fn square',               // 1
  '  (n -> n * n)',          // 2
  '',                        // 3
  ';; doc for fact',         // 4
  'fn fact',                 // 5
  '  0 => 1',                // 6
  '  n => n * fact (n - 1)', // 7
  '',                        // 8
  'if debug?',               // 9
  '  println "a"',           // 10
  'else',                    // 11
  '  println "b"',           // 12
  'x := 1',                  // 13
];

test('form from its header line', () => {
  assert.deepEqual(topLevelFormAt(src, 1), { start: 1, end: 2 });
});

test('form from a body line, without trailing blanks or the next doc comment', () => {
  assert.deepEqual(topLevelFormAt(src, 2), { start: 1, end: 2 });
  assert.deepEqual(topLevelFormAt(src, 4), { start: 1, end: 2 });
  assert.deepEqual(topLevelFormAt(src, 7), { start: 5, end: 7 });
});

test('column-0 else continues the if', () => {
  assert.deepEqual(topLevelFormAt(src, 12), { start: 9, end: 12 });
  assert.deepEqual(topLevelFormAt(src, 11), { start: 9, end: 12 });
});

test('one-line form at end of file', () => {
  assert.deepEqual(topLevelFormAt(src, 13), { start: 13, end: 13 });
});

test('nothing above the first form', () => {
  assert.equal(topLevelFormAt(src, 0), undefined);
});

test('REPL input ends in exactly one newline (sendText adds the blank line)', () => {
  assert.equal(replInput('fn f\n  (x -> x)\n\n  '), 'fn f\n  (x -> x)\n');
});
