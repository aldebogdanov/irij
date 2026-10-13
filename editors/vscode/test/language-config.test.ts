import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// npm runs tests from the package root
const conf = JSON.parse(readFileSync('language-configuration.json', 'utf8'));
const increase = new RegExp(conf.indentationRules.increaseIndentPattern);
const decrease = new RegExp(conf.indentationRules.decreaseIndentPattern);
const word = new RegExp(`^(?:${conf.wordPattern})$`);

test('block heads indent the next line', () => {
  for (const line of [
    'fn square', 'pub fn all? :: Fn Vec Bool', 'handler h :: Eff', 'impl Show for Int',
    'effect Ask', 'spec Color', '  if c', '  else', '  else if c', '  match x',
    '  r := match x', '  with mock-fs', '  scope s', '  scope.race s', '  do',
    '  n =>', '  (n ->', 'x :=', '  xs <-', 'model m :: "b.qnt" :pure',
  ]) assert.match(line, increase, line);
});

test('inline forms do not indent the next line', () => {
  for (const line of [
    '  y := if (a > b) a else b', 'r := with fs.default-fs do (f 1)', 'r := do (a) (b)',
    '  spec :: Int', 'use std.list', 'do-stuff 1', 'x := 1', '  =>', '  => tag',
    'cap db :: Db = "dev.Db"', 'iffy := 2', 'matcher x', 'fn-name := 1',
  ]) assert.doesNotMatch(line, increase, line);
});

test('else dedents', () => {
  assert.match('  else', decrease);
  assert.doesNotMatch('  elsewhere', decrease);
});

test('words include kebab-case and ?/! suffixes', () => {
  for (const w of ['contains?', 'validate!', 'fs-exists?', 'snake_case', '$BUYER', '1_000', '3.14'])
    assert.match(w, word, w);
});
