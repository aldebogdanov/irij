;;; irij-nrepl.el --- nREPL client for the Irij programming language -*- lexical-binding: t; -*-

;; Copyright (C) 2024  Irij Contributors
;; Author: Irij Contributors
;; Version: 0.1.0
;; Keywords: languages irij nrepl
;; URL: https://github.com/irij-lang/irij
;; Package-Requires: ((emacs "27.1") (cl-lib "0.5"))

;;; Commentary:
;;
;; nREPL client for Irij (ℑ).  Connects to a running `irij --nrepl-server`
;; and evaluates code with results shown inline (CIDER-style) and in the
;; minibuffer.
;;
;; Workflow:
;;   1. Start server:  irij --nrepl-server     (terminal)
;;   2. Connect:       C-c C-n                 (in any .irj buffer)
;;   3. Eval:
;;        C-x C-e   eval expression at point   → inline  ;;  => 42
;;        C-c C-e   same
;;        C-c C-d   eval top-level declaration (fn/type/binding)
;;        C-c C-k   eval entire buffer
;;        C-c M-r   eval selected region
;;        C-c C-o   show *irij-nrepl* output buffer
;;
;; Auto-connect: if `.nrepl-port` exists in the project root, the first
;; eval command will connect automatically without prompting.
;;
;; Hot redefinition: redefining a `fn` is just another eval.  The nREPL
;; server's VarCell mechanism ensures all callers see the new version.

;;; Code:

(require 'cl-lib)


;; ── Customisation ─────────────────────────────────────────────────────

(defgroup irij-nrepl nil
  "nREPL client for the Irij programming language."
  :group 'irij
  :prefix "irij-nrepl-")

(defcustom irij-nrepl-host "localhost"
  "Hostname for the Irij nREPL server."
  :type 'string
  :group 'irij-nrepl)

(defcustom irij-nrepl-default-port 7888
  "Default port used when no .nrepl-port file is found."
  :type 'integer
  :group 'irij-nrepl)

(defcustom irij-nrepl-result-buffer "*irij-nrepl*"
  "Buffer name for nREPL output history."
  :type 'string
  :group 'irij-nrepl)

(defcustom irij-nrepl-inline-max-length 120
  "Truncate inline result overlays at this many characters."
  :type 'integer
  :group 'irij-nrepl)


;; ── Faces ─────────────────────────────────────────────────────────────

(defface irij-nrepl-result-face
  '((((class color) (background dark))
     :foreground "#4ec9b0" :slant italic)
    (((class color) (background light))
     :foreground "#007777" :slant italic)
    (t :slant italic))
  "Face for inline eval results.")

(defface irij-nrepl-error-face
  '((((class color) (background dark))
     :foreground "#f48771" :slant italic)
    (((class color) (background light))
     :foreground "#c0392b" :slant italic)
    (t :underline t :slant italic))
  "Face for inline eval errors.")


;; ── Connection state ──────────────────────────────────────────────────

(defvar irij-nrepl--conn nil
  "Active network process, or nil when disconnected.")

(defvar irij-nrepl--session nil
  "Current nREPL session UUID string.")

(defvar irij-nrepl--recv-buf ""
  "Partial receive buffer for TCP framing (bencode may arrive in chunks).")

(defvar irij-nrepl--pending (make-hash-table :test 'equal)
  "Map of request-id → (callback . accumulated-hash-table).")

(defvar irij-nrepl--id-seq 0
  "Monotonically increasing request ID counter.")

(defvar irij-nrepl--overlays nil
  "List of active inline result overlays.")


;; ────────────────────────────────────────────────────────────────────
;; Section 1 — Bencode codec
;; ────────────────────────────────────────────────────────────────────
;;
;; Bencode wire format:
;;   Integer:  i<decimal>e          e.g.  i42e   i-1e
;;   String:   <byte-len>:<bytes>   e.g.  5:hello
;;   List:     l<items>e            e.g.  li1ei2ee
;;   Dict:     d<key-val-pairs>e    e.g.  d3:fooi1ee   (keys sorted!)

;; ── Encoder ───────────────────────────────────────────────────────────

(defun irij--bencode-encode (obj)
  "Encode OBJ to a bencode byte string.
Accepts: integer, string, list (plain or alist), hash-table."
  (cond
   ((integerp obj)
    (format "i%de" obj))

   ((stringp obj)
    ;; Length = UTF-8 byte count, not character count.
    (let* ((bytes  (encode-coding-string obj 'utf-8 t))
           (prefix (format "%d:" (length bytes))))
      (concat prefix bytes)))

   ((hash-table-p obj)
    (irij--bencode-encode-dict
     (let (pairs)
       (maphash (lambda (k v) (push (cons k v) pairs)) obj)
       pairs)))

   ((listp obj)
    (cond
     ((null obj) "le")
     ;; Alist (dict): all elements are (string . value) cons cells
     ((cl-every (lambda (e) (and (consp e) (stringp (car e)))) obj)
      (irij--bencode-encode-dict obj))
     ;; Plain list
     (t
      (concat "l"
              (mapconcat #'irij--bencode-encode obj "")
              "e"))))

   (t (error "irij-bencode: cannot encode %S" obj))))

(defun irij--bencode-encode-dict (alist)
  "Encode ALIST as a bencode dict with lexicographically sorted keys."
  (let ((sorted (sort (copy-sequence alist)
                      (lambda (a b) (string< (car a) (car b))))))
    (concat "d"
            (mapconcat (lambda (pair)
                         (concat (irij--bencode-encode (car pair))
                                 (irij--bencode-encode (cdr pair))))
                       sorted "")
            "e")))

;; ── Decoder ───────────────────────────────────────────────────────────

(defun irij--bencode-decode (str pos)
  "Decode one bencode value from STR starting at byte offset POS.
Returns (VALUE . NEW-POS) on success, or nil if more bytes are needed."
  (when (< pos (length str))
    (let ((ch (aref str pos)))
      (cond

       ;; Integer: i<digits>e
       ((= ch ?i)
        (let ((end (string-match "e" str (1+ pos))))
          (when end
            (cons (string-to-number (substring str (1+ pos) end))
                  (1+ end)))))

       ;; List: l<items>e
       ;; 'result is thrown with the return value (or nil for incomplete).
       ((= ch ?l)
        (let ((items nil)
              (cur   (1+ pos)))
          (catch 'result
            (while (< cur (length str))
              (if (= (aref str cur) ?e)
                  (throw 'result (cons (nreverse items) (1+ cur)))
                (let ((r (irij--bencode-decode str cur)))
                  (unless r (throw 'result nil))
                  (push (car r) items)
                  (setq cur (cdr r)))))
            nil)))   ; ran off end without finding 'e' → incomplete

       ;; Dict: d<key-val-pairs>e
       ((= ch ?d)
        (let ((map (make-hash-table :test 'equal))
              (cur  (1+ pos)))
          (catch 'result
            (while (< cur (length str))
              (if (= (aref str cur) ?e)
                  (throw 'result (cons map (1+ cur)))
                (let ((kr (irij--bencode-decode str cur)))
                  (unless kr (throw 'result nil))
                  (let ((vr (irij--bencode-decode str (cdr kr))))
                    (unless vr (throw 'result nil))
                    (puthash (car kr) (car vr) map)
                    (setq cur (cdr vr))))))
            nil)))

       ;; String: <byte-len>:<bytes>
       ((and (>= ch ?0) (<= ch ?9))
        (let ((colon (string-match ":" str pos)))
          (when colon
            (let* ((len   (string-to-number (substring str pos colon)))
                   (start (1+ colon))
                   (end   (+ start len)))
              (when (<= end (length str))
                (cons (decode-coding-string (substring str start end) 'utf-8)
                      end))))))

       (t nil)))))


;; ────────────────────────────────────────────────────────────────────
;; Section 2 — TCP transport
;; ────────────────────────────────────────────────────────────────────

(defun irij-nrepl--filter (_proc str)
  "Process filter: accumulate STR into receive buffer, parse complete messages."
  (setq irij-nrepl--recv-buf (concat irij-nrepl--recv-buf str))
  (let (keep-going)
    (setq keep-going t)
    (while keep-going
      (let ((result (irij--bencode-decode irij-nrepl--recv-buf 0)))
        (if result
            (progn
              (setq irij-nrepl--recv-buf
                    (substring irij-nrepl--recv-buf (cdr result)))
              (irij-nrepl--dispatch (car result)))
          (setq keep-going nil))))))

(defun irij-nrepl--dispatch (msg)
  "Dispatch a parsed nREPL response MSG (hash-table) to its pending callback."
  (let* ((id     (gethash "id"     msg))
         (status (gethash "status" msg))
         (entry  (and id (gethash id irij-nrepl--pending))))
    (when entry
      (let ((callback (car entry))
            (acc      (cdr entry)))
        ;; Merge fields into accumulator; concatenate incremental out/err strings.
        (maphash (lambda (k v)
                   (let ((existing (gethash k acc)))
                     (if (and (stringp existing) (stringp v))
                         (puthash k (concat existing v) acc)
                       (puthash k v acc))))
                 msg)
        ;; Fire callback when server signals done
        (when (and (listp status) (member "done" status))
          (remhash id irij-nrepl--pending)
          (funcall callback acc))))))

(defun irij-nrepl--send-raw (op-alist callback)
  "Send OP-ALIST over the active connection without session check.
Used internally for the initial `clone` op where session is not yet set."
  (let* ((id  (format "irij-%d" (cl-incf irij-nrepl--id-seq)))
         (msg (cons (cons "id" id) op-alist)))
    (puthash id (cons callback (make-hash-table :test 'equal))
             irij-nrepl--pending)
    (process-send-string irij-nrepl--conn
                         (irij--bencode-encode msg))))

(defun irij-nrepl--send (op-alist callback)
  "Send OP-ALIST to the server; call CALLBACK with the complete response.
Signals an error if not connected."
  (unless (irij-nrepl--connected-p)
    (user-error "ℑ Not connected to nREPL  (C-c C-n to connect)"))
  (irij-nrepl--send-raw op-alist callback))

(defun irij-nrepl--connected-p ()
  "Return non-nil when a live, sessioned connection exists."
  (and irij-nrepl--conn
       (process-live-p irij-nrepl--conn)
       irij-nrepl--session))


;; ────────────────────────────────────────────────────────────────────
;; Section 3 — Session management
;; ────────────────────────────────────────────────────────────────────

(defun irij-nrepl--find-port-file ()
  "Walk from `default-directory' upward, return port from .nrepl-port or nil."
  (let ((dir (expand-file-name default-directory)))
    (catch 'found
      (while t
        (let ((f (expand-file-name ".nrepl-port" dir)))
          (when (file-exists-p f)
            (throw 'found
                   (string-to-number
                    (string-trim
                     (with-temp-buffer
                       (insert-file-contents f)
                       (buffer-string)))))))
        (let ((parent (file-name-directory (directory-file-name dir))))
          (when (string= parent dir) (throw 'found nil))
          (setq dir parent))))))

;;;###autoload
(defun irij-nrepl-connect (&optional host port)
  "Connect to an Irij nREPL server on HOST:PORT.
If PORT is nil, read .nrepl-port from the project root.
If no .nrepl-port file is found, prompt interactively."
  (interactive)
  (when (irij-nrepl--connected-p)
    (unless (yes-or-no-p "Already connected.  Reconnect? ")
      (user-error "Aborted")))
  (irij-nrepl-disconnect)
  (let* ((h (or host irij-nrepl-host "localhost"))
         (p (or port
                (irij-nrepl--find-port-file)
                (read-number "nREPL port: " irij-nrepl-default-port))))
    ;; Reset state
    (setq irij-nrepl--recv-buf  ""
          irij-nrepl--id-seq    0)
    (clrhash irij-nrepl--pending)
    ;; Open TCP connection (binary, synchronous)
    (message "ℑ Connecting to nREPL on %s:%d…" h p)
    (setq irij-nrepl--conn
          (make-network-process
           :name     "irij-nrepl"
           :host     h
           :service  p
           :filter   #'irij-nrepl--filter
           :sentinel #'irij-nrepl--sentinel
           :coding   'binary
           :nowait   nil))
    ;; Clone session.  Use --send-raw because there is no session yet —
    ;; the normal --send guard requires an established session.
    (irij-nrepl--send-raw
     (list (cons "op" "clone"))
     (lambda (resp)
       (let ((sid (gethash "new-session" resp)))
         (setq irij-nrepl--session sid)
         (message "ℑ Connected to nREPL on %s:%d  (session %s…)"
                  h p (substring sid 0 8)))))))

(defun irij-nrepl--sentinel (proc event)
  "Handle nREPL connection state changes."
  (when (string-match-p "\\(?:closed\\|failed\\|exited\\|deleted\\)" event)
    (when (eq proc irij-nrepl--conn)
      (setq irij-nrepl--conn    nil
            irij-nrepl--session nil)
      (message "ℑ nREPL disconnected"))))

(defun irij-nrepl-disconnect ()
  "Close the active nREPL session and connection."
  (interactive)
  (when (and irij-nrepl--conn (process-live-p irij-nrepl--conn))
    (when irij-nrepl--session
      (ignore-errors
        (irij-nrepl--send-raw
         (list (cons "op"      "close")
               (cons "session" irij-nrepl--session))
         #'ignore)))
    (delete-process irij-nrepl--conn))
  (setq irij-nrepl--conn    nil
        irij-nrepl--session nil))

(defun irij-nrepl--ensure-connected ()
  "Auto-connect via .nrepl-port if possible; otherwise error."
  (unless (irij-nrepl--connected-p)
    (let ((port (irij-nrepl--find-port-file)))
      (if port
          (progn
            (irij-nrepl-connect irij-nrepl-host port)
            ;; Wait briefly for the async clone response
            (accept-process-output irij-nrepl--conn 2))
        (user-error "ℑ Not connected to nREPL  (C-c C-n to connect)")))))


;; ────────────────────────────────────────────────────────────────────
;; Section 4 — Inline result overlays
;; ────────────────────────────────────────────────────────────────────

(defun irij-nrepl--clear-overlays ()
  "Remove all active inline result overlays."
  (mapc #'delete-overlay irij-nrepl--overlays)
  (setq irij-nrepl--overlays nil))

(defun irij-nrepl--show-inline (buf pos text &optional errp)
  "Show TEXT as an inline overlay after POS in BUF.
Use error face when ERRP is non-nil."
  (irij-nrepl--clear-overlays)
  (when (buffer-live-p buf)
    (with-current-buffer buf
      (let* ((face    (if errp 'irij-nrepl-error-face 'irij-nrepl-result-face))
             (display (if (> (length text) irij-nrepl-inline-max-length)
                          (concat (substring text 0 (- irij-nrepl-inline-max-length 1))
                                  "…")
                        text))
             (str     (propertize (concat "  ;; => " display)
                                  'face   face
                                  'cursor t))
             (ov      (make-overlay pos pos buf t nil)))
        (overlay-put ov 'after-string      str)
        (overlay-put ov 'irij-nrepl-result t)
        (push ov irij-nrepl--overlays)
        ;; Dismiss the overlay on the very next user command
        (let ((dismiss nil))
          (setq dismiss
                (lambda ()
                  (irij-nrepl--clear-overlays)
                  (remove-hook 'pre-command-hook dismiss)))
          (add-hook 'pre-command-hook dismiss))))))


;; ────────────────────────────────────────────────────────────────────
;; Section 5 — Eval & result display
;; ────────────────────────────────────────────────────────────────────

(defun irij-nrepl--eval (code &optional buf pos)
  "Evaluate CODE string via nREPL; display result inline at BUF/POS."
  (irij-nrepl--send
   (list (cons "op"      "eval")
         (cons "code"    code)
         (cons "session" irij-nrepl--session))
   (let ((target-buf buf)
         (target-pos pos))
     (lambda (resp)
       (irij-nrepl--handle-response resp target-buf target-pos)))))

(defun irij-nrepl--handle-response (resp buf pos)
  "Handle a completed nREPL eval response RESP.
Show inline overlay at BUF/POS when provided."
  (let ((value  (gethash "value"  resp))
        (out    (gethash "out"    resp))
        (err    (gethash "err"    resp))
        (status (gethash "status" resp)))
    ;; Always log to the output buffer
    (irij-nrepl--append-to-result-buf out err value)
    ;; Inline overlay + minibuffer
    (cond
     ((and (listp status) (member "eval-error" status))
      (let ((msg (string-trim (or err "evaluation failed"))))
        (when (and buf pos)
          (irij-nrepl--show-inline buf pos msg t))
        (message "ℑ Error: %s" msg)))
     (value
      (when (and buf pos)
        (irij-nrepl--show-inline buf pos value nil))
      ;; Also show any stdout that came along — useful for C-c C-k / C-c M-r
      (let ((printed (and out (not (string-empty-p (string-trim out)))
                          (string-trim out))))
        (if printed
            (message "ℑ => %s   [out: %s]" value printed)
          (message "ℑ => %s" value)))))))

(defun irij-nrepl--append-to-result-buf (out err value)
  "Append OUT, ERR, and VALUE to the nREPL result buffer."
  (with-current-buffer (get-buffer-create irij-nrepl-result-buffer)
    (unless (derived-mode-p 'irij-nrepl-result-mode)
      (irij-nrepl-result-mode))
    (let ((inhibit-read-only t))
      (goto-char (point-max))
      (when (or out err value)
        (insert "\n;; ─────────────────────────────\n"))
      (when (and out (not (string-empty-p out)))
        (insert (replace-regexp-in-string
                 "^\\(.\\)" ";;   \\1"
                 (format ";; stdout:\n%s" out))))
      (when (and err (not (string-empty-p err)))
        (insert (format ";; error: %s\n" (string-trim err))))
      (when (and value (not (string-empty-p value)))
        (insert (format ";; => %s\n" value))))))


;; ────────────────────────────────────────────────────────────────────
;; Section 6 — Result buffer
;; ────────────────────────────────────────────────────────────────────

(defvar irij-nrepl-result-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "g")       #'irij-nrepl-clear-result-buffer)
    (define-key map (kbd "C-c C-c") #'irij-nrepl-clear-result-buffer)
    (define-key map (kbd "q")       #'quit-window)
    map)
  "Keymap for the Irij nREPL result buffer.")

(define-derived-mode irij-nrepl-result-mode special-mode "ℑ-nREPL"
  "Read-only mode for the Irij nREPL output history buffer.

Key bindings:
  g / C-c C-c   clear buffer
  q             quit window"
  (setq buffer-read-only t)
  (setq-local truncate-lines nil))

(defun irij-nrepl-clear-result-buffer ()
  "Clear the nREPL output history buffer."
  (interactive)
  (with-current-buffer (get-buffer-create irij-nrepl-result-buffer)
    (let ((inhibit-read-only t))
      (erase-buffer))))

(defun irij-nrepl-show-result-buffer ()
  "Pop to the nREPL output history buffer."
  (interactive)
  (pop-to-buffer (get-buffer-create irij-nrepl-result-buffer)))


;; ────────────────────────────────────────────────────────────────────
;; Section 7 — User commands
;; ────────────────────────────────────────────────────────────────────

;;;###autoload
(defun irij-nrepl-eval-last-sexp ()
  "Evaluate the Irij expression ending at point; show result inline."
  (interactive)
  (irij-nrepl--ensure-connected)
  (let* ((buf (current-buffer))
         (end (point))
         (beg (save-excursion
                (condition-case nil
                    (progn (backward-sexp) (point))
                  (scan-error (line-beginning-position)))))
         (code (string-trim (buffer-substring-no-properties beg end))))
    (irij-nrepl--eval code buf end)))

;;;###autoload
(defun irij-nrepl-eval-region (beg end)
  "Evaluate the selected region; show result in minibuffer."
  (interactive "r")
  (irij-nrepl--ensure-connected)
  (irij-nrepl--eval
   (buffer-substring-no-properties beg end)
   nil nil))

;;;###autoload
(defun irij-nrepl-eval-defun ()
  "Evaluate the top-level Irij form at point; show result inline.
A top-level form is any non-blank, non-comment line that starts at
column 0, plus its indented body lines."
  (interactive)
  (irij-nrepl--ensure-connected)
  (let* ((buf  (current-buffer))
         (info (irij-nrepl--defun-at-point)))
    (if info
        (irij-nrepl--eval (car info) buf (cdr info))
      (user-error "ℑ No top-level form found at point"))))

(defun irij-nrepl--col0-line-p ()
  "Non-nil if point is on a substantive column-0 line (not blank, not comment).
This is the definition of a top-level form boundary in Irij."
  (and (not (looking-at "^[ \t]*$"))
       (not (looking-at "^;;"))
       (looking-at "^[^ \t]")))

(defun irij-nrepl--defun-at-point ()
  "Return (CODE . OVERLAY-POS) for the top-level form containing point.
Walks backward to the nearest col-0 substantive line, then forward
to collect its body (indented + blank lines).  Returns nil at BOB
if no col-0 line exists above."
  (save-excursion
    (beginning-of-line)
    ;; Walk backward until we land on a col-0 substantive line
    (while (and (not (bobp))
                (not (irij-nrepl--col0-line-p)))
      (forward-line -1))
    (when (irij-nrepl--col0-line-p)
      (let ((start       (point))
            (overlay-pos (line-end-position)))
        ;; Walk forward: absorb indented lines and blank lines
        (forward-line 1)
        (while (and (not (eobp))
                    (not (irij-nrepl--col0-line-p)))
          (forward-line 1))
        (cons (string-trim-right
               (buffer-substring-no-properties start (point)))
              overlay-pos)))))

;;;###autoload
(defun irij-nrepl-eval-buffer ()
  "Evaluate the entire buffer; show result in minibuffer."
  (interactive)
  (irij-nrepl--ensure-connected)
  ;; No inline overlay for whole-buffer eval (point-max is off-screen).
  ;; Result + stdout will appear in minibuffer and *irij-nrepl* buffer.
  (irij-nrepl--eval
   (buffer-substring-no-properties (point-min) (point-max))
   nil nil))


;; ────────────────────────────────────────────────────────────────────
;; Section 8 — Keymap installation
;; ────────────────────────────────────────────────────────────────────
;;
;; Keybindings are defined in two places:
;;   1. irij-mode.el defvar — picked up on fresh Emacs start
;;   2. with-eval-after-load below — installs into the LIVE map in a
;;      running Emacs even when irij-mode-map already existed before
;;      irij-nrepl.el was first loaded.
;;
;; This means `M-x load-file irij-nrepl.el` is enough to activate all
;; keybindings without restarting Emacs.

(defun irij-nrepl--install-keys ()
  "Install nREPL keybindings into `irij-mode-map'."
  (when (boundp 'irij-mode-map)
    (define-key irij-mode-map (kbd "C-c C-n") #'irij-nrepl-connect)
    (define-key irij-mode-map (kbd "C-x C-e") #'irij-nrepl-eval-last-sexp)
    (define-key irij-mode-map (kbd "C-c C-e") #'irij-nrepl-eval-last-sexp)
    (define-key irij-mode-map (kbd "C-c C-d") #'irij-nrepl-eval-defun)
    (define-key irij-mode-map (kbd "C-c C-k") #'irij-nrepl-eval-buffer)
    (define-key irij-mode-map (kbd "C-c M-r") #'irij-nrepl-eval-region)
    (define-key irij-mode-map (kbd "C-c C-o") #'irij-nrepl-show-result-buffer)))

;; Run immediately if irij-mode is already loaded (live Emacs session),
;; or deferred until it loads (fresh start).
(with-eval-after-load 'irij-mode
  (irij-nrepl--install-keys))

(provide 'irij-nrepl)

;;; irij-nrepl.el ends here
