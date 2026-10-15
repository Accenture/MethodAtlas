// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.discovery.jvm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;

/**
 * {@link CommentRedactor} for Java source text, built on JavaParser.
 *
 * <p>
 * Every comment (Javadoc, block, line, including orphan and inline comments) is
 * replaced by U+0020 spaces. Line terminators inside comments are kept, so the
 * result has exactly the same length, the same line structure and the same
 * offset for every non-comment token as the input. String literals and text
 * blocks are never touched because comments are located by the parser, not by
 * scanning for {@code //} or {@code /*} sequences.
 * </p>
 *
 * <p>
 * The redactor fails closed: if the text does not parse, if the parser reports
 * a comment without a position, if a reported position does not delimit a
 * comment in the text, or if a comment survives in the result (checked by
 * parsing the result again), a {@link CommentRedactionException} is thrown and
 * no text is returned.
 * </p>
 *
 * <p>
 * Instances are stateless and safe for concurrent use: a fresh {@link JavaParser} is created for every call.
 * </p>
 *
 * @see CommentRedactor
 * @since 4.1.0
 */
public final class JavaCommentRedactor implements CommentRedactor {

    private static final String JAVA_SUFFIX = ".java";
    private static final String LINE_COMMENT_START = "//";
    private static final String BLOCK_COMMENT_START = "/*";
    private static final String BLOCK_COMMENT_END = "*/";

    /**
     * Creates a redactor; required by {@link java.util.ServiceLoader}.
     * It accepts every file whose name ends in {@code .java}.
     */
    public JavaCommentRedactor() {
        // Required by ServiceLoader
    }

    /**
     * Returns the identifier of this redactor, equal to that of
     * {@link JavaTestDiscovery#pluginId()}.
     *
     * @return {@code "java"}
     */
    @Override
    public String pluginId() {
        return "java";
    }

    /**
     * Tells whether {@code sourceFile} is Java source: its name ends in
     * {@code .java} (case-insensitive). Configured suffixes are deliberately not
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
        String name = sourceFile.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(JAVA_SUFFIX);
    }

    /**
     * Replaces every comment in {@code source} with spaces.
     *
     * @param sourceFile the file the text belongs to; used for diagnostics only
     * @param source     Java source text (a compilation unit or a type
     *                   declaration); must not be {@code null}
     * @return text of identical length in which every comment character other
     *         than a line terminator is a space; never {@code null}
     * @throws CommentRedactionException if the text cannot be parsed or the
     *                                   redaction cannot be guaranteed complete
     */
    @Override
    public String redact(Path sourceFile, String source) throws CommentRedactionException {
        List<Comment> comments = parseComments(sourceFile, source);
        char[] chars = source.toCharArray();
        if (!comments.isEmpty()) {
            int[] lineStarts = lineStarts(source);
            for (Comment comment : comments) {
                blank(sourceFile, source, chars, lineStarts, comment);
            }
        }
        String result = new String(chars);
        if (result.length() != source.length()) {
            throw new CommentRedactionException("internal error: redaction changed the length of " + sourceFile);
        }
        if (!comments.isEmpty() && !parseComments(sourceFile, result).isEmpty()) {
            throw new CommentRedactionException("a comment survived redaction in " + sourceFile);
        }
        return result;
    }

    private static List<Comment> parseComments(Path sourceFile, String text) throws CommentRedactionException {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        ParseResult<CompilationUnit> parsed = new JavaParser(cfg).parse(text);
        Optional<CompilationUnit> unit = parsed.getResult();
        if (!parsed.isSuccessful() || unit.isEmpty()) {
            throw new CommentRedactionException("cannot parse " + sourceFile + " to locate comments: "
                    + parsed.getProblems());
        }
        List<Comment> all = new ArrayList<>();
        parsed.getCommentsCollection().ifPresent(c -> all.addAll(c.getComments()));
        // The tree view is merged in as a second source: a comment known to the AST
        // must never be missed even if the lexer-level collection omitted it.
        unit.get().getComment().ifPresent(all::add);
        all.addAll(unit.get().getAllContainedComments());
        // Node equality is structural, so de-duplicate by position, never by equals().
        Map<Optional<Range>, Comment> byRange = new LinkedHashMap<>();
        List<Comment> comments = new ArrayList<>();
        for (Comment comment : all) {
            if (comment.getRange().isEmpty()) {
                comments.add(comment);
            } else {
                byRange.putIfAbsent(comment.getRange(), comment);
            }
        }
        comments.addAll(byRange.values());
        return comments;
    }

    private static void blank(Path sourceFile, String source, char[] chars, int[] lineStarts, Comment comment)
            throws CommentRedactionException {
        Optional<Range> range = comment.getRange();
        if (range.isEmpty()) {
            throw new CommentRedactionException("comment without a source position in " + sourceFile);
        }
        int begin = offset(lineStarts, source.length(), range.get().begin.line, range.get().begin.column);
        int end = offset(lineStarts, source.length(), range.get().end.line, range.get().end.column);
        if (begin < 0 || end < begin || !delimitsComment(source, begin, end)) {
            throw new CommentRedactionException("comment position does not match the text of " + sourceFile
                    + " at line " + range.get().begin.line);
        }
        for (int i = begin; i <= end; i++) {
            if (chars[i] != '\n' && chars[i] != '\r') {
                chars[i] = ' ';
            }
        }
    }

    private static boolean delimitsComment(String source, int begin, int end) {
        boolean block = source.startsWith(BLOCK_COMMENT_START, begin) && end - begin >= 3
                && source.startsWith(BLOCK_COMMENT_END, end - 1);
        return source.startsWith(LINE_COMMENT_START, begin) || block;
    }

    /** Returns the char offset of a 1-based line/column, or -1 when it lies outside the text. */
    private static int offset(int[] lineStarts, int length, int line, int column) {
        if (line < 1 || line > lineStarts.length || column < 1) {
            return -1;
        }
        long offset = (long) lineStarts[line - 1] + column - 1;
        return offset < length ? (int) offset : -1;
    }

    /** Offsets of the first char of every line; a lone CR, LF or CRLF each ends one line. */
    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean crlf = c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n';
            if (c == '\n' || (c == '\r' && !crlf)) {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
