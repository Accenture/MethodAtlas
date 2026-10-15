// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.discovery.powershell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.egothor.methodatlas.discovery.powershell.parser.PowerShellTestLexer;
import org.egothor.methodatlas.util.CommentBlanker;

/**
 * {@link CommentRedactor} for PowerShell source text, built on the ANTLR lexer
 * of the PowerShell discovery plugin.
 *
 * <p>
 * PowerShell has two comment forms: {@code #} line comments and
 * {@code <# ... #>} block comments (which also carry comment-based help). The
 * grammar's {@code LINE_COMMENT} and {@code BLOCK_COMMENT} rules recognise both.
 * Every such token is replaced by U+0020 spaces; line terminators inside it are
 * kept, so the result has the same length, line structure and token offsets as
 * the input. Single-quoted, double-quoted and here-strings are lexed as single
 * tokens, so comment markers inside them are never touched.
 * </p>
 *
 * <p>
 * A {@code #requires} line is a directive for the PowerShell engine, not a
 * comment, and is left unredacted only if it matches the strict {@code #requires}
 * grammar: one or more of {@code -Version <n.n>}, {@code -PSEdition Core|Desktop},
 * {@code -RunAsAdministrator}, {@code -Modules}, {@code -Assembly} and {@code -ShellId}
 * with bare-name or module-hashtable operands, and nothing else. The line must start
 * with the directive, preceded by white space only. Any other {@code #requires ...}
 * text (free prose, unknown options) is an ordinary comment and is blanked.
 * </p>
 *
 * <p>
 * A {@code #} or {@code <#} inside a {@code $( ... )} sub-expression of a
 * double-quoted or here-string is lexed inside the string token and would be missed,
 * so such a string is rejected (fail closed).
 * </p>
 *
 * <p>
 * The redactor fails closed. A {@link CommentRedactionException} is thrown if
 * the lexer reports an error; if a quote, a back-tick escape of a quote or
 * comment marker, or the start of a block comment is left unrecognised (an
 * unterminated string or block comment); if a typographic quote
 * (U+2018..U+201E), which PowerShell treats as a quote character, occurs where
 * it could end a string unexpectedly; or if a comment token survives in the
 * result (checked by lexing the result again).
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
public final class PowerShellCommentRedactor implements CommentRedactor {

    private static final String PS1_SUFFIX = ".ps1";
    private static final char OPEN_PAREN = '(';
    private static final char CLOSE_PAREN = ')';
    private static final char HASH = '#';
    /** A version number such as {@code 5}, {@code 5.1} or {@code 7.4.1.0}. */
    private static final String REQ_VERSION = "\\d+(?:\\.\\d+){0,3}";
    /** A bare module, assembly or shell identifier: no white space, so no prose. */
    private static final String REQ_NAME = "[\\w.\\-:\\\\/]+";
    private static final String REQ_KEY =
            "(?:ModuleName|ModuleVersion|RequiredVersion|MaximumVersion|GUID)\\s*=\\s*['\"]?[\\w.\\-{}]+['\"]?";
    /** A module specification: a bare name or a hashtable of the fixed module keys. */
    private static final String REQ_ITEM =
            "(?:" + REQ_NAME + "|@\\{\\s*" + REQ_KEY + "(?:\\s*[;,]\\s*" + REQ_KEY + ")*\\s*;?\\s*\\})";
    private static final String REQ_LIST = REQ_ITEM + "(?:\\s*,\\s*" + REQ_ITEM + ")*";
    /** One {@code #requires} option with its operand, per the documented grammar. */
    private static final String REQ_OPTION = "(?:-Version\\s+" + REQ_VERSION
            + "|-PSEdition\\s+(?:Core|Desktop)"
            + "|-RunAsAdministrator"
            + "|-Modules\\s+" + REQ_LIST
            + "|-Assembly\\s+" + REQ_LIST
            + "|-ShellId\\s+" + REQ_NAME + ")";
    /**
     * Strict {@code #requires} grammar: the keyword followed by one or more options and nothing else.
     * Anything else starting with {@code #requires} is an ordinary comment.
     */
    private static final Pattern REQUIRES_DIRECTIVE = Pattern
            .compile("#requires(?:\\s+" + REQ_OPTION + ")+\\s*", Pattern.CASE_INSENSITIVE);
    /** Typographic single quotes, which PowerShell treats as single-quote characters. */
    private static final String SMART_SINGLE_QUOTES = "‘’‚‛";
    /** Typographic double quotes, which PowerShell treats as double-quote characters. */
    private static final String SMART_DOUBLE_QUOTES = "“”„";

    /**
     * Creates a redactor; required by {@link java.util.ServiceLoader}.
     * It accepts every file whose name ends in {@code .ps1}.
     */
    public PowerShellCommentRedactor() {
        // Required by ServiceLoader
    }

    /**
     * Returns the identifier of this redactor, equal to that of
     * {@link PowerShellTestDiscovery#pluginId()}.
     *
     * @return {@code "powershell"}
     */
    @Override
    public String pluginId() {
        return "powershell";
    }

    /**
     * Tells whether {@code sourceFile} is a PowerShell script: its name ends in
     * {@code .ps1} (case-insensitive), which includes {@code *.Tests.ps1}.
     * Configured suffixes are deliberately not consulted: unscoped suffixes are
     * shared by all plugins and would let this redactor claim another
     * language's files.
     *
     * @param sourceFile path to test; may be {@code null}
     * @return {@code true} if this redactor handles the file
     */
    @Override
    public boolean supports(Path sourceFile) {
        if (sourceFile == null || sourceFile.getFileName() == null) {
            return false;
        }
        return sourceFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PS1_SUFFIX);
    }

    /**
     * Replaces every comment in {@code source} with spaces, except
     * {@code #requires} directives.
     *
     * @param sourceFile the file the text belongs to; used for diagnostics only
     * @param source     PowerShell source text; must not be {@code null}
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
     * Lexes {@code text} and returns the comment tokens that must be blanked,
     * after checking that nothing the lexer could not classify may hide a
     * comment.
     */
    private static List<Token> lexComments(Path sourceFile, String text) throws CommentRedactionException {
        List<String> lexerErrors = new ArrayList<>();
        PowerShellTestLexer lexer = new PowerShellTestLexer(CharStreams.fromString(text));
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
        List<Token> comments = new ArrayList<>();
        for (Token token : stream.getTokens()) {
            int type = token.getType();
            if (type == PowerShellTestLexer.LINE_COMMENT) {
                if (!isRequiresDirective(token, text, positions)) {
                    comments.add(token);
                }
            } else if (type == PowerShellTestLexer.BLOCK_COMMENT) {
                comments.add(token);
            } else if (type == PowerShellTestLexer.OTHER) {
                rejectUnrecognisedOpener(sourceFile, token, text, positions);
            } else if (type == PowerShellTestLexer.SINGLE_QUOTED) {
                rejectSmartQuote(sourceFile, token, SMART_SINGLE_QUOTES);
            } else if (type == PowerShellTestLexer.DOUBLE_QUOTED) {
                rejectSmartQuote(sourceFile, token, SMART_DOUBLE_QUOTES);
                rejectCommentInSubExpression(sourceFile, token);
            } else if (type == PowerShellTestLexer.HERE_DOUBLE) {
                rejectCommentInSubExpression(sourceFile, token);
            }
        }
        return comments;
    }

    /** A {@code #requires} line that starts its line (after white space only) is a directive. */
    private static boolean isRequiresDirective(Token token, String text, CommentBlanker positions)
            throws CommentRedactionException {
        if (!REQUIRES_DIRECTIVE.matcher(token.getText()).matches()) {
            return false;
        }
        int i = positions.charIndex(token.getStartIndex()) - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i--;
        }
        return i < 0 || text.charAt(i) == '\n' || text.charAt(i) == '\r';
    }

    /**
     * Fails if an unrecognised character could be the start of a string, a
     * quote escape or a block comment that the lexer did not understand; text
     * after it may then be misclassified.
     */
    private static void rejectUnrecognisedOpener(Path sourceFile, Token other, String text,
            CommentBlanker positions) throws CommentRedactionException {
        String ch = other.getText();
        int next = positions.charIndex(other.getStopIndex() + 1);
        char following = next < text.length() ? text.charAt(next) : '\0';
        boolean quote = "\"".equals(ch) || "'".equals(ch)
                || (ch.length() == 1 && (SMART_SINGLE_QUOTES + SMART_DOUBLE_QUOTES).indexOf(ch.charAt(0)) >= 0);
        boolean escapedOpener = "`".equals(ch) && "\"'#<".indexOf(following) >= 0;
        boolean blockOpener = "<".equals(ch) && following == '#';
        if (quote || escapedOpener || blockOpener) {
            throw new CommentRedactionException("unterminated or unrecognised literal or comment in "
                    + sourceFile + " at line " + other.getLine());
        }
    }

    /**
     * Fails if an expandable string contains a {@code $( ... )} sub-expression with a
     * {@code #} or {@code <#} inside: the lexer treats the whole string as one token, so a
     * real comment in the sub-expression would be neither found nor blanked. A {@code #} in
     * a literal part of the string, or after the closing parenthesis, is fine. Parentheses
     * are counted individually (conservative for quoted parentheses), a back-tick-escaped
     * {@code $} does not open a sub-expression, and an unclosed sub-expression runs to the
     * end of the token.
     */
    private static void rejectCommentInSubExpression(Path sourceFile, Token token)
            throws CommentRedactionException {
        String body = token.getText();
        int from = body.indexOf("$(");
        while (from >= 0) {
            if (!isBacktickEscaped(body, from)) {
                int depth = 1;
                for (int i = from + 2; i < body.length() && depth > 0; i++) {
                    char c = body.charAt(i);
                    if (c == OPEN_PAREN) {
                        depth++;
                    } else if (c == CLOSE_PAREN) {
                        depth--;
                    } else if (c == HASH) {
                        throw new CommentRedactionException("comment marker inside a $( ) sub-expression of a "
                                + "string in " + sourceFile + " at line " + token.getLine());
                    }
                }
            }
            from = body.indexOf("$(", from + 2);
        }
    }

    private static boolean isBacktickEscaped(String body, int index) {
        int ticks = 0;
        for (int i = index - 1; i >= 0 && body.charAt(i) == '`'; i--) {
            ticks++;
        }
        return ticks % 2 == 1;
    }

    /** Fails if {@code token} contains a typographic quote that PowerShell would treat as its terminator. */
    private static void rejectSmartQuote(Path sourceFile, Token token, String smartQuotes)
            throws CommentRedactionException {
        String body = token.getText();
        for (int i = 0; i < body.length(); i++) {
            if (smartQuotes.indexOf(body.charAt(i)) >= 0) {
                throw new CommentRedactionException("typographic quote inside a string in " + sourceFile
                        + " at line " + token.getLine());
            }
        }
    }
}
