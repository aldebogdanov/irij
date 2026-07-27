package dev.irij.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Terminal input decoder: a stream of chars in, one event map out.
 *
 * <p>Deliberately independent of JLine and of any terminal — it reads
 * through the {@link Source} interface, so the whole escape-sequence
 * grammar is unit-testable by feeding a char array
 * ({@code TermDecoderTest}). {@link TermCapability} supplies the real
 * source (a JLine {@code NonBlockingReader}) and wraps the returned
 * maps into Irij values.
 *
 * <p>Events are maps discriminated by {@code kind}:
 * <pre>
 *   {kind= "key"    key= "enter" ch= "" mods= ["ctrl"]}
 *   {kind= "mouse"  btn= "left" press= true col= 11 row= 2 mods= []}
 *   {kind= "none"}                      ;; timeout expired
 *   {kind= "eof"}                       ;; stdin closed
 * </pre>
 * ({@code resize} events don't originate here — they come from the
 * signal handler's inbox, see {@link TermCapability}.)
 *
 * <p>Mouse coordinates are 0-based, matching buffer coordinates; the
 * wire protocol's 1-based values are converted here.
 */
public final class TermDecoder {

    private TermDecoder() {}

    /** Char source. Mirrors JLine's {@code NonBlockingReader.read(long)}. */
    public interface Source {
        /** @return a char, {@link #EOF}, or {@link #EXPIRED} */
        int read(long timeoutMs);
    }

    public static final int EOF = -1;
    public static final int EXPIRED = -2;

    /**
     * How long to wait for the rest of an escape sequence before
     * concluding the ESC stood alone. The classic ambiguity: a bare
     * Escape keypress and the lead byte of a CSI sequence are the same
     * char, and only silence distinguishes them. 40 ms is comfortably
     * above local-terminal latency and below human "the Esc key is
     * laggy" perception. Over a slow ssh link a chopped sequence
     * decodes as Escape + garbage; that's the accepted trade every
     * terminal app makes.
     */
    static final long ESC_TIMEOUT_MS = 40;

    /** Read one event, waiting at most {@code timeoutMs} for its first char. */
    public static Map<String, Object> next(Source src, long timeoutMs) {
        int c = src.read(timeoutMs);
        if (c == EXPIRED) return event("none");
        if (c == EOF) return event("eof");
        if (c == 27) return escape(src);
        return fromChar(c);
    }

    // ── Plain chars and C0 controls ─────────────────────────────────

    private static Map<String, Object> fromChar(int c) {
        return switch (c) {
            case 13, 10 -> key("enter", "");
            case 9      -> key("tab", "");
            case 127, 8 -> key("backspace", "");
            case 0      -> key("space", "", "ctrl");
            case 27     -> key("escape", "");
            default -> {
                if (c >= 1 && c <= 26) {
                    // Ctrl-A .. Ctrl-Z, minus the three cased above.
                    yield key(String.valueOf((char) ('a' + c - 1)), "", "ctrl");
                }
                if (c >= 28 && c <= 31) {
                    // Ctrl-\ Ctrl-] Ctrl-^ Ctrl-_
                    yield key(String.valueOf((char) (c + 64)).toLowerCase(), "", "ctrl");
                }
                if (c == 32) yield key("space", " ");
                String s = new String(Character.toChars(c));
                yield key(s, s);
            }
        };
    }

    // ── Escape sequences ────────────────────────────────────────────

    private static Map<String, Object> escape(Source src) {
        int c = src.read(ESC_TIMEOUT_MS);
        if (c == EXPIRED) return key("escape", "");
        if (c == EOF) return event("eof");
        if (c == '[') return csi(src);
        if (c == 'O') return ss3(src);
        if (c == 27) return key("escape", "");
        // ESC <char> — the alt/meta prefix.
        return withAlt(fromChar(c));
    }

    /** {@code ESC [ params final} — arrows, editing keys, mouse reports. */
    private static Map<String, Object> csi(Source src) {
        var params = new StringBuilder();
        int c = src.read(ESC_TIMEOUT_MS);
        // Parameter (0x30-0x3F) and intermediate (0x20-0x2F) bytes, then
        // a final byte in 0x40-0x7E.
        while (c >= 0x20 && c <= 0x3F) {
            params.append((char) c);
            c = src.read(ESC_TIMEOUT_MS);
        }
        if (c < 0) return key("escape", "");
        char fin = (char) c;
        String p = params.toString();

        if (p.startsWith("<")) return mouse(p.substring(1), fin);

        long[] nums = parseParams(p);
        long p1 = nums.length > 0 ? nums[0] : 1;
        String[] mods = modsFrom(nums.length > 1 ? nums[1] : 1);

        String name = switch (fin) {
            case 'A' -> "up";
            case 'B' -> "down";
            case 'C' -> "right";
            case 'D' -> "left";
            case 'H' -> "home";
            case 'F' -> "end";
            case 'Z' -> "backtab";
            case 'P' -> "f1";
            case 'Q' -> "f2";
            case 'R' -> "f3";
            case 'S' -> "f4";
            case '~' -> tildeKey(p1);
            default  -> null;
        };
        if (name == null) return event("none");
        // CSI 1;5A style: the modifier rides in the second param. For
        // the `~` family the first param IS the key, so the modifier is
        // still param 2 — same slot, already read.
        return key(name, "", mods);
    }

    /** {@code ESC O x} — the "application cursor keys" variant. */
    private static Map<String, Object> ss3(Source src) {
        int c = src.read(ESC_TIMEOUT_MS);
        if (c < 0) return key("escape", "");
        String name = switch ((char) c) {
            case 'A' -> "up";
            case 'B' -> "down";
            case 'C' -> "right";
            case 'D' -> "left";
            case 'H' -> "home";
            case 'F' -> "end";
            case 'P' -> "f1";
            case 'Q' -> "f2";
            case 'R' -> "f3";
            case 'S' -> "f4";
            default  -> null;
        };
        return name == null ? event("none") : key(name, "");
    }

    private static String tildeKey(long p1) {
        return switch ((int) p1) {
            case 1, 7 -> "home";
            case 2    -> "insert";
            case 3    -> "delete";
            case 4, 8 -> "end";
            case 5    -> "pageup";
            case 6    -> "pagedown";
            case 11   -> "f1";
            case 12   -> "f2";
            case 13   -> "f3";
            case 14   -> "f4";
            case 15   -> "f5";
            case 17   -> "f6";
            case 18   -> "f7";
            case 19   -> "f8";
            case 20   -> "f9";
            case 21   -> "f10";
            case 23   -> "f11";
            case 24   -> "f12";
            default   -> null;
        };
    }

    /**
     * SGR mouse report (mode 1006): {@code ESC [ < btn ; col ; row M|m}.
     * Chosen over the legacy X10 encoding because coordinates aren't
     * capped at 223 and release events carry their button.
     */
    private static Map<String, Object> mouse(String params, char fin) {
        long[] nums = parseParams(params);
        if (nums.length < 3) return event("none");
        long b = nums[0];
        long col = nums[1] - 1;
        long row = nums[2] - 1;

        var mods = new ArrayList<String>();
        if ((b & 16) != 0) mods.add("ctrl");
        if ((b & 8) != 0) mods.add("alt");
        if ((b & 4) != 0) mods.add("shift");

        String btn;
        boolean press = fin == 'M';
        if ((b & 64) != 0) {
            btn = (b & 1) == 0 ? "wheel-up" : "wheel-down";
            press = true;                       // wheels have no release
        } else if ((b & 32) != 0) {
            btn = "move";
        } else {
            btn = switch ((int) (b & 3)) {
                case 0 -> "left";
                case 1 -> "middle";
                case 2 -> "right";
                default -> "none";
            };
        }

        var ev = event("mouse");
        ev.put("btn", btn);
        ev.put("press", press);
        ev.put("col", col);
        ev.put("row", row);
        ev.put("mods", List.copyOf(mods));
        return ev;
    }

    private static long[] parseParams(String p) {
        if (p.isEmpty()) return new long[0];
        String[] parts = p.split(";", -1);
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 1 : Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 1;
            }
        }
        return out;
    }

    /** xterm modifier param: 1 + bitmask(shift=1, alt=2, ctrl=4). */
    private static String[] modsFrom(long param) {
        long bits = param - 1;
        if (bits <= 0) return new String[0];
        var mods = new ArrayList<String>();
        if ((bits & 4) != 0) mods.add("ctrl");
        if ((bits & 2) != 0) mods.add("alt");
        if ((bits & 1) != 0) mods.add("shift");
        return mods.toArray(new String[0]);
    }

    // ── Event construction ──────────────────────────────────────────

    private static Map<String, Object> event(String kind) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", kind);
        return m;
    }

    private static Map<String, Object> key(String name, String ch, String... mods) {
        var m = event("key");
        m.put("key", name);
        m.put("ch", ch);
        m.put("mods", List.of(mods));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withAlt(Map<String, Object> ev) {
        if (!"key".equals(ev.get("kind"))) return ev;
        var mods = new ArrayList<String>((List<String>) ev.get("mods"));
        if (!mods.contains("alt")) mods.add("alt");
        ev.put("mods", List.copyOf(mods));
        return ev;
    }
}
