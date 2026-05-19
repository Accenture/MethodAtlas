package org.egothor.methodatlas.discovery.powershell.internal;

import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.discovery.powershell.parser.PowerShellTestBaseVisitor;
import org.egothor.methodatlas.discovery.powershell.parser.PowerShellTestParser;

/**
 * ANTLR4 visitor that walks a PowerShell/Pester parse tree and collects
 * information about {@code It} test blocks.
 *
 * <p>Instances are single-use: create one per source file, call
 * {@link #visit(org.antlr.v4.runtime.tree.ParseTree)}, then read results via
 * {@link #getDiscoveredCommands()}.</p>
 *
 * <h2>Detection</h2>
 * <p>Every {@code It} block encountered during traversal is recorded,
 * regardless of nesting depth inside {@code Describe} or {@code Context}
 * blocks.  The grammar is case-insensitive so {@code it}, {@code IT}, and
 * {@code It} all match.</p>
 *
 * <h2>Tag extraction</h2>
 * <p>Tags are collected from the {@code -Tag} parameter of the {@code It}
 * line.  Both the array form ({@code -Tag @("a","b")}) and the plain
 * comma-separated form ({@code -Tag "a","b"}) are handled by the grammar
 * and exposed via {@link PowerShellTestParser.ParamValueContext#string_()}.</p>
 */
public final class PowerShellTestVisitor extends PowerShellTestBaseVisitor<Void> {

    private static final int MIN_QUOTED_LENGTH = 2;

    private final List<CommandInfo> discoveredCommands = new ArrayList<>();

    /**
     * Visits an {@code It} block, extracts the test name and any tags, and
     * records the result.  Recursion via {@link #visitChildren(org.antlr.v4.runtime.tree.RuleNode)}
     * ensures that nested {@code It} blocks (rare in practice) are also captured.
     *
     * @param ctx parse-tree context for the {@code It} block
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitItBlock(PowerShellTestParser.ItBlockContext ctx) {
        String name = unquote(ctx.string_());
        List<String> tags = extractTags(ctx.itArg());
        int beginLine = ctx.start.getLine();
        int endLine   = ctx.stop != null ? ctx.stop.getLine() : beginLine;
        discoveredCommands.add(new CommandInfo(name, List.copyOf(tags), beginLine, endLine));
        // Recurse into nested It blocks inside the script block
        return visitChildren(ctx);
    }

    /**
     * All {@code It} blocks found in the file after visiting.
     *
     * @return unmodifiable list of discovered commands
     */
    public List<CommandInfo> getDiscoveredCommands() {
        return List.copyOf(discoveredCommands);
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Removes surrounding quotes from a string literal and un-escapes
     * PowerShell escape sequences.
     *
     * <ul>
     *   <li>Double-quoted: surrounding {@code "} removed; {@code `"} → {@code "}</li>
     *   <li>Single-quoted: surrounding {@code '} removed; {@code ''} → {@code '}</li>
     * </ul>
     *
     * @param ctx string context from the grammar; may be {@code null}
     * @return unquoted string; empty when {@code ctx} is {@code null}
     */
    private static String unquote(PowerShellTestParser.String_Context ctx) {
        if (ctx == null) {
            return "";
        }
        String raw = ctx.getText();
        if (raw.length() < MIN_QUOTED_LENGTH) {
            return raw;
        }
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1).replace("`\"", "\"");
        }
        if (raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        return raw;
    }

    /**
     * Extracts tag values from the {@code itArg} list of an {@code It} block.
     *
     * <p>Looks for an {@code itArg} whose MINUS token and IDENTIFIER token are
     * both present and whose identifier text equals {@code "tag"}
     * (case-insensitive match handled by the grammar's {@code caseInsensitive}
     * option).  The tags are then read from the {@code paramValue} sub-rule
     * that consumes all immediately-following quoted strings.</p>
     *
     * @param args itArg context list from the {@code itBlock} rule
     * @return mutable list of tag strings (possibly empty)
     */
    private static List<String> extractTags(List<PowerShellTestParser.ItArgContext> args) {
        List<String> tags = new ArrayList<>();
        for (PowerShellTestParser.ItArgContext arg : args) {
            if (arg.MINUS() != null
                    && arg.IDENTIFIER() != null
                    && "tag".equalsIgnoreCase(arg.IDENTIFIER().getText())
                    && arg.paramValue() != null) {
                for (PowerShellTestParser.String_Context sc : arg.paramValue().string_()) {
                    tags.add(unquote(sc));
                }
            }
        }
        return tags;
    }
}
