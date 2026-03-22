;;; irij-mode.el --- Major mode for the Irij programming language -*- lexical-binding: t; -*-

;; Copyright (C) 2024  Irij Contributors
;; Author: Irij Contributors
;; Version: 0.1.0
;; Keywords: languages irij
;; URL: https://github.com/irij-lang/irij
;; Package-Requires: ((emacs "27.1"))

;;; Commentary:
;;
;; Major mode for editing Irij (ℑ) source files (.irj).
;;
;; Features:
;;   • Syntax highlighting — keywords, type names, operators, literals, comments
;;   • 2-space indentation enforcement
;;   • Comment toggling with `;;`
;;   • REPL integration — send region or buffer to a running `irij` process
;;
;; Installation:
;;   (add-to-list 'load-path "/path/to/editors/emacs")
;;   (require 'irij-mode)
;;
;; Or with use-package:
;;   (use-package irij-mode
;;     :load-path "path/to/editors/emacs"
;;     :mode "\\.irj\\'")

;;; Code:

(require 'comint)


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
  "Path to the `irij` executable used for the REPL."
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
    ;; String literals
    (modify-syntax-entry ?\" "\"" st)
    ;; Operators — treat as punctuation
    (dolist (c '(?| ?< ?> ?! ?@ ?# ?/ ?* ?+ ?- ?= ?& ?~ ?. ?^ ?%))
      (modify-syntax-entry c "." st))
    ;; Underscore is part of identifiers
    (modify-syntax-entry ?_ "w" st)
    ;; Hyphens are part of identifiers (kebab-case)
    (modify-syntax-entry ?- "w" st)
    ;; $VAR / $ROLE names
    (modify-syntax-entry ?$ "w" st)
    st)
  "Syntax table for `irij-mode'.")


;; ── Font-lock keywords ────────────────────────────────────────────────

(defconst irij-keywords
  '("fn" "do" "if" "else" "match" "type" "newtype"
    "mod" "use" "pub" "with" "scope" "effect"
    "role" "cap" "handler" "impl" "proto"
    "pre" "post" "law" "contract" "select"
    "enclave" "forall" "par-each" "on-failure"
    "in" "out")
  "Reserved words in Irij.")

(defconst irij-builtin-values
  '("true" "false")
  "Built-in value names in Irij.")

(defconst irij-builtin-fns
  '("print" "println" "to-str" "dbg"
    "div" "abs" "min" "max"
    "head" "tail" "length" "reverse" "sort"
    "concat" "take" "drop" "to-vec"
    "contains?" "keys" "vals" "get"
    "nth" "last" "fold"
    "identity" "const" "not" "empty?"
    "spawn" "sleep" "try" "apply"
    "await" "timeout" "par" "race" "verify-laws"
    "error" "type-of"
    "assoc" "dissoc" "merge"
    "split" "join" "trim" "upper-case" "lower-case"
    "starts-with?" "ends-with?" "replace" "substring" "char-at" "index-of"
    "sqrt" "floor" "ceil" "round" "sin" "cos" "tan" "log" "exp" "pow"
    "random-int" "random-float"
    "parse-int" "parse-float" "char-code" "from-char-code"
    "read-file" "write-file" "file-exists?" "get-env" "now-ms")
  "Built-in functions in Irij.")

;; Emacs 29+ introduced `font-lock-number-face'.  On older versions
;; the symbol doesn't exist (neither as face nor variable), which
;; causes "Symbol's value as variable is void" when font-lock tries
;; to use it.  We define it as a variable pointing to the real face
;; so that font-lock can resolve it in all Emacs versions.
(unless (boundp 'font-lock-number-face)
  (defvar font-lock-number-face 'font-lock-constant-face
    "Compatibility shim: points to `font-lock-constant-face' on Emacs < 29."))

(defconst irij-font-lock-keywords
  (let ((kw-re    (regexp-opt irij-keywords      'words))
        (bool-re  (regexp-opt irij-builtin-values 'words))
        (fn-re    (regexp-opt irij-builtin-fns    'words)))
    `(
      ;; Keywords
      (,kw-re    . font-lock-keyword-face)
      ;; Boolean literals
      (,bool-re  . font-lock-constant-face)
      ;; Built-in functions
      (,fn-re    . font-lock-builtin-face)
      ;; Type names — PascalCase
      ("\\<[A-Z][A-Za-z0-9]*\\>" . font-lock-type-face)
      ;; Role names — $ALLCAPS
      ("\\$[A-Z][A-Z0-9_]*" . font-lock-type-face)
      ;; Keyword atoms — :ident (not ::)
      ("\\(?:^\\|[^:]\\)\\(:[a-z][a-z0-9-]*\\)" 1 font-lock-constant-face)
      ;; Type annotations — :: Type
      ("::" . font-lock-keyword-face)
      ;; Bind operators
      (":=" . font-lock-keyword-face)
      (":!" . font-lock-keyword-face)
      ;; Arrow operators
      ("->" . font-lock-keyword-face)
      ("=>" . font-lock-keyword-face)
      ("|>" . font-lock-keyword-face)
      ("<|" . font-lock-keyword-face)
      (">>" . font-lock-keyword-face)
      ("<<"  . font-lock-keyword-face)
      ;; Apply-to-rest (lowest precedence apply): f ~ rest ≡ f(rest)
      ("\\(?:^\\|[^~<]\\)\\(~\\)\\(?:[^>*/]\\|$\\)" 1 font-lock-keyword-face)
      ;; Seq operators: /+ /* /# /& /| /? /! /^ /$ @ @i
      ("\\(?:/[+*#&|!?^$]\\|@i?\\)" . font-lock-builtin-face)
      ;; Numeric literals (int, float, hex, rational)
      ("\\<\\([0-9][0-9_]*\\(?:\\.[0-9][0-9_]*\\)?\\(?:[eE][+-]?[0-9]+\\)?\\)\\>" 1 font-lock-number-face)
      ("\\<0x[0-9A-Fa-f][0-9A-Fa-f_]*\\>" . font-lock-number-face)
      ("\\<[0-9]+/[0-9]+\\>"               . font-lock-number-face)
      ;; String interpolation markers  ${…}
      ("\\${[^}]*}" . font-lock-preprocessor-face)
      ;; Function definitions — fn <name>
      ("\\bfn\\s-+\\([a-z][a-z0-9-]*\\)" 1 font-lock-function-name-face)
      ;; Type definitions — type/newtype <Name>
      ("\\b\\(?:type\\|newtype\\)\\s-+\\([A-Z][A-Za-z0-9]*\\)" 1 font-lock-type-face)
      ;; Binding names — name :=   name :!
      ("\\([a-z][a-z0-9-]*\\)\\s-*:\\(?:=\\|!\\)" 1 font-lock-variable-name-face)
      ))
  "Font-lock keywords for `irij-mode'.")


;; ── Indentation ───────────────────────────────────────────────────────

(defun irij-indent-line ()
  "Indent the current Irij line.
Uses a simple heuristic: increase indent after lines ending in a
context-opening construct (fn/match/if/else/with/do/type bodies),
and decrease after a line that is less indented than its predecessor."
  (interactive)
  (let* ((pos        (- (point-max) (point)))
         (indent-col (irij--compute-indent)))
    (beginning-of-line)
    (skip-chars-forward " \t")
    (unless (= (current-column) indent-col)
      (delete-region (line-beginning-position) (point))
      (indent-to indent-col))
    ;; Restore cursor position
    (when (> (- (point-max) pos) (point))
      (goto-char (- (point-max) pos)))))

(defun irij--compute-indent ()
  "Return the column to indent the current line to."
  (save-excursion
    ;; Find the previous non-blank, non-comment line
    (beginning-of-line)
    (let ((current-blank (looking-at "^[ \t]*$")))
      (forward-line -1)
      (while (and (not (bobp))
                  (or (looking-at "^[ \t]*$")
                      (looking-at "^[ \t]*;;")))
        (forward-line -1))
      (let* ((prev-indent (current-indentation))
             (prev-line   (buffer-substring (line-beginning-position)
                                            (line-end-position)))
             ;; Does the previous line open a new block?
             (opens-block (irij--line-opens-block prev-line)))
        (cond
         (current-blank 0)
         (opens-block   (+ prev-indent irij-indent-offset))
         (t             prev-indent))))))

(defun irij--line-opens-block (line)
  "Return non-nil if LINE ends with a block-opening construct."
  (string-match-p
   (concat
    "\\(?:"
    ;; fn / type / newtype declarations without inline body
    "\\bfn\\b.*[^=>]$"
    "\\|\\btype\\b.*$"
    "\\|\\bnewtype\\b.*$"
    ;; match / if / else / with / do end of line
    "\\|\\b\\(?:match\\|if\\|else\\|with\\|do\\|scope\\|handler\\|effect\\|impl\\|proto\\)\\b.*$"
    ;; Arrow at end of line:  =>  or  ->
    "\\|=>[ \t]*$"
    "\\|-[\\[>].*$"
    "\\)")
   (string-trim-right line)))


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
  (setq comint-prompt-regexp "^ℑ> \\|^  | "))


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
  \\[irij-nrepl-show-result-buffer] Show nREPL output buffer"
  :syntax-table irij-mode-syntax-table
  (setq-local font-lock-defaults         '(irij-font-lock-keywords))
  (setq-local indent-line-function       #'irij-indent-line)
  (setq-local comment-start             ";; ")
  (setq-local comment-end               "")
  (setq-local comment-start-skip        ";;+\\s-*")
  (setq-local tab-width                 irij-indent-offset)
  (setq-local indent-tabs-mode          nil)
  (setq-local electric-indent-inhibit   t)
  (font-lock-ensure))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.irj\\'" . irij-mode))

;; Load the nREPL client if it is on the load-path (no error if absent).
(require 'irij-nrepl nil t)

(provide 'irij-mode)

;;; irij-mode.el ends here
