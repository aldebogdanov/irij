;;; irij-mode.el --- Major mode for editing Irij files -*- lexical-binding: t; -*-

;; Author: Irij Contributors
;; Version: 0.1.0
;; Keywords: languages
;; URL: https://github.com/irij-lang/irij

;;; Commentary:

;; Provides syntax highlighting, indentation, and basic editor
;; integration for the Irij programming language (.irj files).

;;; Code:

(defgroup irij nil
  "Major mode for editing Irij code."
  :prefix "irij-"
  :group 'languages)

(defcustom irij-indent-offset 2
  "Number of spaces per indentation level in Irij mode."
  :type 'integer
  :group 'irij)

;; ─── Syntax Table ────────────────────────────────────────────────────

(defvar irij-mode-syntax-table
  (let ((st (make-syntax-table)))
    ;; ;; starts a comment to end of line
    ;; First ; is the comment starter (first char), second ; is comment starter (second char)
    (modify-syntax-entry ?\; ". 12" st)
    (modify-syntax-entry ?\n ">" st)
    ;; Strings
    (modify-syntax-entry ?\" "\"" st)
    ;; Bracket pairs
    (modify-syntax-entry ?\( "()" st)
    (modify-syntax-entry ?\) ")(" st)
    (modify-syntax-entry ?\[ "(]" st)
    (modify-syntax-entry ?\] ")[" st)
    (modify-syntax-entry ?\{ "(}" st)
    (modify-syntax-entry ?\} "){" st)
    ;; Symbol constituents (allowed in identifiers/operators)
    (modify-syntax-entry ?- "_" st)
    (modify-syntax-entry ?! "_" st)
    (modify-syntax-entry ?? "_" st)
    (modify-syntax-entry ?> "_" st)
    (modify-syntax-entry ?< "_" st)
    (modify-syntax-entry ?= "_" st)
    (modify-syntax-entry ?| "_" st)
    (modify-syntax-entry ?+ "_" st)
    (modify-syntax-entry ?* "_" st)
    (modify-syntax-entry ?/ "_" st)
    (modify-syntax-entry ?& "_" st)
    (modify-syntax-entry ?~ "_" st)
    (modify-syntax-entry ?. "_" st)
    (modify-syntax-entry ?@ "_" st)
    (modify-syntax-entry ?\\ "_" st)
    st)
  "Syntax table for `irij-mode'.")

;; ─── Font Lock ───────────────────────────────────────────────────────

(defvar irij-keywords
  '("fn" "do" "if" "then" "else" "match" "type" "newtype"
    "mod" "use" "pub" "with" "scope" "effect" "handler"
    "cap" "role" "pre" "post" "law" "proto" "impl" "for"
    "select" "enclave" "detach!" "forall" "contract"
    "in" "out" "loop" "recur" "proof" "par-each"
    "on-failure" "flow" "chan" "timeout" "lazy" ":open")
  "Irij language keywords.")

(defvar irij-builtins
  '("Ok" "Err" "Some" "None" "true" "false")
  "Irij built-in constructors and literals.")

(defvar irij-font-lock-keywords
  (let ((kw-re (regexp-opt irij-keywords 'symbols))
        (bi-re (regexp-opt irij-builtins 'symbols)))
    `(
      ;; Keywords
      (,kw-re . font-lock-keyword-face)
      ;; Function name after `fn` or `pub fn`
      ("\\(?:pub\\s-+\\)?fn\\s-+\\([a-z_][a-zA-Z0-9_?!-]*\\)"
       1 font-lock-function-name-face)
      ;; Module paths after mod/use
      ("\\<\\(mod\\|use\\)\\s-+\\([a-zA-Z_][a-zA-Z0-9_.]*\\)"
       (1 font-lock-preprocessor-face)
       (2 font-lock-preprocessor-face))
      ;; Built-in constructors and literals
      (,bi-re . font-lock-builtin-face)
      ;; Type/constructor names (capitalized identifiers)
      ("\\<\\([A-Z][a-zA-Z0-9]*\\)\\>" 1 font-lock-type-face)
      ;; Keyword literals :keyword-name
      ("\\(:[a-z][a-zA-Z0-9_-]*\\)" 1 font-lock-constant-face)
      ;; Binding targets: name := or name :!
      ("\\<\\([a-z_][a-zA-Z0-9_?!-]*\\)\\s-+:\\(?:=\\|!\\)"
       1 font-lock-variable-name-face)
      ))
  "Font-lock keywords for `irij-mode'.")

;; ─── Indentation ─────────────────────────────────────────────────────

(defvar irij--block-start-re
  (concat
   "^\\s-*"
   "\\(?:"
   ;; fn/handler declarations with ::
   "\\(?:pub\\s-+\\)?\\(?:fn\\|handler\\)\\s-+[a-zA-Z_][a-zA-Z0-9_?!-]*\\s-+::"
   "\\|"
   ;; type/effect/proto/impl/cap declarations
   "\\(?:type\\|effect\\|proto\\|impl\\|cap\\)\\s-+[A-Z][a-zA-Z0-9]*"
   "\\|"
   ;; Block-introducing keywords (standalone or at end)
   "\\(?:do\\|if\\|else\\|match\\|with\\|scope\\|select\\|enclave\\|on-failure\\)\\>"
   "\\)")
  "Regexp matching lines that open an indented block.")

(defvar irij--toplevel-re
  (concat "^\\s-*"
          (regexp-opt '("fn" "pub" "type" "newtype" "effect" "handler"
                        "mod" "use" "proto" "impl" "cap" "role")
                      'symbols))
  "Regexp matching top-level form starters.")

(defun irij-indent-line ()
  "Indent the current line according to Irij syntax."
  (interactive)
  (let ((indent (irij--calculate-indent)))
    (when indent
      (let ((offset (- (current-column) (current-indentation))))
        (indent-line-to indent)
        (when (> offset 0)
          (forward-char offset))))))

(defun irij--calculate-indent ()
  "Calculate the proper indentation for the current line."
  (save-excursion
    (beginning-of-line)
    (let ((cur-line (buffer-substring-no-properties
                     (line-beginning-position) (line-end-position))))
      ;; Top-level forms always at column 0
      (when (string-match irij--toplevel-re cur-line)
        (cl-return-from irij--calculate-indent 0))
      ;; Find previous non-blank, non-comment line
      (let ((prev-indent 0)
            (prev-line "")
            (found nil))
        (while (and (not found) (not (bobp)))
          (forward-line -1)
          (unless (looking-at "^\\s-*\\(?:;;.*\\)?$")
            (setq prev-indent (current-indentation))
            (setq prev-line (buffer-substring-no-properties
                             (line-beginning-position) (line-end-position)))
            (setq found t)))
        (cond
         ;; Previous line opens a block → indent
         ((string-match irij--block-start-re prev-line)
          (+ prev-indent irij-indent-offset))
         ;; Match arm on previous line (contains =>) → maintain
         ((string-match "=>" prev-line)
          prev-indent)
         ;; Default: keep same indentation as previous line
         (t prev-indent))))))

;; ─── Mode Definition ────────────────────────────────────────────────

;;;###autoload
(define-derived-mode irij-mode prog-mode "Irij"
  "Major mode for editing Irij language files."
  :syntax-table irij-mode-syntax-table
  :group 'irij
  (setq-local comment-start ";; ")
  (setq-local comment-end "")
  (setq-local comment-start-skip ";;+\\s-*")
  (setq-local indent-line-function #'irij-indent-line)
  (setq-local tab-width irij-indent-offset)
  (setq-local indent-tabs-mode nil)
  (setq-local font-lock-defaults '(irij-font-lock-keywords)))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.irj\\'" . irij-mode))

(provide 'irij-mode)

;;; irij-mode.el ends here
