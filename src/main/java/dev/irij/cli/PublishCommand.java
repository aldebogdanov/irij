package dev.irij.cli;

import dev.irij.IrijRuntimeError;
import dev.irij.parser.IrijParseDriver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** {@code irij publish} (alias {@code sow}) — bundle and upload a seed to the registry. */
final class PublishCommand {

    /** Resolve the registry Bearer token: $IRIJ_TOKEN first, then
     *  ~/.config/irij/token (first non-blank line). Null if neither. */
    private static String resolvePublishToken() {
        var env = System.getenv("IRIJ_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        try {
            var tokenFile = Path.of(System.getProperty("user.home"),
                    ".config", "irij", "token");
            if (Files.exists(tokenFile)) {
                for (var line : Files.readAllLines(tokenFile)) {
                    if (!line.isBlank()) return line.trim();
                }
            }
        } catch (Exception ignored) { /* fall through to null */ }
        return null;
    }

    static void run() {
        var projectRoot = Path.of(System.getProperty("user.dir"));
        var tomlFile = projectRoot.resolve("irij.toml");

        if (!Files.exists(tomlFile)) {
            System.err.println("No irij.toml found. Cannot publish without project metadata.");
            System.exit(1);
            return;
        }

        try {
            var result = dev.irij.module.ProjectFile.parseFile(tomlFile);
            var meta = result.meta();

            // Validate required fields
            if (meta == null || meta.name().isBlank() || meta.version().isBlank()
                    || meta.author().isBlank() || meta.description().isBlank()) {
                System.err.println("Error: irij.toml [project] must have name, version, author, and description.");
                System.exit(1);
                return;
            }

            // Optional link fields must be real web URLs — a typo'd link on
            // the registry seed page is worse than none.
            for (var link : new String[][] {
                    {"website", meta.website()}, {"repo", meta.repo()}, {"docs", meta.docs()}}) {
                if (!link[1].isBlank()
                        && !link[1].startsWith("https://") && !link[1].startsWith("http://")) {
                    System.err.println("Error: irij.toml [project] " + link[0]
                        + " must be an http(s):// URL (got '" + link[1] + "').");
                    System.exit(1);
                    return;
                }
            }

            // Commit-count versioning: the [project] version is a 2-part
            // MAJOR.MINOR base; the published version is base + "." + the
            // git commit count. Reject a hand-picked patch (decision: a
            // manual patch must never collide with the commit-count one).
            try {
                dev.irij.module.ProjectVersion.requireMajorMinorBase(meta.version());
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
                return;
            }
            // Releases come from main only, from a clean tree (the branch
            // guard lives here, not in the version string).
            var curBranch = dev.irij.module.ProjectVersion.branch(projectRoot).orElse("");
            if (!dev.irij.module.ProjectVersion.isReleaseBranch(curBranch)) {
                System.err.println("Error: `irij publish` must run on the main branch "
                    + "(on '" + (curBranch.isBlank() ? "<detached/unknown>" : curBranch) + "').");
                System.err.println("  Commit-count releases come from main only; a feature "
                    + "branch would mint a different patch.");
                System.exit(1);
                return;
            }
            if (!dev.irij.module.ProjectVersion.isClean(projectRoot)) {
                System.err.println("Error: working tree is not clean — commit or stash first.");
                System.err.println("  The published version is derived from the commit count, so "
                    + "uncommitted work would be mis-stamped.");
                System.exit(1);
                return;
            }
            // The full MAJOR.MINOR.<count> version used for the rest of publish.
            final String publishVersion =
                    dev.irij.module.ProjectVersion.releaseVersion(projectRoot, meta.version());

            // Reject path deps
            for (var dep : result.deps()) {
                if (dep.source() instanceof dev.irij.module.ProjectFile.DepSource.PathDep) {
                    System.err.println("Error: cannot publish with path seed '" + dep.name()
                        + "'. Convert to registry or git seed first.");
                    System.exit(1);
                    return;
                }
            }

            // Collect .irj files + README + irij.toml
            var filesToBundle = new ArrayList<Path>();
            filesToBundle.add(tomlFile);
            try (var stream = Files.walk(projectRoot, 5)) {
                stream.filter(p -> Files.isRegularFile(p)
                        && !p.startsWith(projectRoot.resolve(".git"))
                        && !p.startsWith(projectRoot.resolve("build"))
                        && !p.startsWith(projectRoot.resolve(".irij"))
                        && (p.toString().endsWith(".irj")
                            || p.getFileName().toString().equalsIgnoreCase("README.md")
                            || p.getFileName().toString().equalsIgnoreCase("README")))
                    .forEach(filesToBundle::add);
            }

            // Build tarball
            var tarball = Files.createTempFile("irij-publish-", ".tar.gz");
            try {
                var tarArgs = new ArrayList<String>();
                tarArgs.add("tar");
                tarArgs.add("czf");
                tarArgs.add(tarball.toString());
                tarArgs.add("-C");
                tarArgs.add(projectRoot.toString());
                for (var f : filesToBundle) {
                    tarArgs.add(projectRoot.relativize(f).toString());
                }
                var pb = new ProcessBuilder(tarArgs)
                    .redirectErrorStream(true);
                var proc = pb.start();
                try (var is = proc.getInputStream()) { is.readAllBytes(); }
                if (proc.waitFor() != 0) {
                    System.err.println("Error creating tarball.");
                    System.exit(1);
                    return;
                }

                // Upload to registry
                var registryUrl = System.getenv().getOrDefault("IRIJ_REGISTRY", "https://irij.online");
                var url = registryUrl + "/api/seeds/publish";

                System.out.println("Publishing " + meta.name() + " " + publishVersion + " ...");

                // Multipart upload: metadata JSON + tarball
                var boundary = "----IrijPublish" + System.currentTimeMillis();
                var metaJson = "{\"name\":" + jsonStr(meta.name())
                    + ",\"version\":" + jsonStr(publishVersion)
                    + ",\"description\":" + jsonStr(meta.description())
                    + ",\"author\":" + jsonStr(meta.author())
                    + ",\"license\":" + jsonStr(meta.license())
                    + ",\"website\":" + jsonStr(meta.website())
                    + ",\"repo\":" + jsonStr(meta.repo())
                    + ",\"docs\":" + jsonStr(meta.docs()) + "}";

                var bodyBaos = new ByteArrayOutputStream();
                bodyBaos.write(("--" + boundary + "\r\n").getBytes());
                bodyBaos.write("Content-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n".getBytes());
                bodyBaos.write(metaJson.getBytes());
                bodyBaos.write(("\r\n--" + boundary + "\r\n").getBytes());
                bodyBaos.write(("Content-Disposition: form-data; name=\"tarball\"; filename=\""
                    + meta.name() + "-" + publishVersion + ".tar.gz\"\r\n"
                    + "Content-Type: application/gzip\r\n\r\n").getBytes());
                bodyBaos.write(Files.readAllBytes(tarball));
                bodyBaos.write(("\r\n--" + boundary + "--\r\n").getBytes());

                // Bearer token for the multi-tenant registry. Sources, in
                // priority order: $IRIJ_TOKEN, then ~/.config/irij/token.
                // Create one at <registry>/dashboard → "Create token".
                var token = resolvePublishToken();
                if (token == null || token.isBlank()) {
                    System.err.println("No registry token found. Publishing requires one.");
                    System.err.println("  1. Create a token at " + registryUrl + "/dashboard");
                    System.err.println("  2. Provide it via either:");
                    System.err.println("       export IRIJ_TOKEN=<token>");
                    System.err.println("       echo <token> > ~/.config/irij/token");
                    System.exit(1);
                    return;
                }

                var client = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + token.trim())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bodyBaos.toByteArray()))
                    .build();

                var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    System.out.println("Published " + meta.name() + " " + publishVersion + " ✓");
                } else {
                    System.err.println("Publish failed (HTTP " + response.statusCode() + "): " + response.body());
                    System.exit(1);
                }
            } finally {
                Files.deleteIfExists(tarball);
            }
        } catch (dev.irij.module.ProjectFile.ParseError e) {
            System.err.println("Error in irij.toml: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error publishing: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private PublishCommand() {}
}
