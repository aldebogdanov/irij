;;; irij-mode.el --- Major mode for the Irij programming language -*- lexical-binding: t; -*-

;; Copyright (C) 2024  Irij Contributors
;; Author: Irij Contributors
;; Version: 0.3.0
;; Keywords: languages irij
;; URL: https://github.com/aldebogdanov/irij
;; Package-Requires: ((emacs "27.1"))

;;; Commentary:
;;
;; Major mode for editing Irij (ℑ) source files (.irj).
;;
;; Features:
;;   • Syntax highlighting — keywords, type names, operators, literals, comments
;;   • Indentation for Irij's offside-rule blocks (TAB cycles valid levels)
;;   • Comment toggling with `;;`
;;   • imenu index of top-level declarations
;;   • REPL integration — send region or buffer to a running `irij` process
;;   • LSP — registers `irij lsp' with eglot and lsp-mode when they load
;;
;; Installation:
;;   (add-to-list 'load-path "/path/to/editors/emacs")
;;   (require 'irij-mode)
;;
;; Or with use-package:
;;   (use-package irij-mode
;;     :load-path "path/to/editors/emacs"
;;     :mode "\\.irj\\'"
;;     :hook (irij-mode . eglot-ensure))
;;
;; Keyword and builtin lists below are checked against the lexer and
;; the compiler's intrinsic set by EditorGrammarSyncTest — keep them
;; in sync with editors/textmate/irij.tmLanguage.json.

;;; Code:

(require 'comint)
(require 'subr-x)


;; ── Customisation group ────────────────────────────────────────────────

(defgroup irij nil
  "Support for the Irij programming language."
  :group 'languages
  :prefix "irij-")

(defcustom irij-indent-offset 2
  "Number of spaces per indentation level in Irij source files."
  :type 'integer
  :group 'irij)

(defcustom irij-executable "irij"
  "Path to the `irij` executable used for the REPL and the language server."
  :type 'string
  :group 'irij)

(defcustom irij-repl-buffer-name "*Irij REPL*"
  "Buffer name for the Irij REPL process."
  :type 'string
  :group 'irij)


;; ── Syntax table ──────────────────────────────────────────────────────

(defvar irij-mode-syntax-table
  (let ((st (make-syntax-table)))
    ;; `;;` line comments  (first `;` starts, second is comment-start-second)
    (modify-syntax-entry ?\; ". 12" st)
    (modify-syntax-entry ?\n ">" st)
    ;; String literals, with `\` escapes
    (modify-syntax-entry ?\" "\"" st)
    (modify-syntax-entry ?\\ "\\" st)
    ;; Operators — treat as punctuation
    (dolist (c '(?| ?< ?> ?@ ?# ?/ ?* ?+ ?= ?& ?~ ?. ?^ ?% ?:))
      (modify-syntax-entry c "." st))
    ;; Identifier characters beyond letters/digits: kebab-case `-`,
    ;; snake `_`, predicate/bang suffixes `?` `!`, party sigil `$`.
    ;; Symbol constituents, so `\\_<…\\_>' brackets a whole name.
    (dolist (c '(?- ?_ ?? ?! ?$))
      (modify-syntax-entry c "_" st))
    st)
  "Syntax table for `irij-mode'.")


;; ── Font-lock keywords ────────────────────────────────────────────────

(defconst irij-keywords
  '("fn" "do" "if" "else" "match" "spec" "newtype"
    "mod" "use" "pub" "with" "scope" "effect"
    "party" "cap" "handler" "impl" "proto"
    "pre" "post" "contract" "select"
    "enclave" "par-each" "on-failure"
    "in" "out" "for" "proof")
  "Reserved words in Irij.
`model' is not here: it is a soft keyword, highlighted only where a
declaration starts (see `irij-font-lock-keywords').")

(defconst irij-builtin-values
  '("true" "false")
  "Built-in value names in Irij.")

(defconst irij-builtin-fns
  '(;; constants
    "pi" "e"
    ;; console / debug
    "print" "println" "to-str" "dbg" "type-of" "error" "read-line"
    ;; numbers
    "abs" "min" "max" "quo" "rem" "sqrt" "floor" "ceil" "round"
    "sin" "cos" "tan" "log" "exp" "pow"
    "parse-int" "parse-float" "random-int" "random-float"
    ;; collections
    "head" "tail" "length" "reverse" "sort" "concat" "conj"
    "take" "drop" "to-vec" "to-set" "to-tuple" "nth" "last" "fold"
    "contains?" "empty?" "keys" "vals" "get" "assoc" "dissoc" "merge"
    ;; functions
    "identity" "const" "flip" "not"
    ;; strings
    "split" "join" "trim" "upper-case" "lower-case"
    "starts-with?" "ends-with?" "replace" "substring" "char-at" "index-of"
    "char-code" "from-char-code"
    ;; data formats
    "json-parse" "json-encode" "json-encode-pretty"
    "url-encode" "url-decode" "toml-parse"
    ;; crypto
    "sha256-hex" "hmac-sha256-hex" "pbkdf2-sha256-hex"
    "random-token" "constant-time-eq?"
    ;; concurrency
    "spawn" "sleep" "await" "timeout" "par" "race" "try"
    ;; environment
    "env" "get-env" "now-ms" "program-args"
    ;; specs
    "validate" "validate!"
    ;; implicit in handler clauses
    "resume")
  "Names an Irij file reaches without an import.
Mirrors `IntrinsicsEmitter.NAMES' plus the constants `pi' and `e'
and the handler-clause `resume'.")

(defun irij--face (face fallback)
  "Return FACE if it is defined in this Emacs, else FALLBACK."
  (if (facep face) face fallback))

(defconst irij--ident-re
  "[a-z][a-z0-9]*\\(?:[-_][a-z0-9]+\\)*[?!]?"
  "Regexp for an Irij lowercase identifier (lexer rule IDENT).")

(defconst irij-font-lock-keywords
  (let ((kw-re    (regexp-opt irij-keywords       'symbols))
        (bool-re  (regexp-opt irij-builtin-values 'symbols))
        (fn-re    (regexp-opt irij-builtin-fns    'symbols))
        (num-face (irij--face 'font-lock-number-face 'font-lock-constant-face))
        (esc-face (irij--face 'font-lock-escape-face 'font-lock-builtin-face))
        (op-face  (irij--face 'font-lock-operator-face 'font-lock-keyword-face)))
    `(
      ;; String escapes and ${…} interpolation (override string face)
      ("\\\\[nrte\"\\\\$]" 0 ',esc-face t)
      ("\\${[^}\n]*}" 0 'font-lock-preprocessor-face t)
      ;; `model' — soft keyword, only at the start of a declaration
      (,(concat "^[ \t]*\\(?:pub[ \t]+\\)?\\(model\\)[ \t]+\\(" irij--ident-re "\\)")
       (1 'font-lock-keyword-face) (2 'font-lock-function-name-face))
      ;; Declaration names — fn / handler / cap <ident>
      (,(concat "\\_<\\(?:fn\\|handler\\|cap\\)[ \t]+\\(" irij--ident-re "\\)")
       1 'font-lock-function-name-face)
      ;; Declaration names — spec / newtype / effect / proto / impl / contract <Name>
      ("\\_<\\(?:spec\\|newtype\\|effect\\|proto\\|impl\\|contract\\)[ \t]+\\([A-Z][A-Za-z0-9]*\\)"
       1 'font-lock-type-face)
      ;; Java interop — Class/member, java.pkg.Class/member
      ("\\_<[A-Za-z_][A-Za-z0-9_]*\\(?:\\.[A-Za-z_][A-Za-z0-9_]*\\)*/[A-Za-z_][A-Za-z0-9_]*"
       . 'font-lock-builtin-face)
      ;; Keywords
      (,kw-re    . 'font-lock-keyword-face)
      ;; Boolean literals
      (,bool-re  . 'font-lock-constant-face)
      ;; Built-in functions
      (,fn-re    . 'font-lock-builtin-face)
      ;; Type names — PascalCase
      ("\\_<[A-Z][A-Za-z0-9]*\\_>" . 'font-lock-type-face)
      ;; Party names — $ALLCAPS
      ("\\$[A-Z][A-Z0-9_-]*" . 'font-lock-type-face)
      ;; Keyword atoms — :ident (not ::, not attr:ident)
      ("\\(?:^\\|[^:[:alnum:]_-]\\)\\(:[a-z][a-z0-9_-]*\\)" 1 'font-lock-constant-face)
      ;; Structural operators — longest first
      (,(regexp-opt '(":::" "::" ":=" ":!" "<-" "->" "=>" "|>" "<|" ">>" "<<"
                      "~*>" "~>" "<~" "~/" "..." "..<" ".."))
       . 'font-lock-keyword-face)
      ;; Apply-to-rest: f ~ rest ≡ f(rest)
      ("\\(?:^\\|[^~<]\\)\\(~\\)\\(?:[^>*/]\\|$\\)" 1 'font-lock-keyword-face)
      ;; Seq operators: /+ /* /# /& /| /? /! /^ /$ @ @i
      ("/[+*#&|!?^$]\\|@i?" . 'font-lock-builtin-face)
      ;; Collection openers #[ #{ #(
      ("#[[{(]" . ',op-face)
      ;; Numeric literals (hex, rational, float, int)
      ("\\_<0[xX][0-9A-Fa-f]\\(?:[0-9A-Fa-f_]*[0-9A-Fa-f]\\)?\\_>" . ',num-face)
      ("\\_<[0-9]+/[0-9]+\\_>"                                     . ',num-face)
      ("\\_<[0-9][0-9_]*\\.[0-9][0-9_]*\\_>"                       . ',num-face)
      ("\\_<[0-9]\\(?:[0-9_]*[0-9]\\)?\\_>"                        . ',num-face)
      ;; Binding names — name :=   name :!
      (,(concat "\\_<\\(" irij--ident-re "\\)[ \t]*:[=!]")
       1 'font-lock-variable-name-face)
      ))
  "Font-lock keywords for `irij-mode'.")


;; ── Indentation ───────────────────────────────────────────────────────
;;
;; Irij blocks follow the offside rule: a header line (`fn f`, `if c`,
;; `match x`, `pat =>`, …) is followed by a body indented one level.
;; Where a body ends cannot be inferred, so the computed column is the
;; *deepest* valid one; TAB pressed repeatedly cycles down through the
;; shallower levels, and `indent-region' keeps an existing dedent.

(defconst irij--block-head-re
  (concat
   "\\`[ \t]*\\(?:pub[ \t]+\\)?"
   "\\(?:\\(?:" irij--ident-re "\\)[ \t]*\\(?::=\\|:!\\|<-\\)[ \t]*\\)?"
   "\\(fn\\|if\\|else\\|match\\|with\\|scope\\(?:\\.[a-z-]+\\)?\\|do\\|effect\\|handler"
   "\\|spec\\|proto\\|impl\\|model\\|select\\|enclave\\|par-each\\|on-failure\\|for\\|contract\\)"
   "\\_>")
  "Regexp for a logical line that may open an indented block.
Group 1 is the block keyword.")

(defun irij--strip-comment (line)
  "Return LINE without a trailing `;;' comment (strings respected)."
  (let ((i 0) (len (length line)) (in-str nil) (cut nil))
    (while (and (< i len) (not cut))
      (let ((c (aref line i)))
        (cond
         (in-str (cond ((eq c ?\\) (setq i (1+ i)))
                       ((eq c ?\") (setq in-str nil))))
         ((eq c ?\") (setq in-str t))
         ((and (eq c ?\;) (< (1+ i) len) (eq (aref line (1+ i)) ?\;))
          (setq cut i))))
      (setq i (1+ i)))
    (string-trim-right (if cut (substring line 0 cut) line))))

(defun irij--line-text ()
  "Current line's code, without comment or trailing whitespace."
  (irij--strip-comment
   (buffer-substring-no-properties (line-beginning-position) (line-end-position))))

(defun irij--blank-or-comment-line-p ()
  "Non-nil if the current line is blank or only a comment."
  (save-excursion
    (beginning-of-line)
    (looking-at-p "[ \t]*\\(?:;;.*\\)?$")))

(defun irij--prev-code-line ()
  "Move to the previous non-blank, non-comment line; return point or nil."
  (let ((found nil))
    (while (and (not found) (= 0 (forward-line -1)))
      (unless (irij--blank-or-comment-line-p)
        (setq found (point))))
    found))

(defun irij--logical-line-start (depth)
  "Move to the line where the logical line containing point begins.
Climbs out of brackets opened deeper than DEPTH and over `\\'
continuations.  Returns point at that line's beginning."
  (beginning-of-line)
  (let ((moved t))
    (while moved
      (setq moved nil)
      (let ((ppss (syntax-ppss)))
        (when (> (nth 0 ppss) depth)
          ;; Outermost opener deeper than DEPTH
          (goto-char (nth depth (nth 9 ppss)))
          (beginning-of-line)
          (setq moved t)))
      (when (save-excursion
              (and (= 0 (forward-line -1))
                   (looking-at-p ".*\\\\[ \t]*$")))
        (forward-line -1)
        (setq moved t))))
  (point))

(defun irij--opens-block-p (logical last &optional required)
  "Non-nil if a block follows a logical line.
LOGICAL is the logical line's text from its first line to the end of
LAST, its final physical line (both without comments).  With REQUIRED,
only blocks that must have a body count — not `fn', `effect',
`handler' or `model', whose bodies are optional."
  (or
   ;; Arm / lambda / binding / assignment left open at end of line —
   ;; but not a leading `=> params' imperative-body marker, whose body
   ;; stays at the marker's level
   (and (string-match-p "\\(?:=>\\|->\\|:=\\|:!\\|<-\\|=\\)\\'" last)
        (not (string-match-p "\\`[ \t]*=>" last)))
   (and (string-match irij--block-head-re logical)
        (let ((kw   (match-string 1 logical))
              (rest (substring logical (match-end 0))))
          (pcase kw
            ((guard (and required (member kw '("fn" "effect" "handler" "model")))) nil)
            ;; Inline `if c a else b' is an expression, not a block
            ("if"   (not (string-match-p "\\_<else\\_>" rest)))
            ;; `else' alone, or `else if c' without its own else
            ("else" (or (string-match-p "\\`[ \t]*\\'" rest)
                        (and (string-match-p "\\`[ \t]*if\\_>" rest)
                             (not (string-match-p "\\_<else\\_>" rest)))))
            ;; `fn f (x -> …)' carries its body inline
            ("fn"   (not (irij--inline-lambda-body-p rest)))
            ;; `with h do (…)' and `do (a) (b)' are inline
            ("with" (not (string-match-p "\\_<do\\_>" rest)))
            ("do"   (string-match-p "\\`[ \t]*\\'" rest))
            ;; `spec :: T' annotation, not a declaration
            ("spec" (not (string-match-p "\\`[ \t]*::" rest)))
            (_ t))))))

(defun irij--inline-lambda-body-p (rest)
  "Non-nil if REST, the text after `fn', ends with a lambda body.
A trailing `(params -> …)' group; with a `::' spec on the line, the
group must start with a lowercase parameter so `(_ -> Bool)' or
`(Int -> Int)' spec types do not count."
  (with-temp-buffer
    (set-syntax-table irij-mode-syntax-table)
    (insert rest)
    (and (eq (char-before) ?\))
         (condition-case nil
             (progn (backward-sexp)
                    (looking-at-p (if (string-match-p "::" rest)
                                      "([ \t]*[a-z][^()]*->"
                                    "([^()]*->")))
           (scan-error nil)))))

(defconst irij--leading-infix-re
  "\\`[ \t]*\\(?:|>\\|<|\\|>>\\|<<\\|\\+\\+\\|&&\\|||\\|==\\|/=\\|[-+*/%]\\s-\\)"
  "Regexp for a line that continues the previous one with an infix operator.")

(defun irij--block-if-line-p (text)
  "Non-nil if TEXT is a line that opens an `if' block (no inline else)."
  (and (string-match irij--block-head-re text)
       (equal (match-string 1 text) "if")
       (not (string-match-p "\\_<else\\_>" (substring text (match-end 0))))))

(defun irij--else-indent ()
  "Column of the `if' that an `else' on the current line closes, or nil.
Walks back along the indentation frontier: the nearest block `if' not
already closed by an `else' at its own column."
  (save-excursion
    (let ((frontier most-positive-fixnum) (closed nil) (col nil))
      (while (and (not col) (> frontier 0) (irij--prev-code-line))
        (let ((ind (current-indentation)) (text (irij--line-text)))
          (when (<= ind frontier)
            (cond
             ((string-match-p "\\`[ \t]*else\\_>" text) (push ind closed))
             ((irij--block-if-line-p text)
              (if (memq ind closed)
                  (setq closed (delq ind closed))
                (setq col ind))))
            (setq frontier ind))))
      col)))

(defun irij--compute-indent ()
  "Return the deepest valid indentation column for the current line."
  (save-excursion
    (beginning-of-line)
    (let* ((ppss      (syntax-ppss))
           (depth     (nth 0 ppss))
           (opener    (nth 1 ppss))
           (this-line (irij--line-text)))
      (cond
       ;; Inside a string: leave alone
       ((nth 3 ppss) (current-indentation))
       ;; Line starts with a closing bracket: line of its opener
       ((and opener (string-match-p "\\`[ \t]*[])}]" this-line))
        (save-excursion (goto-char opener) (current-indentation)))
       ;; `else' aligns with its `if'
       ((and (string-match-p "\\`[ \t]*else\\_>" this-line)
             (irij--else-indent)))
       ;; `|> f' / `++ x' continues the expression above
       ((and (string-match-p irij--leading-infix-re this-line)
             (let ((prev (save-excursion (irij--prev-code-line))))
               (and prev (not (and opener (>= opener prev))))))
        (save-excursion
          (irij--prev-code-line)
          (irij--logical-line-start depth)
          ;; Siblings align; the first continuation goes one level in
          (if (string-match-p irij--leading-infix-re (irij--line-text))
              (current-indentation)
            (+ (current-indentation) irij-indent-offset))))
       (t
        (let ((prev (save-excursion (irij--prev-code-line))))
          (cond
           ((not prev) 0)
           ;; First line after a bracket opened on the previous line
           ((and opener (>= opener prev)) (irij--indent-after-opener opener))
           (t
            (goto-char prev)
            (let* ((last    (irij--line-text))
                   (start   (irij--logical-line-start depth))
                   (base    (current-indentation))
                   (logical (irij--strip-comment
                             (buffer-substring-no-properties
                              start (save-excursion (goto-char prev)
                                                    (line-end-position))))))
              (cond
               ((irij--opens-block-p logical last) (+ base irij-indent-offset))
               ;; `\' continuation
               ((string-match-p "\\\\\\'" last) (+ base irij-indent-offset))
               (t base)))))))))))

(defun irij--indent-after-opener (opener)
  "Indent column for the first line after the bracket at OPENER."
  (save-excursion
    (goto-char opener)
    (let ((after (irij--strip-comment
                  (buffer-substring-no-properties (1+ opener) (line-end-position))))
          (col   (current-column)))
      (cond
       ;; Nothing after the opener, or a lambda head `(x ->'
       ((or (string-match-p "\\`[ \t]*\\'" after)
            (string-match-p "\\(?:->\\|=>\\)\\'" after))
        (+ (progn (irij--logical-line-start (nth 0 (syntax-ppss)))
                  (current-indentation))
           irij-indent-offset))
       ;; Align with the first element after the opener
       (t (1+ col))))))

(defun irij--indent-to (col)
  "Indent the current line to COL, keeping point relative to the text."
  (let ((pos (- (point-max) (point))))
    (beginning-of-line)
    (skip-chars-forward " \t")
    (unless (= (current-column) col)
      (delete-region (line-beginning-position) (point))
      (indent-to col))
    (when (> (- (point-max) pos) (point))
      (goto-char (- (point-max) pos)))))

(defun irij-indent-line ()
  "Indent the current Irij line.
The first TAB moves to the deepest valid column; repeated TABs cycle
one level shallower each time, wrapping back to the deepest."
  (interactive)
  (let ((target (irij--compute-indent))
        (cur    (current-indentation)))
    (when (and (eq this-command last-command)
               (memq this-command '(indent-for-tab-command irij-indent-line)))
      (setq target (if (> cur 0) (max 0 (- cur irij-indent-offset)) target)))
    (irij--indent-to target)))

(defun irij--after-block-head-p ()
  "Non-nil if the code line above the current one requires a block body."
  (save-excursion
    (let ((depth (nth 0 (syntax-ppss (line-beginning-position)))))
      (when (irij--prev-code-line)
        (let* ((last  (irij--line-text))
               (eol   (line-end-position))
               (start (irij--logical-line-start depth)))
          (irij--opens-block-p
           (irij--strip-comment (buffer-substring-no-properties start eol))
           last t))))))

(defun irij--indentation-valid-p (cur target)
  "Non-nil if column CUR is a defensible indentation for the current line.
TARGET is the computed (deepest block) column.  A shallower multiple of
the offset closes blocks; a deeper column continues the expression
above or hand-aligns inside brackets.  Only a body left at or left of
its block head, or a dedent off the offset grid, is wrong."
  (or (= cur target)
      (and (< cur target)
           (= 0 (% cur irij-indent-offset))
           (not (irij--after-block-head-p)))
      (> cur target)))

(defun irij-indent-region (beg end)
  "Re-indent lines between BEG and END whose indentation is wrong.
Indentation is semantic in Irij and a block's end cannot be inferred,
so a line keeps its column when `irij--indentation-valid-p' accepts it;
only clearly wrong lines move to the computed column."
  (save-excursion
    (goto-char beg)
    (beginning-of-line)
    (let ((end-marker (copy-marker end)))
      (while (< (point) end-marker)
        (unless (irij--blank-or-comment-line-p)
          (let ((target (irij--compute-indent))
                (cur    (current-indentation)))
            (unless (irij--indentation-valid-p cur target)
              (irij--indent-to target))))
        (forward-line 1))
      (set-marker end-marker nil))))


;; ── imenu ─────────────────────────────────────────────────────────────

(defvar irij-imenu-generic-expression
  `(("Functions" ,(concat "^\\(?:pub[ \t]+\\)?fn[ \t]+\\(" irij--ident-re "\\)") 1)
    ("Specs"     "^\\(?:pub[ \t]+\\)?\\(?:spec\\|newtype\\)[ \t]+\\([A-Z][A-Za-z0-9]*\\)" 1)
    ("Effects"   "^\\(?:pub[ \t]+\\)?\\(?:effect\\|proto\\)[ \t]+\\([A-Z][A-Za-z0-9]*\\)" 1)
    ("Impls"     "^impl[ \t]+\\([A-Z][A-Za-z0-9]*[ \t]+for[ \t]+[A-Z][A-Za-z0-9]*\\)" 1)
    ("Handlers"  ,(concat "^\\(?:pub[ \t]+\\)?\\(?:handler\\|cap\\|model\\)[ \t]+\\("
                          irij--ident-re "\\)")
     1))
  "`imenu' index of top-level Irij declarations.")


;; ── Comment toggling ──────────────────────────────────────────────────

(defun irij-toggle-comment-region (beg end)
  "Toggle `;;` comments on each line in region BEG to END."
  (interactive "r")
  (comment-or-uncomment-region beg end))


;; ── REPL integration ─────────────────────────────────────────────────

(defun irij-repl ()
  "Start (or switch to) the Irij REPL."
  (interactive)
  (let ((buf (get-buffer irij-repl-buffer-name)))
    (if (and buf (comint-check-proc buf))
        (pop-to-buffer buf)
      (setq buf (make-comint-in-buffer
                 "Irij REPL"
                 irij-repl-buffer-name
                 irij-executable))
      (with-current-buffer buf
        (irij-repl-mode))
      (pop-to-buffer buf))))

(defun irij-send-region (beg end)
  "Send region BEG to END to the Irij REPL."
  (interactive "r")
  (irij--ensure-repl)
  (comint-send-string irij-repl-buffer-name
                      (buffer-substring-no-properties beg end))
  (comint-send-string irij-repl-buffer-name "\n"))

(defun irij-send-buffer ()
  "Send the entire current buffer to the Irij REPL."
  (interactive)
  (irij-send-region (point-min) (point-max)))

(defun irij-send-line ()
  "Send the current line to the Irij REPL."
  (interactive)
  (irij--ensure-repl)
  (comint-send-string irij-repl-buffer-name
                      (buffer-substring-no-properties
                       (line-beginning-position) (line-end-position)))
  (comint-send-string irij-repl-buffer-name "\n"))

(defun irij--ensure-repl ()
  "Start the REPL if not already running."
  (unless (comint-check-proc irij-repl-buffer-name)
    (irij-repl)))


;; ── REPL mode ─────────────────────────────────────────────────────────

(define-derived-mode irij-repl-mode comint-mode "Irij REPL"
  "Major mode for the Irij REPL buffer."
  :syntax-table irij-mode-syntax-table
  (setq-local font-lock-defaults '(irij-font-lock-keywords))
  ;; Primary prompt `ℑ> ', continuation prompt ` | ' (IrijRepl.java)
  (setq comint-prompt-regexp "^ℑ> \\|^ | "))


;; ── Language server ───────────────────────────────────────────────────

(defun irij--lsp-command (&rest _)
  "Command line that starts the Irij language server."
  (list irij-executable "lsp"))

(with-eval-after-load 'eglot
  (defvar eglot-server-programs)
  (add-to-list 'eglot-server-programs '(irij-mode . irij--lsp-command)))

(declare-function lsp-register-client "lsp-mode" (client))
(declare-function make-lsp-client "lsp-mode" (&rest args))
(declare-function lsp-stdio-connection "lsp-mode" (command &optional test-command))
(declare-function lsp-activate-on "lsp-mode" (&rest languages))
(with-eval-after-load 'lsp-mode
  (defvar lsp-language-id-configuration)
  (add-to-list 'lsp-language-id-configuration '(irij-mode . "irij"))
  (lsp-register-client
   (make-lsp-client :new-connection (lsp-stdio-connection #'irij--lsp-command)
                    :activation-fn (lsp-activate-on "irij")
                    :server-id 'irij-lsp)))


;; ── Keymap ────────────────────────────────────────────────────────────

(defvar irij-mode-map
  (let ((map (make-sparse-keymap)))
    ;; Comint subprocess REPL (always available)
    (define-key map (kbd "C-c C-z") #'irij-repl)
    (define-key map (kbd "C-c C-r") #'irij-send-region)
    (define-key map (kbd "C-c C-b") #'irij-send-buffer)
    (define-key map (kbd "C-c C-l") #'irij-send-line)
    (define-key map (kbd "C-c C-c") #'irij-toggle-comment-region)
    ;; nREPL client (available when irij-nrepl.el is loaded)
    (define-key map (kbd "C-c C-n") #'irij-nrepl-connect)
    (define-key map (kbd "C-x C-e") #'irij-nrepl-eval-last-sexp)
    (define-key map (kbd "C-c C-e") #'irij-nrepl-eval-last-sexp)
    (define-key map (kbd "C-c C-d") #'irij-nrepl-eval-defun)
    (define-key map (kbd "C-c C-k") #'irij-nrepl-eval-buffer)
    (define-key map (kbd "C-c M-r") #'irij-nrepl-eval-region)
    (define-key map (kbd "C-c C-o") #'irij-nrepl-show-result-buffer)
    map)
  "Keymap for `irij-mode'.")


;; ── Major mode definition ─────────────────────────────────────────────

;;;###autoload
(define-derived-mode irij-mode prog-mode "Irij"
  "Major mode for editing Irij (ℑ) source files.

Indentation: TAB indents to the deepest valid column; repeat TAB to
cycle to shallower block levels.

Comint subprocess REPL:
  \\[irij-repl]              Start / switch to REPL
  \\[irij-send-region]       Send region to REPL
  \\[irij-send-buffer]       Send buffer to REPL
  \\[irij-send-line]         Send current line to REPL
  \\[irij-toggle-comment-region]  Toggle `;;` comments

nREPL client (requires `irij --nrepl-server` running):
  \\[irij-nrepl-connect]            Connect to nREPL server
  \\[irij-nrepl-eval-last-sexp]     Eval expression at point
  \\[irij-nrepl-eval-defun]         Eval top-level declaration
  \\[irij-nrepl-eval-buffer]        Eval entire buffer
  \\[irij-nrepl-eval-region]        Eval selected region
  \\[irij-nrepl-show-result-buffer] Show nREPL output buffer

Language server: `eglot' and `lsp-mode' start `irij lsp' for this
mode once they are loaded, e.g. via (add-hook \\='irij-mode-hook
#\\='eglot-ensure)."
  :syntax-table irij-mode-syntax-table
  (setq-local font-lock-defaults         '(irij-font-lock-keywords))
  (setq-local indent-line-function       #'irij-indent-line)
  (setq-local indent-region-function     #'irij-indent-region)
  (setq-local comment-start             ";; ")
  (setq-local comment-end               "")
  (setq-local comment-start-skip        ";;+\\s-*")
  (setq-local tab-width                 irij-indent-offset)
  (setq-local indent-tabs-mode          nil)
  (setq-local electric-indent-inhibit   t)
  (setq-local imenu-generic-expression  irij-imenu-generic-expression)
  (setq-local imenu-case-fold-search    nil))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.irj\\'" . irij-mode))

;; Load the nREPL client if it is on the load-path (no error if absent).
(require 'irij-nrepl nil t)
;; Expand/shrink region (C-c C-= / C-c C--), likewise optional.
(require 'irij-selection nil t)

(provide 'irij-mode)

;;; irij-mode.el ends here
