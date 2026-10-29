package words;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cap provider over java.util.regex, an object API: a compiled Pattern
 * goes back to Irij as a handle and comes back in the next call. Static
 * methods, plain values in and out — so the Irij side needs no JVM.
 */
public final class Patterns {
    private Patterns() {}

    public static Object compile(String regex) {
        return Pattern.compile(regex);
    }

    /** Every match of {@code pattern} in {@code text}, in order. */
    public static List<String> findAll(Object pattern, String text) {
        Matcher m = ((Pattern) pattern).matcher(text);
        var found = new ArrayList<String>();
        while (m.find()) found.add(m.group());
        return found;
    }

    public static String replaceAll(Object pattern, String text, String with) {
        return ((Pattern) pattern).matcher(text).replaceAll(with);
    }
}
