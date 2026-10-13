;;; irij-selection.el --- Expand/shrink region for Irij -*- lexical-binding: t; -*-

;; Copyright (C) 2026  Irij Contributors
;; Author: Irij Contributors
;; Keywords: languages irij
;; URL: https://github.com/aldebogdanov/irij
;; Package-Requires: ((emacs "27.1"))

;;; Commentary:
;;
;; Structural selection for Irij buffers:
;;
;;   C-c C-=   irij-expand-region   grow the region to the next enclosing node
;;   C-c C--   irij-shrink-region   undo the last expansion
;;
;; With eglot managing the buffer, the steps come from the language
;; server's `textDocument/selectionRange' (`irij lsp' walks the parse
;; tree: name → application → bracket contents → bracket → statement →
;; block → declaration → file).  lsp-mode users get the same through
;; `lsp-extend-selection'.  Without a server the commands fall back to
;; enclosing brackets and strings, from the syntax table.
;;
;; `irij-mode' loads this file when it is on the load-path.

;;; Code:

(require 'seq)
(require 'thingatpt)

(declare-function eglot-current-server "eglot" ())
(declare-function eglot-server-capable "eglot" (&rest feats))
(declare-function eglot--request "eglot" (server method params &rest args))
(declare-function eglot--TextDocumentIdentifier "eglot" ())
(declare-function eglot--pos-to-lsp-position "eglot" (&optional pos))
(declare-function eglot-range-region "eglot" (range &optional markers))
(declare-function eglot--range-region "eglot" (range &optional markers))
(declare-function lsp-extend-selection "lsp-mode" ())
(defvar lsp-mode)

(defvar-local irij-selection--history nil
  "Regions active before each `irij-expand-region' step, newest first.")

(defun irij-selection--region ()
  "The active region as (BEG . END), or (POINT . POINT)."
  (if (use-region-p)
      (cons (region-beginning) (region-end))
    (cons (point) (point))))

(defun irij-selection--eglot-chain (pos)
  "Innermost-first list of (BEG . END) for POS from the language server.
Nil when eglot doesn't manage the buffer or the server lacks the
capability."
  (when-let* ((server (and (fboundp 'eglot-current-server) (eglot-current-server))))
    (when (eglot-server-capable :selectionRangeProvider)
      (let* ((resp (eglot--request
                    server :textDocument/selectionRange
                    (list :textDocument (eglot--TextDocumentIdentifier)
                          :positions (vector (eglot--pos-to-lsp-position pos)))))
             (node (and (> (length resp) 0) (aref resp 0)))
             (to-region (if (fboundp 'eglot-range-region)
                            #'eglot-range-region #'eglot--range-region))
             (out nil))
        (while node
          (push (funcall to-region (plist-get node :range)) out)
          (setq node (plist-get node :parent)))
        (nreverse out)))))

(defun irij-selection--syntax-chain (pos)
  "Innermost-first list of (BEG . END) for POS from brackets and strings."
  (save-excursion
    (goto-char pos)
    (let ((out nil))
      (when-let* ((bounds (bounds-of-thing-at-point 'symbol)))
        (push bounds out))
      (let ((ppss (syntax-ppss pos)))
        (when (nth 3 ppss)
          (let ((open (nth 8 ppss)))
            (goto-char open)
            (forward-sexp)
            (push (cons (1+ open) (1- (point))) out)
            (push (cons open (point)) out)))
        (dolist (open (reverse (nth 9 ppss)))
          (goto-char open)
          (when-let* ((close (ignore-errors (scan-lists open 1 0))))
            (push (cons (1+ open) (1- close)) out)
            (push (cons (if (eq (char-before open) ?#) (1- open) open) close) out))))
      (push (cons (point-min) (point-max)) out)
      (nreverse out))))

(defun irij-selection--set (region)
  "Select REGION, a (BEG . END) pair, leaving point at its start."
  (goto-char (cdr region))
  (push-mark (cdr region) t t)
  (goto-char (car region)))

;;;###autoload
(defun irij-expand-region ()
  "Grow the region to the next enclosing syntactic node."
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (lsp-extend-selection)
    (let* ((cur   (irij-selection--region))
           (chain (or (irij-selection--eglot-chain (car cur))
                      (irij-selection--syntax-chain (car cur))))
           (next  (seq-find (lambda (r)
                              (and (<= (car r) (car cur)) (>= (cdr r) (cdr cur))
                                   (not (equal r cur))))
                            chain)))
      (unless (memq last-command '(irij-expand-region irij-shrink-region))
        (setq irij-selection--history nil))
      (if (not next)
          (message "ℑ Selection covers the whole buffer")
        (push cur irij-selection--history)
        (irij-selection--set next)))))

;;;###autoload
(defun irij-shrink-region ()
  "Undo the last `irij-expand-region' step."
  (interactive)
  (let ((prev (pop irij-selection--history)))
    (cond
     ((null prev) (message "ℑ Nothing to shrink"))
     ((= (car prev) (cdr prev)) (deactivate-mark) (goto-char (car prev)))
     (t (irij-selection--set prev)))))

(with-eval-after-load 'irij-mode
  (defvar irij-mode-map)
  (define-key irij-mode-map (kbd "C-c C-=") #'irij-expand-region)
  (define-key irij-mode-map (kbd "C-c C--") #'irij-shrink-region))

(provide 'irij-selection)

;;; irij-selection.el ends here
