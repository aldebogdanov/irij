/** Line ranges of Irij top-level forms. Pure; no VS Code API, so it is
 *  unit-tested directly (test/forms.test.ts). */

export interface LineRange {
  /** First line, 0-based. */
  start: number;
  /** Last line, inclusive. */
  end: number;
}

/** A line that starts a top-level form: code in column 0, except an
 *  `else` continuing a top-level `if`. */
function isFormStart(line: string): boolean {
  return /^[^\s;]/.test(line) && !/^else(?![\w?!-])/.test(line);
}

function isBlankOrComment(line: string): boolean {
  return /^\s*(;;.*)?$/.test(line);
}

/**
 * The top-level form containing `line`: back to the nearest column-0
 * code line, forward to just before the next one, minus trailing blank
 * and comment lines. A body is indented, so this is the form the REPL
 * sees as one input. Returns undefined above the first form.
 */
export function topLevelFormAt(lines: readonly string[], line: number): LineRange | undefined {
  let start = Math.min(line, lines.length - 1);
  while (start >= 0 && !isFormStart(lines[start])) start--;
  if (start < 0) return undefined;
  let end = start + 1;
  while (end < lines.length && !isFormStart(lines[end])) end++;
  end--;
  while (end > start && isBlankOrComment(lines[end])) end--;
  return { start, end };
}

/** Text for the REPL: the code plus a blank line, which ends any open
 *  block (the REPL reads like Python's: indented lines continue). */
export function replInput(code: string): string {
  return code.replace(/\s+$/, '') + '\n';
}
