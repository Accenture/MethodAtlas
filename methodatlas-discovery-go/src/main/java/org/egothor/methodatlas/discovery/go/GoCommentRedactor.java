// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.discovery.go;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.egothor.methodatlas.discovery.go.parser.GoTestLexer;
import org.egothor.methodatlas.util.CommentBlanker;

/**
 * {@link CommentRedactor} for Go source text, built on the ANTLR lexer of the
 * Go discovery plugin.
 *
 * <p>
 * Go has exactly two comment forms, line comments (introduced by two slashes)
 * and general comments (delimited by slash-star and star-slash); the grammar's
 * {@code LINE_COMMENT} and {@code BLOCK_COMMENT} rules recognise both. Every such token is replaced
 * by U+0020 spaces; line terminators inside it are kept, so the result has the
 * same length, line structure and token offsets as the input. Interpreted
 * strings, raw strings and rune literals are lexed as single tokens, so comment
 * markers inside them are never touched.
 * </p>
 *
 * <p>
 * The redactor fails closed. A {@link CommentRedactionException} is thrown if
 * the lexer reports an error, if a quote or a slash-star sequence is left
 * unrecognised (an unterminated string, rune or comment, which could hide a
 * comment from the lexer), or if a comment token survives in the result
 * (checked by lexing the result again).
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
public final class GoCommentRedactor implements CommentRedactor {

    private static final String GO_SUFFIX = ".go";

    /**
     * Creates a redactor; required by {@link java.util.ServiceLoader}.
     * It accepts every file whose name ends in {@code .go}.
     */
    public GoCommentRedactor() {
        // Required by ServiceLoader
    }

    /**
     * Returns the identifier of this redactor, equal to that of
     * {@link GoTestDiscovery#pluginId()}.
     *
     * @return {@code "go"}
     */
    @Override
    public String pluginId() {
        return "go";
    }

    /**
     * Tells whether {@code sourceFile} is Go source: its name ends in
     * {@code .go} (case-insensitive). Configured suffixes are deliberately not
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
        return sourceFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(GO_SUFFIX);
    }

    /**
     * Replaces every comment in {@code source} with spaces.
     *
     * @param sourceFile the file the text belongs to; used for diagnostics only
     * @param source     Go source text; must not be {@code null}
     * @return text of identical length in which every comment character other
     *         than a line terminator is a space; never {@code null}
     * @throws CommentRedactionException if the text cannot be lexed
     *                                   unambiguously or the redaction cannot
     *                                   be guaranteed complete
     */
    @Override
    public String redact(Path sourceFile, String source) throws CommentRedactionException {
        List<Token> comments = lexComments(sourceFile, source);
        CommentBlanker blanker = new CommentBlanker(source);
        for (Token comment : comments) {
            blanker.blank(comment.getStartIndex(), comment.getStopIndex());
        }
        String result = blanker.result();
        if (!comments.isEmpty() && !lexComments(sourceFile, result).isEmpty()) {
            throw new CommentRedactionException("a comment survived redaction in " + sourceFile);
        }
        return result;
    }

    /**
     * Lexes {@code text} and returns its comment tokens after checking that
     * nothing the lexer could not classify may hide a comment.
     */
    private static List<Token> lexComments(Path sourceFile, String text) throws CommentRedactionException {
        List<String> lexerErrors = new ArrayList<>();
        GoTestLexer lexer = new GoTestLexer(CharStreams.fromString(text));
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
        List<Token> comments = new ArrayList<>();
        List<Token> tokens = stream.getTokens();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int type = token.getType();
            if (type == GoTestLexer.LINE_COMMENT || type == GoTestLexer.BLOCK_COMMENT) {
                comments.add(token);
            } else if (type == GoTestLexer.OTHER) {
                rejectUnrecognisedOpener(sourceFile, token, i + 1 < tokens.size() ? tokens.get(i + 1) : null);
            }
        }
        return comments;
    }

    /**
     * Fails if an unrecognised character could be the start of a string, rune
     * or comment the lexer did not understand; text after it may then be
     * misclassified.
     */
    private static void rejectUnrecognisedOpener(Path sourceFile, Token other, Token next)
            throws CommentRedactionException {
        String text = other.getText();
        boolean quote = "\"".equals(text) || "'".equals(text) || "`".equals(text);
        boolean blockOpener = "/".equals(text) && next != null && next.getType() == GoTestLexer.STAR
                && next.getStartIndex() == other.getStopIndex() + 1;
        if (quote || blockOpener) {
            throw new CommentRedactionException("unterminated or unrecognised literal or comment in "
                    + sourceFile + " at line " + other.getLine());
        }
    }
}
