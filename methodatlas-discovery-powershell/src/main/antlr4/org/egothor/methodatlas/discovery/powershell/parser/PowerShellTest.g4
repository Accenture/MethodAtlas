/*
 * PowerShellTest.g4 — Focused PowerShell/Pester grammar for MethodAtlas
 * test-method detection.
 *
 * Handles the Pester DSL constructs:
 *   Describe "suite"  { ... }
 *   Context  "suite"  { ... }
 *   It       "test" [-Tag "a","b"] { ... }
 *
 * Script blocks and all other PowerShell syntax are treated as opaque
 * content so that arbitrary PowerShell code does not affect structural
 * parsing.  The grammar is case-insensitive (It/IT/it all match) via the
 * ANTLR4 caseInsensitive option.
 *
 * This grammar was written independently for this project.  It is not
 * derived from any existing PowerShell grammar.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Egothor and Accenture
 */
grammar PowerShellTest;

options { caseInsensitive = true; }

// ═══════════════════════════════════════════════════════════════════════
// PARSER RULES
// ═══════════════════════════════════════════════════════════════════════

script : element* EOF ;

element
    : pesterBlock
    | itBlock
    | scriptBlock
    | atom
    ;

pesterBlock
    : ( DESCRIBE | CONTEXT ) string_ itArg* scriptBlock
    ;

itBlock
    : IT string_ itArg* scriptBlock
    ;

// -Tag "a", "b" and -SwitchFlag arguments on the It/Describe/Context line
itArg
    : MINUS IDENTIFIER paramValue?
    | string_
    | COMMA
    ;

// Tag value(s): @("a", "b") array form or plain "a", "b" comma-separated form
paramValue
    : AT LPAREN string_ ( COMMA string_ )* RPAREN
    | string_ ( COMMA string_ )*
    ;

scriptBlock : LBRACE scriptContent* RBRACE ;

scriptContent
    : pesterBlock
    | itBlock
    | scriptBlock
    | atom
    ;

// Opaque atom: any single token that is not a brace or EOF
atom : ~( LBRACE | RBRACE | EOF ) ;

string_ : SINGLE_QUOTED | DOUBLE_QUOTED ;

// ═══════════════════════════════════════════════════════════════════════
// LEXER RULES
// ═══════════════════════════════════════════════════════════════════════

// ── Pester keywords (case-insensitive via options block) ──────────────

IT       : 'it'       ;
DESCRIBE : 'describe' ;
CONTEXT  : 'context'  ;

// ── Punctuation ───────────────────────────────────────────────────────

LBRACE : '{' ;
RBRACE : '}' ;
LPAREN : '(' ;
RPAREN : ')' ;
COMMA  : ',' ;
MINUS  : '-' ;
AT     : '@' ;

// ── String literals ───────────────────────────────────────────────────

// PowerShell double-quoted string: backtick is the escape character.
// `" inside the string is a literal quote; any other `<char> is an escape.
// A double-quoted string may span lines, so newlines are allowed: otherwise the
// text of a multi-line string would be lexed as code and its '#' or quote
// characters misread (relevant to the comment redactor).
DOUBLE_QUOTED
    : '"' ( '`' . | ~["`] )* '"'
    ;

// PowerShell single-quoted string: '' is the only escape sequence.
SINGLE_QUOTED
    : '\'' ( '\'\'' | ~'\'' )* '\''
    ;

// Here-strings: @" ... "@ and @' ... '@.  The opening delimiter ends its line
// and the closing delimiter starts a line; everything between is literal text
// that may contain quotes, '#' and '<#'.  Without these rules a quote inside a
// here-string would pair with a quote far away and swallow real code.
HERE_DOUBLE
    : '@"' [ \t]* NL ( .*? NL )? '"@'
    ;

HERE_SINGLE
    : '@\'' [ \t]* NL ( .*? NL )? '\'@'
    ;

fragment NL : '\r'? '\n' ;

// ── Identifier (for -Tag parameter name detection) ────────────────────

IDENTIFIER : [a-z_] [a-z0-9_\-]* ;

// ── Comments ──────────────────────────────────────────────────────────

// Comments go to the HIDDEN channel instead of being discarded: the parser
// ignores them exactly as it ignored skipped tokens, while the comment
// redactor (PowerShellCommentRedactor) can still locate every comment in the
// token stream.  PowerShell has only these two comment forms; the block form
// also carries comment-based help.

LINE_COMMENT  : '#' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '<#' .*? '#>' -> channel(HIDDEN) ;

// ── Whitespace ────────────────────────────────────────────────────────

WS : [ \t\r\n]+ -> skip ;

// ── Catch-all ─────────────────────────────────────────────────────────

// Characters no rule above recognises ('$', '=', '|', '.', ...).  Hidden, so
// the parser sees the same token stream it saw when such characters were
// dropped as lexer errors; as a token it lets the comment redactor tell
// "dropped character" from "quote or escape the lexer failed to understand"
// and fail closed on the latter.  Must stay the LAST lexer rule.
OTHER : . -> channel(HIDDEN) ;
