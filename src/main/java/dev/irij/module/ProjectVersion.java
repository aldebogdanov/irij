package dev.irij.module;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Commit-count versioning for Irij projects (replikativ-style).
 *
 * <p>The published / released version of any Irij project is
 * {@code MAJOR.MINOR.<commit-count>}, where {@code MAJOR.MINOR} is the
 * 2-part base declared in {@code irij.toml [project] version} (or, for
 * the engine itself, in {@code build.gradle.kts}) and {@code commit-count}
 * is {@code git rev-list --count HEAD}. The patch number is therefore a
 * monotonically increasing build number derived from history — it never
 * resets when {@code MINOR} bumps, and nobody hand-picks it.
 *
 * <p>Releases come from {@code main} only; that is enforced at the publish
 * boundary, not in the version string. A non-release branch produces a
 * dev/build version {@code MAJOR.MINOR.<count>-<branch>} (SemVer
 * pre-release form) so a local build is visibly not a release — this
 * never reaches the registry because {@code irij publish} refuses any
 * non-{@code main} / dirty tree.
 */
public final class ProjectVersion {

    private ProjectVersion() {}

    /** A 2-part base: {@code MAJOR.MINOR}, each a run of digits. */
    private static final Pattern MAJOR_MINOR = Pattern.compile("\\d+\\.\\d+");
    /** A full commit-count version: {@code MAJOR.MINOR.PATCH}. */
    private static final Pattern MAJOR_MINOR_PATCH = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    public static boolean isMajorMinor(String s) {
        return s != null && MAJOR_MINOR.matcher(s).matches();
    }

    /**
     * Enforce that an {@code irij.toml} base version is exactly
     * {@code MAJOR.MINOR}. Irij projects must NOT pin a patch by hand —
     * the patch is the commit count, appended at publish time.
     *
     * @throws IllegalArgumentException with a user-facing, actionable
     *     message if {@code base} is missing or not 2-part.
     */
    public static void requireMajorMinorBase(String base) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException(
                "irij.toml [project] version is missing.\n"
                + "  Irij projects use commit-count versioning: set a 2-part\n"
                + "  base only, e.g.  version = \"0.2\"  — the patch (commit\n"
                + "  count) is appended automatically at `irij publish`.");
        }
        if (!isMajorMinor(base.trim())) {
            throw new IllegalArgumentException(
                "irij.toml [project] version = \"" + base + "\" is not a 2-part "
                + "MAJOR.MINOR base.\n"
                + "  Irij projects use commit-count versioning: the patch is the\n"
                + "  git commit count, appended automatically at publish time.\n"
                + "  Set a 2-part base, e.g.  version = \"" + suggestBase(base) + "\"\n"
                + "  (publishing `" + base + "` as-is is rejected so a hand-picked\n"
                + "  patch can never collide with the commit-count patch).");
        }
    }

    /** Best-effort {@code MAJOR.MINOR} suggestion for an over-specified base. */
    private static String suggestBase(String base) {
        String[] parts = base.trim().split("\\.");
        if (parts.length >= 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
            return parts[0] + "." + parts[1];
        }
        return "0.1";
    }

    // ── git helpers ─────────────────────────────────────────────────────

    /** {@code git rev-list --count HEAD} in {@code root}; empty if not a
     *  git repo, no commits yet, or git unavailable. */
    public static OptionalInt commitCount(Path root) {
        return git(root, "rev-list", "--count", "HEAD")
                .filter(s -> s.matches("\\d+"))
                .map(s -> OptionalInt.of(Integer.parseInt(s)))
                .orElse(OptionalInt.empty());
    }

    /** Current branch name; empty on detached HEAD or no git. */
    public static Optional<String> branch(Path root) {
        return git(root, "rev-parse", "--abbrev-ref", "HEAD")
                .filter(s -> !s.isBlank() && !s.equals("HEAD"));
    }

    /** True iff the working tree has no uncommitted changes. A non-git
     *  tree is treated as "not clean" (caller should reject). */
    public static boolean isClean(Path root) {
        return git(root, "status", "--porcelain").map(String::isEmpty).orElse(false);
    }

    /** {@code main} and {@code master} are release branches. */
    public static boolean isReleaseBranch(String branch) {
        return "main".equals(branch) || "master".equals(branch);
    }

    // ── version computation ─────────────────────────────────────────────

    /**
     * Release version: always {@code MAJOR.MINOR.<count>}. Used by
     * {@code irij publish} (after it has verified branch + clean tree).
     *
     * @throws IllegalArgumentException if base is not MAJOR.MINOR
     * @throws IllegalStateException if the commit count can't be read
     */
    public static String releaseVersion(Path root, String base) {
        requireMajorMinorBase(base);
        int count = commitCount(root).orElseThrow(() -> new IllegalStateException(
            "cannot read git commit count in " + root + " — is this a git "
            + "repository with at least one commit?"));
        return base.trim() + "." + count;
    }

    /**
     * Build/display version: {@code MAJOR.MINOR.<count>} on a release
     * branch, else {@code MAJOR.MINOR.<count>-<branch>}. Falls back to
     * {@code base + ".0"} (or {@code base} unchanged if base isn't even
     * MAJOR.MINOR) when git is unavailable, so `irij build` never hard-fails
     * outside a repo.
     */
    public static String buildVersion(Path root, String base) {
        if (!isMajorMinor(base == null ? "" : base.trim())) {
            return base; // leave non-conforming bases alone for display
        }
        String b = base.trim();
        OptionalInt count = commitCount(root);
        if (count.isEmpty()) return b + ".0";
        String full = b + "." + count.getAsInt();
        Optional<String> br = branch(root);
        if (br.isPresent() && !isReleaseBranch(br.get())) {
            return full + "-" + sanitizeBranch(br.get());
        }
        return full;
    }

    /** Make a branch name safe for a SemVer pre-release identifier. */
    static String sanitizeBranch(String branch) {
        String s = branch.replaceAll("[^0-9A-Za-z-]+", "-");
        s = s.replaceAll("(^-+)|(-+$)", "");
        return s.isEmpty() ? "dev" : s;
    }

    // ── registry resolution (MAJOR.MINOR → latest patch) ────────────────

    /**
     * Pick the highest commit-count patch in {@code minorBase}'s line.
     *
     * @param available all published version strings for a seed
     * @param minorBase a 2-part {@code MAJOR.MINOR} pin
     * @return the {@code MAJOR.MINOR.PATCH} with the greatest PATCH, or
     *     empty if none of {@code available} is in that line
     */
    public static Optional<String> latestPatch(List<String> available, String minorBase) {
        String prefix = minorBase + ".";
        String best = null;
        long bestPatch = -1;
        for (String v : available) {
            if (v == null || !v.startsWith(prefix)) continue;
            var m = MAJOR_MINOR_PATCH.matcher(v);
            if (!m.matches()) continue;
            // confirm the MAJOR.MINOR actually equals minorBase (prefix
            // alone would let "0.20" match base "0.2")
            if (!(m.group(1) + "." + m.group(2)).equals(minorBase)) continue;
            long patch = Long.parseLong(m.group(3));
            if (patch > bestPatch) { bestPatch = patch; best = v; }
        }
        return Optional.ofNullable(best);
    }

    // ── process plumbing ────────────────────────────────────────────────

    /** Run {@code git <args>} in {@code root}; return trimmed stdout on
     *  exit 0, else empty. Never throws. */
    private static Optional<String> git(Path root, String... args) {
        try {
            var cmd = new java.util.ArrayList<String>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(root.toString());
            cmd.addAll(List.of(args));
            var proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out;
            try (var is = proc.getInputStream()) { out = is.readAllBytes(); }
            int code = proc.waitFor();
            if (code != 0) return Optional.empty();
            return Optional.of(new String(out).trim());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
