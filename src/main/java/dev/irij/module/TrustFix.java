package dev.irij.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds entries to irij.toml's {@code [trust]} as text, for
 * {@code irij audit --fix}: the rest of the file — comments, order,
 * spacing — stays as written. Without a {@code [trust]} section one is
 * appended with all three keys, so the file says outright what may reach
 * Java (an empty list included). With one, each key that needs entries
 * gets them on its existing line, or a new line under the header.
 */
public final class TrustFix {

    private TrustFix() {}

    public static final List<String> KEYS = List.of("caps", "jvm", "jars", "java");

    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");

    /** {@code toml} with {@code add} (key → entries) in its [trust]. */
    public static String apply(String toml, Map<String, List<String>> add) {
        String text = toml == null ? "" : toml;
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isHeader(lines.get(i), "trust")) { header = i; break; }
        }
        if (header < 0) {
            var sb = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) sb.append('\n');
            if (!text.isBlank()) sb.append('\n');
            sb.append("# Who may reach Java: modules that bind a Java class with `cap` (caps),\n");
            sb.append("# write Java by hand (jvm), seeds that bring Java libraries (jars)\n");
            sb.append("# or ship Java sources (java).\n");
            sb.append("# Written by `irij audit --fix` — review it.\n");
            sb.append("[trust]\n");
            for (String k : KEYS) sb.append(k).append(" = ").append(list(add.getOrDefault(k, List.of()))).append('\n');
            return sb.toString();
        }
        int end = lines.size();
        for (int i = header + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("[")) { end = i; break; }
        }
        int insertAt = header + 1;
        for (String k : KEYS) {
            List<String> more = add.getOrDefault(k, List.of());
            if (more.isEmpty()) continue;
            Pattern keyLine = Pattern.compile("^(\\s*)" + k + "\\s*=(.*)$");
            boolean done = false;
            for (int i = header + 1; i < end; i++) {
                Matcher m = keyLine.matcher(lines.get(i));
                if (!m.matches()) continue;
                String rest = m.group(2);
                int open = rest.indexOf('['), close = rest.indexOf(']');
                if (open < 0 || close < open) {
                    throw new IllegalStateException("[trust] " + k + " spans several lines; add "
                            + list(more) + " to it by hand");
                }
                var items = new ArrayList<String>();
                Matcher s = STRING.matcher(rest.substring(open, close));
                while (s.find()) items.add(s.group(1));
                for (String x : more) if (!items.contains(x)) items.add(x);
                lines.set(i, m.group(1) + k + " = " + list(items) + rest.substring(close + 1));
                done = true;
                break;
            }
            if (!done) {
                lines.add(insertAt++, k + " = " + list(more));
                end++;
            }
        }
        return String.join("\n", lines);
    }

    private static boolean isHeader(String line, String name) {
        String t = line.trim();
        String h = "[" + name + "]";
        return t.equals(h) || (t.startsWith(h) && (t.charAt(h.length()) == ' ' || t.charAt(h.length()) == '#'));
    }

    private static String list(List<String> items) {
        var quoted = new ArrayList<String>();
        for (String i : items) quoted.add("\"" + i + "\"");
        return "[" + String.join(", ", quoted) + "]";
    }

    /** Ordered, de-duplicated key → entries, skipping empty lists. */
    public static Map<String, List<String>> entries(List<String> caps, List<String> jvm, List<String> jars) {
        return entries(caps, jvm, jars, List.of());
    }

    public static Map<String, List<String>> entries(List<String> caps, List<String> jvm, List<String> jars,
                                                    List<String> java) {
        var m = new LinkedHashMap<String, List<String>>();
        if (!caps.isEmpty()) m.put("caps", caps.stream().distinct().toList());
        if (!jvm.isEmpty()) m.put("jvm", jvm.stream().distinct().toList());
        if (!jars.isEmpty()) m.put("jars", jars.stream().distinct().toList());
        if (!java.isEmpty()) m.put("java", java.stream().distinct().toList());
        return m;
    }
}
