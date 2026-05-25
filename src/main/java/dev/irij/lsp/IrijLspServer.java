package dev.irij.lsp;

import dev.irij.parser.IrijParseDriver;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Irij Language Server — MVP.
 *
 * <p>Entry: {@code irij lsp}. Speaks LSP over stdio (the editor
 * spawns this as a subprocess and reads/writes JSON-RPC framed
 * messages on its stdin/stdout).
 *
 * <p>Capabilities surfaced in {@link #initialize}:
 * <ul>
 *   <li>Text-document sync — FULL (we re-parse the whole file on
 *       every change; incremental sync is a later optimisation).</li>
 *   <li>{@code textDocument/publishDiagnostics} — parse errors
 *       surfaced as red squiggles after every open + change.</li>
 *   <li>{@code textDocument/hover} — placeholder body that shows
 *       the symbol under the cursor + the language version. Real
 *       symbol-aware hover is phase 2b.1.</li>
 *   <li>{@code textDocument/definition} — placeholder returns no
 *       location for now; phase 2b.1 wires it through the AST
 *       fn-decl index.</li>
 * </ul>
 *
 * <p>The MVP deliberately doesn't run the full
 * {@link dev.irij.compiler.IrijCompiler} pipeline (module inliner,
 * EffectRowChecker, bytecode emit) on each keystroke — parsing is
 * fast enough that we re-parse on every {@code didChange}; the
 * heavier passes can come once we wire incremental sync.
 */
public final class IrijLspServer implements LanguageServer,
        TextDocumentService, WorkspaceService {

    /** In-memory mirror of every open document URI → source text.
     *  LSP keeps the editor as the canonical source; the server's
     *  copy is needed for re-parses + hover / goto-def lookups. */
    private final ConcurrentHashMap<String, String> docs = new ConcurrentHashMap<>();

    /** Per-document symbol index, rebuilt on every didOpen/didChange.
     *  Used by hover (signature + kind), definition (jump to decl
     *  location), and completion (in-scope identifiers). */
    private final ConcurrentHashMap<String, List<LspIndex.Symbol>> indices = new ConcurrentHashMap<>();

    private LanguageClient client;

    /** Entry from {@link dev.irij.cli.IrijCli}. Blocks on stdio
     *  until the client disconnects. */
    public static void run() {
        IrijLspServer server = new IrijLspServer();
        Launcher<LanguageClient> launcher = Launcher.createLauncher(
                server, LanguageClient.class, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        try {
            launcher.startListening().get();
        } catch (Exception e) {
            // Client disconnect or transport error — exit cleanly.
        }
    }

    public void connect(LanguageClient client) {
        this.client = client;
    }

    // ── LanguageServer lifecycle ────────────────────────────────────

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);
        caps.setHoverProvider(true);
        caps.setDefinitionProvider(true);
        caps.setCompletionProvider(new CompletionOptions(false, java.util.List.of(".", " ")));
        ServerInfo info = new ServerInfo("Irij Language Server",
                dev.irij.cli.IrijCli.VERSION);
        return CompletableFuture.completedFuture(new InitializeResult(caps, info));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override public void exit() { System.exit(0); }

    @Override public TextDocumentService getTextDocumentService() { return this; }
    @Override public WorkspaceService getWorkspaceService() { return this; }

    // ── TextDocumentService lifecycle ───────────────────────────────

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();
        docs.put(doc.getUri(), doc.getText());
        indices.put(doc.getUri(), LspIndex.build(doc.getText()));
        publishDiagnostics(doc.getUri(), doc.getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // FULL sync — every change carries the entire new text.
        String uri = params.getTextDocument().getUri();
        for (TextDocumentContentChangeEvent ev : params.getContentChanges()) {
            docs.put(uri, ev.getText());
        }
        indices.put(uri, LspIndex.build(docs.get(uri)));
        publishDiagnostics(uri, docs.get(uri));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        docs.remove(uri);
        indices.remove(uri);
    }

    @Override public void didSave(DidSaveTextDocumentParams params) {}

    // ── Diagnostics ─────────────────────────────────────────────────

    private void publishDiagnostics(String uri, String source) {
        if (client == null) return;
        List<Diagnostic> diags = LspDiagnostics.all(source);
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diags));
    }

    // ── Hover (placeholder) ─────────────────────────────────────────

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        String src = docs.get(uri);
        if (src == null) return CompletableFuture.completedFuture(null);
        String word = LspText.wordAt(src, params.getPosition());
        if (word == null || word.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<LspIndex.Symbol> idx = indices.getOrDefault(uri, List.of());
        LspIndex.Symbol sym = LspIndex.findByName(idx, word);
        String body;
        if (sym != null) {
            StringBuilder b = new StringBuilder();
            b.append("```irij\n").append(sym.signature()).append("\n```\n\n");
            if (!sym.docComment().isEmpty()) {
                b.append(sym.docComment()).append("\n\n");
            }
            b.append("*").append(sym.kind().name().toLowerCase()).append("* ")
                    .append("(line ").append(sym.loc().line()).append(")");
            body = b.toString();
        } else {
            body = "`" + word + "` — no definition in this file";
        }
        MarkupContent md = new MarkupContent(MarkupKind.MARKDOWN, body);
        return CompletableFuture.completedFuture(new Hover(md));
    }

    // ── Definition ──────────────────────────────────────────────────

    @Override
    public CompletableFuture<
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends Location>, List<? extends LocationLink>>>
    definition(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        String src = docs.get(uri);
        if (src == null) {
            return CompletableFuture.completedFuture(
                    org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of()));
        }
        String word = LspText.wordAt(src, params.getPosition());
        if (word == null || word.isEmpty()) {
            return CompletableFuture.completedFuture(
                    org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of()));
        }
        List<LspIndex.Symbol> idx = indices.getOrDefault(uri, List.of());
        LspIndex.Symbol sym = LspIndex.findByName(idx, word);
        if (sym == null) {
            return CompletableFuture.completedFuture(
                    org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of()));
        }
        Location loc = new Location(uri,
                LspText.zeroWidthAt(sym.loc().line(), sym.loc().col() + 1));
        return CompletableFuture.completedFuture(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of(loc)));
    }

    // ── Completion: keywords + in-scope identifiers ─────────────────

    @Override
    public CompletableFuture<
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<CompletionItem>, CompletionList>>
    completion(CompletionParams params) {
        List<CompletionItem> items = new java.util.ArrayList<>();
        // Keywords first
        for (String kw : List.of(
                "fn", "pub", "if", "else", "match", "with", "scope",
                "do", "spec", "effect", "handler", "cap", "mod", "use",
                "newtype", "proto", "impl")) {
            CompletionItem it = new CompletionItem(kw);
            it.setKind(CompletionItemKind.Keyword);
            items.add(it);
        }
        // In-scope identifiers from the symbol index
        String uri = params.getTextDocument().getUri();
        for (LspIndex.Symbol s : indices.getOrDefault(uri, List.of())) {
            CompletionItem it = new CompletionItem(s.name());
            it.setKind(switch (s.kind()) {
                case FN -> CompletionItemKind.Function;
                case EFFECT -> CompletionItemKind.Interface;
                case HANDLER, CAP -> CompletionItemKind.Module;
                case SPEC, NEWTYPE -> CompletionItemKind.Struct;
                case PROTO -> CompletionItemKind.Interface;
                case ROLE -> CompletionItemKind.Constant;
            });
            it.setDetail(s.signature());
            items.add(it);
        }
        return CompletableFuture.completedFuture(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(items));
    }

    // ── WorkspaceService stubs ──────────────────────────────────────

    @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {}
    @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
}
