package dev.irij.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escape-sequence decoding, exercised without a terminal.
 *
 * <p>{@link TermDecoder} reads through a {@code Source}, so a test can
 * hand it a canned char stream — which is the whole reason the grammar
 * lives outside {@link TermCapability}.
 */
class TermDecoderTest {

    /** Feeds a fixed string, then reports timeout forever. */
    private static TermDecoder.Source src(String s) {
        int[] pos = {0};
        return timeout -> pos[0] < s.length() ? s.charAt(pos[0]++) : TermDecoder.EXPIRED;
    }

    private static Map<String, Object> decode(String s) {
        return TermDecoder.next(src(s), 100);
    }

    // ── Plain keys ──────────────────────────────────────────────────

    @Test
    void printableChar() {
        var ev = decode("a");
        assertEquals("key", ev.get("kind"));
        assertEquals("a", ev.get("key"));
        assertEquals("a", ev.get("ch"));
        assertEquals(List.of(), ev.get("mods"));
    }

    @Test
    void uppercaseCarriesNoShiftMod() {
        // The shift is already expressed in the character; reporting it
        // twice would make every keymap check both.
        var ev = decode("A");
        assertEquals("A", ev.get("key"));
        assertEquals(List.of(), ev.get("mods"));
    }

    @Test
    void controlKeys() {
        assertEquals("enter", decode("\r").get("key"));
        assertEquals("enter", decode("\n").get("key"));
        assertEquals("tab", decode("\t").get("key"));
        assertEquals("backspace", decode("\177").get("key"));
        assertEquals("space", decode(" ").get("key"));
    }

    @Test
    void ctrlLetter() {
        var ev = decode("\003");                  // Ctrl-C
        assertEquals("c", ev.get("key"));
        assertEquals(List.of("ctrl"), ev.get("mods"));
    }

    @Test
    void multiByteCharDecodesAsOneKey() {
        var ev = decode("é");
        assertEquals("é", ev.get("key"));
        assertEquals("é", ev.get("ch"));
    }

    // ── Escape sequences ────────────────────────────────────────────

    @Test
    void bareEscapeWhenNothingFollows() {
        assertEquals("escape", decode("\033").get("key"));
    }

    @Test
    void csiArrows() {
        assertEquals("up", decode("\033[A").get("key"));
        assertEquals("down", decode("\033[B").get("key"));
        assertEquals("right", decode("\033[C").get("key"));
        assertEquals("left", decode("\033[D").get("key"));
    }

    @Test
    void ss3ArrowsAlso() {
        // Application-cursor-key mode: same keys, different lead-in.
        assertEquals("up", decode("\033OA").get("key"));
        assertEquals("f1", decode("\033OP").get("key"));
    }

    @Test
    void editingKeys() {
        assertEquals("home", decode("\033[H").get("key"));
        assertEquals("end", decode("\033[F").get("key"));
        assertEquals("delete", decode("\033[3~").get("key"));
        assertEquals("pageup", decode("\033[5~").get("key"));
        assertEquals("pagedown", decode("\033[6~").get("key"));
        assertEquals("insert", decode("\033[2~").get("key"));
    }

    @Test
    void functionKeys() {
        assertEquals("f5", decode("\033[15~").get("key"));
        assertEquals("f10", decode("\033[21~").get("key"));
        assertEquals("f12", decode("\033[24~").get("key"));
    }

    @Test
    void modifiedArrow() {
        // CSI 1;5A — the 5 is 1 + ctrl(4).
        var ev = decode("\033[1;5A");
        assertEquals("up", ev.get("key"));
        assertEquals(List.of("ctrl"), ev.get("mods"));
    }

    @Test
    void allThreeModifiers() {
        // 8 = 1 + shift(1) + alt(2) + ctrl(4)
        var ev = decode("\033[1;8D");
        assertEquals("left", ev.get("key"));
        assertEquals(List.of("ctrl", "alt", "shift"), ev.get("mods"));
    }

    @Test
    void altPrefixedChar() {
        var ev = decode("\033x");
        assertEquals("x", ev.get("key"));
        assertEquals(List.of("alt"), ev.get("mods"));
    }

    @Test
    void shiftTab() {
        assertEquals("backtab", decode("\033[Z").get("key"));
    }

    // ── Mouse ───────────────────────────────────────────────────────

    @Test
    void mousePress() {
        var ev = decode("\033[<0;12;3M");
        assertEquals("mouse", ev.get("kind"));
        assertEquals("left", ev.get("btn"));
        assertEquals(true, ev.get("press"));
        // Wire coordinates are 1-based; buffer coordinates are not.
        assertEquals(11L, ev.get("col"));
        assertEquals(2L, ev.get("row"));
    }

    @Test
    void mouseRelease() {
        var ev = decode("\033[<2;1;1m");
        assertEquals("right", ev.get("btn"));
        assertEquals(false, ev.get("press"));
    }

    @Test
    void mouseWheel() {
        assertEquals("wheel-up", decode("\033[<64;5;5M").get("btn"));
        assertEquals("wheel-down", decode("\033[<65;5;5M").get("btn"));
    }

    @Test
    void mouseWithCtrl() {
        // 16 = ctrl bit, on top of button 0.
        var ev = decode("\033[<16;1;1M");
        assertEquals("left", ev.get("btn"));
        assertEquals(List.of("ctrl"), ev.get("mods"));
    }

    // ── Stream conditions ───────────────────────────────────────────

    @Test
    void timeoutYieldsNone() {
        assertEquals("none", TermDecoder.next(t -> TermDecoder.EXPIRED, 1).get("kind"));
    }

    @Test
    void eofIsItsOwnEvent() {
        assertEquals("eof", TermDecoder.next(t -> TermDecoder.EOF, 1).get("kind"));
    }

    @Test
    void unknownSequenceIsSwallowed() {
        // An unrecognised final byte must not surface as a stray key —
        // an app keying off `kind` would act on garbage.
        assertEquals("none", decode("\033[?1;2v").get("kind"));
    }

    @Test
    void sequentialReadsFromOneSource() {
        var s = src("ab\033[Ac");
        assertEquals("a", TermDecoder.next(s, 10).get("key"));
        assertEquals("b", TermDecoder.next(s, 10).get("key"));
        assertEquals("up", TermDecoder.next(s, 10).get("key"));
        assertEquals("c", TermDecoder.next(s, 10).get("key"));
        assertEquals("none", TermDecoder.next(s, 10).get("kind"));
    }

    @Test
    void modsAreImmutable() {
        // The event map goes on to become an Irij value; a caller
        // holding a mutable list inside it would be a leak.
        var ev = decode("\033[1;5A");
        assertTrue(ev.get("mods") instanceof List<?>);
        try {
            @SuppressWarnings("unchecked")
            var mods = (List<String>) ev.get("mods");
            mods.add("nope");
            org.junit.jupiter.api.Assertions.fail("mods should be immutable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
