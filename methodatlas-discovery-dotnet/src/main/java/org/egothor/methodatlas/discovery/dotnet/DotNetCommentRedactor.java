// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.discovery.dotnet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestLexer;
import org.egothor.methodatlas.util.CommentBlanker;

/**
 * {@link CommentRedactor} for C# source text, built on the ANTLR lexer of the
 * .NET discovery plugin.
 *
 * <p>
 * C# has two comment forms: single-line comments (introduced by two slashes,
 * which includes the three-slash XML documentation comments) and delimited
 * comments (slash-star to star-slash). The grammar's {@code LINE_COMMENT} and
 * {@code BLOCK_COMMENT} rules recognise both. Every such token is replaced by
 * U+0020 spaces; line terminators inside it are kept, so the result has the
 * same length, line structure and token offsets as the input. Regular,
 * verbatim, interpolated and raw string literals and character literals are
 * lexed as single tokens, so comment markers inside them are never touched.
 * </p>
 *
 * <p>
 * Preprocessor directives are not comments and keep their keyword and operands
 * (for example {@code #if DEBUG}, {@code #pragma warning disable CS1591}, {@code #define},
 * {@code #nullable}, and the file name of {@code #line} or {@code #pragma checksum}), because
 * they affect which code is compiled. The free text of {@code #region}/{@code #endregion}
 * (the region name) and of {@code #error}/{@code #warning} (the message) is blanked, keeping only
 * the keyword: it is prose that carries no classification signal and may hold personal
 * data. A single-line comment that trails any other directive (for example
 * {@code #if DEBUG // note}) is a comment and is blanked.
 * </p>
 *
 * <p>
 * The redactor fails closed. A {@link CommentRedactionException} is thrown if
 * the lexer reports an error (an unterminated or unrecognised literal); if a
 * delimited comment is never closed; if a
 * delimited comment trails a directive; if an interpolated string contains a
 * comment marker inside an interpolation hole, or a raw string uses four or
 * more quotes as delimiter (neither is modelled by the lexer, so a comment in
 * them could be missed); or if a comment survives in the result (checked by
 * lexing the result again).
 * </p>
 *
 * <p>
 * Instances are stateless and safe for concurrent use: a fresh lexer is created
 * for every call.
 * </p>
 *
 * @see CommentRedactor
 * @since 4.1.0
 */
public final class DotNetCommentRedactor implements CommentRedactor {

    private static final String CS_SUFFIX = ".cs";
    /** Directives whose text up to the end of the line is free text, never a comment. */
    private static final Set<String> FREE_TEXT_DIRECTIVES = Set.of("region", "endregion", "error", "warning");
    private static final String RAW_DELIMITER = "\"\"\"";
    private static final char QUOTE = '"';
    private static final char OPEN_BRACE = '{';
    private static final char CLOSE_BRACE = '}';

    /**
     * Creates a redactor; required by {@link java.util.ServiceLoader}.
     * It accepts every file whose name ends in {@code .cs}.
     */
    public DotNetCommentRedactor() {
        // Required by ServiceLoader
    }

    /**
     * Returns the identifier of this redactor, equal to that of
     * {@link DotNetTestDiscovery#pluginId()}.
     *
     * @return {@code "dotnet"}
     */
    @Override
    public String pluginId() {
        return "dotnet";
    }

    /**
     * Tells whether {@code sourceFile} is C# source: its name ends in
     * {@code .cs} (case-insensitive). Configured suffixes are deliberately not
     * consulted: unscoped suffixes are shared by all plugins and would let this
     * redactor claim another language's files.
     *
     * @param sourceFile path to test; may be {@code null}
     * @return {@code true} if this redactor handles the file
     */
    @Override
    public boolean supports(Path sourceFile) {
        if (sourceFile == null || sourceFile.getFileName() == null) {
            return false;
        }
        return sourceFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(CS_SUFFIX);
    }

    /**
     * Replaces every comment in {@code source} with spaces.
     *
     * @param sourceFile the file the text belongs to; used for diagnostics only
     * @param source     C# source text; must not be {@code null}
     * @return text of identical length in which every comment character other
     *         than a line terminator is a space; never {@code null}
     * @throws CommentRedactionException if the text cannot be lexed
     *                                   unambiguously or the redaction cannot
     *                                   be guaranteed complete
     */
    @Override
    public String redact(Path sourceFile, String source) throws CommentRedactionException {
        List<int[]> spans = lexComments(sourceFile, source);
        CommentBlanker blanker = new CommentBlanker(source);
        for (int[] span : spans) {
            blanker.blankChars(span[0], span[1]);
        }
        String result = blanker.result();
        if (!spans.isEmpty() && !lexComments(sourceFile, result).isEmpty()) {
            throw new CommentRedactionException("a comment survived redaction in " + sourceFile);
        }
        return result;
    }

    /**
     * Lexes {@code text} and returns the {@code [from, to)} {@code char} ranges
     * of every comment, after checking that nothing the lexer could not
     * classify may hide a comment.
     */
    private static List<int[]> lexComments(Path sourceFile, String text) throws CommentRedactionException {
        List<String> lexerErrors = new ArrayList<>();
        CSharpTestLexer lexer = new CSharpTestLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                lexerErrors.add(line + ":" + charPositionInLine + " " + msg);
            }
        });
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        if (!lexerErrors.isEmpty()) {
            throw new CommentRedactionException("cannot lex " + sourceFile + ": " + lexerErrors.get(0));
        }
        CommentBlanker positions = new CommentBlanker(text);
        List<int[]> spans = new ArrayList<>();
        for (Token token : stream.getTokens()) {
            int type = token.getType();
            int from = positions.charIndex(token.getStartIndex());
            int to = positions.charIndex(token.getStopIndex() + 1);
            if (type == CSharpTestLexer.LINE_COMMENT || type == CSharpTestLexer.BLOCK_COMMENT) {
                spans.add(new int[] {from, to});
            } else if (type == CSharpTestLexer.PREPROCESSOR) {
                int comment = directiveCommentStart(sourceFile, token);
                if (comment >= 0) {
                    spans.add(new int[] {from + comment, to});
                }
            } else if (type == CSharpTestLexer.DIV && to < text.length() && text.charAt(to) == '*') {
                throw new CommentRedactionException("unterminated delimited comment in " + sourceFile
                        + " at line " + token.getLine());
            } else if (type == CSharpTestLexer.RAW_STRING) {
                rejectLongRawDelimiter(sourceFile, token, text, to);
            } else if (type == CSharpTestLexer.INTERPOLATED_STRING) {
                rejectLongRawDelimiter(sourceFile, token, text, to);
                rejectCommentInHole(sourceFile, token);
            }
        }
        return spans;
    }

    /**
     * Returns the offset within a directive token from which text is blanked to
     * the end of the token, or {@code -1} if nothing is blanked: the free text of
     * {@code #region}/{@code #endregion}/{@code #error}/{@code #warning}, or the trailing
     * single-line comment of any other directive.
     */
    private static int directiveCommentStart(Path sourceFile, Token directive) throws CommentRedactionException {
        String text = directive.getText();
        int i = 1;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int nameStart = i;
        while (i < text.length() && Character.isLetter(text.charAt(i))) {
            i++;
        }
        if (FREE_TEXT_DIRECTIVES.contains(text.substring(nameStart, i))) {
            // Keyword kept, free text blanked (it carries no classification signal and may hold
            // personal data); an already blank remainder yields no span, which keeps redact idempotent.
            return text.substring(i).isBlank() ? -1 : i;
        }
        boolean quoted = false;
        for (; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c == QUOTE) {
                quoted = !quoted;
            } else if (!quoted && c == '/' && text.charAt(i + 1) == '/') {
                return i;
            } else if (!quoted && c == '/' && text.charAt(i + 1) == '*') {
                throw new CommentRedactionException("delimited comment in a preprocessor directive in "
                        + sourceFile + " at line " + directive.getLine());
            }
        }
        return -1;
    }

    /**
     * Fails for a raw string that opens or closes with four or more quotes: the
     * lexer ends the token at the first three, so the rest would be lexed as code.
     */
    private static void rejectLongRawDelimiter(Path sourceFile, Token token, String text, int tokenEnd)
            throws CommentRedactionException {
        String body = token.getText();
        int q = 0;
        while (q < body.length() && body.charAt(q) == '$') {
            q++;
        }
        if (!body.startsWith(RAW_DELIMITER, q)) {
            return;
        }
        boolean longOpener = body.length() > q + RAW_DELIMITER.length()
                && body.charAt(q + RAW_DELIMITER.length()) == '"';
        boolean longCloser = tokenEnd < text.length() && text.charAt(tokenEnd) == '"';
        if (longOpener || longCloser) {
            throw new CommentRedactionException("raw string with four or more quote delimiters in "
                    + sourceFile + " at line " + token.getLine());
        }
    }

    /**
     * Fails if a comment marker appears inside an interpolation hole of an
     * interpolated string: the lexer treats the whole string as one token, so
     * a real comment in a hole would not be found. Markers in the literal part
     * (for example a URL) are fine. Braces are counted individually, which is
     * conservative for escaped and raw-string braces.
     */
    private static void rejectCommentInHole(Path sourceFile, Token token) throws CommentRedactionException {
        String body = token.getText();
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == OPEN_BRACE) {
                depth++;
            } else if (c == CLOSE_BRACE) {
                depth--;
            } else if (depth > 0 && c == '/' && i + 1 < body.length()
                    && (body.charAt(i + 1) == '/' || body.charAt(i + 1) == '*')) {
                throw new CommentRedactionException("comment marker inside an interpolation hole in "
                        + sourceFile + " at line " + token.getLine());
            }
        }
    }
}
