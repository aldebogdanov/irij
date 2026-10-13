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
(require 'cl-lib)

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

;; ── lsp-mode path, with the server's answer stubbed ──────────────────
;; `x := f (g 1)': chain at `1' is 1 → g 1 → (g 1) → f (g 1) → line.

(defun irij-selection-test--range (s e)
  (list :start (list :line 0 :character s) :end (list :line 0 :character e)))

(defun irij-selection-test--answer (as-hash)
  "A selectionRange answer for `x := f (g 1)', as hash tables or plists."
  (let ((node nil))
    (dolist (r '((0 . 12) (5 . 12) (7 . 12) (8 . 11) (10 . 11)))
      (let ((range (irij-selection-test--range (car r) (cdr r))))
        (setq node
              (if as-hash
                  (let ((h (make-hash-table :test 'equal)))
                    (puthash "range" range h)
                    (when node (puthash "parent" node h))
                    h)
                (append (list :range range) (when node (list :parent node)))))))
    (vector node)))

(defun irij-selection-test--lsp-steps (as-hash n)
  (with-temp-buffer
    (transient-mark-mode 1)
    (insert "x := f (g 1)")
    (irij-mode)
    (goto-char 11)                       ; on `1'
    (setq-local lsp-mode t)              ; a mode variable, so dynamic
    (let ((out nil) (last-command nil) (calls 0))
      (cl-letf (((symbol-function 'lsp-feature?) (lambda (_) t))
                ((symbol-function 'lsp--text-document-identifier) (lambda () nil))
                ((symbol-function 'lsp--point-to-position)
                 (lambda (p) (list :line 0 :character (1- p))))
                ((symbol-function 'lsp--range-to-region)
                 (lambda (r) (cons (1+ (plist-get (plist-get r :start) :character))
                                   (1+ (plist-get (plist-get r :end) :character)))))
                ((symbol-function 'lsp-request)
                 (lambda (&rest _) (setq calls (1+ calls))
                   (irij-selection-test--answer as-hash))))
        (dotimes (_ n)
          (let ((this-command 'irij-expand-region))
            (irij-expand-region)
            (push (buffer-substring-no-properties (region-beginning) (region-end)) out)
            (setq last-command this-command)))
        (let ((this-command 'irij-shrink-region))
          (irij-shrink-region)
          (push (buffer-substring-no-properties (region-beginning) (region-end)) out)))
      (should (> calls 0))               ; the server was asked, not the fallback
      (nreverse out))))

(ert-deftest irij-selection-lsp-mode-hash-tables ()
  (should (equal (irij-selection-test--lsp-steps t 4)
                 '("1" "g 1" "(g 1)" "f (g 1)" "(g 1)"))))

(ert-deftest irij-selection-lsp-mode-plists ()
  (should (equal (irij-selection-test--lsp-steps nil 4)
                 '("1" "g 1" "(g 1)" "f (g 1)" "(g 1)"))))

;; ── Remapped expand-region commands (puni stand-in) ──────────────────

(defun puni-expand-region () (interactive) (error "puni ran, not irij"))
(defun puni-contract-region () (interactive) (error "puni ran, not irij"))
(defvar irij-selection-test-puni-map
  (let ((m (make-sparse-keymap)))
    (define-key m (kbd "C-M-=") #'puni-expand-region)
    (define-key m (kbd "C-M--") #'puni-contract-region)
    m))
(define-minor-mode irij-selection-test-puni-mode "Stand-in for puni-mode."
  :keymap irij-selection-test-puni-map)

(ert-deftest irij-selection-remaps-puni-keys ()
  (with-temp-buffer
    (switch-to-buffer (current-buffer))
    (transient-mark-mode 1)
    (insert "x := f (g 1)")
    (irij-mode)
    (irij-selection-test-puni-mode 1)   ; a minor mode map, so it wins key lookup
    (should (eq (key-binding (kbd "C-M-=")) #'irij-expand-region))
    (should (eq (key-binding (kbd "C-M--")) #'irij-shrink-region))
    (goto-char 11)                       ; on `1'
    (execute-kbd-macro (kbd "C-M-= C-M-= C-M-="))
    (should (equal (buffer-substring (region-beginning) (region-end)) "(g 1)"))
    ;; One key sequence, as typed: shrink history needs the previous
    ;; command, and each `execute-kbd-macro' call starts afresh.
    (goto-char 11)
    (deactivate-mark)
    (execute-kbd-macro (kbd "C-M-= C-M-= C-M-= C-M-= C-M-- C-M--"))
    (should (equal (buffer-substring (region-beginning) (region-end)) "g 1"))))

(ert-deftest irij-selection-keys-bound ()
  (should (eq (lookup-key irij-mode-map (kbd "C-c C-=")) #'irij-expand-region))
  (should (eq (lookup-key irij-mode-map (kbd "C-c C--")) #'irij-shrink-region)))

(provide 'irij-selection-test)

;;; irij-selection-test.el ends here
