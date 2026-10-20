;;; irij-mode-test.el --- ERT tests for irij-mode -*- lexical-binding: t; -*-

;; Run from the repository root:
;;   emacs --batch -Q -L editors/emacs -l editors/emacs/test/irij-mode-test.el \
;;         -f ert-run-tests-batch-and-exit

;;; Code:

(require 'ert)
(require 'irij-mode)

(defun irij-test--face-at (text needle &optional nth)
  "Face at the start of the NTH (default 1) occurrence of NEEDLE in TEXT."
  (with-temp-buffer
    (insert text)
    (irij-mode)
    (font-lock-ensure)
    (goto-char (point-min))
    (search-forward needle nil nil (or nth 1))
    (let ((f (get-text-property (match-beginning 0) 'face)))
      (if (listp f) (car f) f))))

(defun irij-test--indent (text)
  "Column `irij-mode' computes for the last line of TEXT."
  (with-temp-buffer
    (insert text)
    (irij-mode)
    (goto-char (point-max))
    (irij--compute-indent)))

;; ── Highlighting ──────────────────────────────────────────────────────

(ert-deftest irij-face-predicate-builtins ()
  (should (eq (irij-test--face-at "x := contains? xs 1" "contains?") 'font-lock-builtin-face))
  (should (eq (irij-test--face-at "x := validate! s v" "validate!") 'font-lock-builtin-face))
  (should (eq (irij-test--face-at "q := quo 7 2" "quo") 'font-lock-builtin-face)))

(ert-deftest irij-face-builtin-not-inside-ident ()
  (should-not (eq (irij-test--face-at "x := head-of xs" "head") 'font-lock-builtin-face)))

(ert-deftest irij-face-keywords ()
  (should (eq (irij-test--face-at "impl Show for Int" "for") 'font-lock-keyword-face))
  (should (eq (irij-test--face-at "proof foo" "proof") 'font-lock-keyword-face))
  (should (eq (irij-test--face-at "x := par-each xs" "par-each") 'font-lock-keyword-face)))

(ert-deftest irij-face-model-soft-keyword ()
  (should (eq (irij-test--face-at "model bank :: \"b.qnt\" :pure" "model")
              'font-lock-keyword-face))
  (should (eq (irij-test--face-at "pub model bank :: \"b.qnt\" :pure" "bank")
              'font-lock-function-name-face))
  (should-not (eq (irij-test--face-at "model := 1\nprintln model" "model" 2)
                  'font-lock-keyword-face)))

(ert-deftest irij-face-declaration-names ()
  (should (eq (irij-test--face-at "fn empty-ish? xs" "empty-ish?") 'font-lock-function-name-face))
  (should (eq (irij-test--face-at "handler acc :: Counter" "acc") 'font-lock-function-name-face))
  (should (eq (irij-test--face-at "cap fs-files :: FileIO = \"x\"" "fs-files")
              'font-lock-function-name-face))
  (should (eq (irij-test--face-at "effect Ask" "Ask") 'font-lock-type-face)))

(ert-deftest irij-face-atoms ()
  (should (eq (irij-test--face-at "x := :not_found" ":not_found") 'font-lock-constant-face))
  (should-not (eq (irij-test--face-at "x := on:click" ":click") 'font-lock-constant-face))
  (should-not (eq (irij-test--face-at "f :: Int Int" ":: Int") 'font-lock-constant-face)))

(ert-deftest irij-face-operators-and-interop ()
  (should (eq (irij-test--face-at "x <- 1" "<-") 'font-lock-keyword-face))
  (should (eq (irij-test--face-at "$A ~> $B" "~>") 'font-lock-keyword-face))
  (should (eq (irij-test--face-at "r := 1 ..< 10" "..<") 'font-lock-keyword-face))
  (should (eq (irij-test--face-at "x := Math/sqrt 2.0" "Math/sqrt") 'font-lock-builtin-face))
  (should (eq (irij-test--face-at "x := s#trim ()" "#trim") 'font-lock-builtin-face))
  (should (eq (irij-test--face-at "ys := xs |> @ #toUpperCase" "#toUpperCase") 'font-lock-builtin-face)))

(ert-deftest irij-face-string-interpolation ()
  (should (eq (irij-test--face-at "s := \"hi ${name}!\"" "${") 'font-lock-preprocessor-face))
  (should (eq (irij-test--face-at "s := \"hi ${name}!\"" "hi") 'font-lock-string-face)))

(ert-deftest irij-face-numbers-not-in-idents ()
  (should (memq (irij-test--face-at "x := 1_000" "1_000")
                '(font-lock-number-face font-lock-constant-face)))
  (should-not (irij-test--face-at "y := n-1" "1")))

;; ── Indentation ───────────────────────────────────────────────────────

(ert-deftest irij-indent-block-heads ()
  (should (= 2 (irij-test--indent "fn square\nx")))
  (should (= 2 (irij-test--indent "pub fn all? :: Fn Vec Bool\nx")))
  (should (= 2 (irij-test--indent "handler h :: Eff\nx")))
  (should (= 2 (irij-test--indent "impl Show for Int\nx")))
  (should (= 4 (irij-test--indent "fn f\n  match x\nx")))
  (should (= 4 (irij-test--indent "fn f\n  r := match x\nx")))
  (should (= 2 (irij-test--indent "fn f\n  r := match x\n  y\nx")))
  (should (= 4 (irij-test--indent "fn fact\n  0 => 1\n  n =>\nx")))
  (should (= 4 (irij-test--indent "fn f\n  cond\nx")))
  (should (= 4 (irij-test--indent "fn f\n  label := cond\nx")))
  (should (= 4 (irij-test--indent "fn f\n  case cmd\nx"))))

(ert-deftest irij-indent-inline-forms-do-not-open ()
  (should (= 2 (irij-test--indent "fn f\n  y := if (a > b) a else b\nx")))
  (should (= 0 (irij-test--indent "fn square (n -> n * n)\nx")))
  (should (= 0 (irij-test--indent "cap db :: Db = \"dev.Db\"\nx")))
  (should (= 2 (irij-test--indent "fn f\n  spec :: Int\nx")))
  (should (= 0 (irij-test--indent "use std.list\nx")))
  (should (= 0 (irij-test--indent "do-stuff 1\nx")))
  (should (= 0 (irij-test--indent "r := with fs.default-fs do (f 1)\nx")))
  (should (= 0 (irij-test--indent "r := do (note 1) (note 2)\nx")))
  (should (= 0 (irij-test--indent "cond := true\nx")))
  (should (= 0 (irij-test--indent "case := 7\nx")))
  (should (= 2 (irij-test--indent "fn f\n  =>\nx")))
  (should (= 2 (irij-test--indent "fn f\n  => tag\nx"))))

(ert-deftest irij-indent-else-matches-if ()
  (should (= 2 (irij-test--indent "fn f\n  if c\n    a\n    b\nelse")))
  (should (= 0 (irij-test--indent "if a\n  if b\n    x\n  else\n    y\nelse")))
  (should (= 2 (irij-test--indent "if a\n  if b\n    x\nelse")))
  (should (= 2 (irij-test--indent "fn f\n  r := if c\n    a\nelse"))))

(ert-deftest irij-indent-brackets ()
  (should (= 8 (irij-test--indent "xs := #[1 2\nx")))
  (should (= 2 (irij-test--indent "foo (x ->\nx")))
  (should (= 2 (irij-test--indent "foo (\nx")))
  (should (= 0 (irij-test--indent "foo (\n  a\n)")))
  (should (= 2 (irij-test--indent "foo (x ->\n  a\nx")))
  (should (= 0 (irij-test--indent "foo (x ->\n  a)\nx"))))

(ert-deftest irij-indent-fn-spec-with-fn-type ()
  (should (= 2 (irij-test--indent "pub fn filter :: (_ -> Bool):eff _ _ ::: eff\nx")))
  (should (= 2 (irij-test--indent "fn twice :: (Int -> Int) Int Int\nx")))
  (should (= 0 (irij-test--indent "fn twice :: Fn Int (f x -> f (f x))\nx"))))

(ert-deftest irij-indent-leading-infix ()
  (should (= 2 (irij-test--indent "r := #[1 2 3]\n|> f")))
  (should (= 2 (irij-test--indent "r := #[1 2 3]\n  |> f\n|> g")))
  (should (= 4 (irij-test--indent "fn f\n  r := xs\n++ ys")))
  (should (= 3 (irij-test--indent "  (\"a\"\n++ \"b\""))))

(ert-deftest irij-indent-model-body ()
  (should (= 2 (irij-test--indent "model m :: \"b.qnt\" :pure {main= \"t\"}\nx"))))

(ert-deftest irij-indent-blank-line-uses-context ()
  (should (= 2 (irij-test--indent "fn f\n"))))

(ert-deftest irij-indent-tab-cycles ()
  (with-temp-buffer
    (insert "fn f\n  if c\n    a\n    b")
    (irij-mode)
    (let ((this-command 'indent-for-tab-command) (last-command nil))
      (irij-indent-line)
      (should (= 4 (current-indentation)))
      (setq last-command 'indent-for-tab-command)
      (irij-indent-line)
      (should (= 2 (current-indentation)))
      (irij-indent-line)
      (should (= 0 (current-indentation)))
      (irij-indent-line)
      (should (= 4 (current-indentation))))))

(ert-deftest irij-indent-region-keeps-valid-lines ()
  (let ((src "fn f\n  a\n  if c\n    b\n  d\ny := 1\ntest \"t\" (->\n  eq (f \"\")\n     \"x\")\n"))
    (with-temp-buffer
      (insert src)
      (irij-mode)
      (indent-region (point-min) (point-max))
      (should (equal (buffer-string) src)))))

(ert-deftest irij-indent-region-fixes-unindented-body ()
  (with-temp-buffer
    (insert "fn f\n  if c\n  x\n")
    (irij-mode)
    (indent-region (point-min) (point-max))
    (should (equal (buffer-string) "fn f\n  if c\n    x\n"))))

(ert-deftest irij-repl-prompt-regexp ()
  (with-temp-buffer
    (irij-repl-mode)
    (should (string-match-p comint-prompt-regexp "ℑ> "))
    (should (string-match-p comint-prompt-regexp " | "))))

(provide 'irij-mode-test)

;;; irij-mode-test.el ends here
