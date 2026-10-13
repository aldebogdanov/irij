;;; irij-selection-test.el --- ERT tests for irij-selection -*- lexical-binding: t; -*-

;; Run from the repository root:
;;   emacs --batch -Q -L editors/emacs -l editors/emacs/test/irij-selection-test.el \
;;         -f ert-run-tests-batch-and-exit
;;
;; Without a language server the commands use the syntax-table
;; fallback; the LSP path is covered by LspSelectionTest on the server.

;;; Code:

(require 'ert)
(require 'irij-mode)
(require 'irij-selection)

(defun irij-selection-test--steps (text needle n)
  "Expand N times from the start of NEEDLE in TEXT; return each region."
  (with-temp-buffer
    (transient-mark-mode 1)
    (insert text)
    (irij-mode)
    (goto-char (point-min))
    (search-forward needle)
    (goto-char (match-beginning 0))
    (let ((out nil) (last-command nil))
      (dotimes (_ n)
        (let ((this-command 'irij-expand-region))
          (irij-expand-region)
          (push (buffer-substring-no-properties (region-beginning) (region-end)) out)
          (setq last-command this-command)))
      (nreverse out))))

(ert-deftest irij-selection-fallback-brackets ()
  (should (equal (irij-selection-test--steps "x := f (g #[1 2] y)\n" "2" 5)
                 '("2" "1 2" "#[1 2]" "g #[1 2] y" "(g #[1 2] y)"))))

(ert-deftest irij-selection-fallback-string ()
  (should (equal (irij-selection-test--steps "x := (\"hi there\")\n" "there" 4)
                 '("there" "hi there" "\"hi there\"" "(\"hi there\")"))))

(ert-deftest irij-selection-shrink-undoes-expand ()
  (with-temp-buffer
    (transient-mark-mode 1)
    (insert "x := (a b)\n")
    (irij-mode)
    (goto-char 9)
    (let ((last-command nil) (this-command 'irij-expand-region))
      (irij-expand-region)
      (setq last-command 'irij-expand-region)
      (irij-expand-region)
      (should (equal (buffer-substring (region-beginning) (region-end)) "a b"))
      (setq this-command 'irij-shrink-region)
      (irij-shrink-region)
      (should (equal (buffer-substring (region-beginning) (region-end)) "b")))))

(ert-deftest irij-selection-keys-bound ()
  (should (eq (lookup-key irij-mode-map (kbd "C-c C-=")) #'irij-expand-region))
  (should (eq (lookup-key irij-mode-map (kbd "C-c C--")) #'irij-shrink-region)))

(provide 'irij-selection-test)

;;; irij-selection-test.el ends here
