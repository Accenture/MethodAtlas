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
DOUBLE_QUOTED
    : '"' ( '`' . | ~["`\r\n] )* '"'
    ;

// PowerShell single-quoted string: '' is the only escape sequence.
SINGLE_QUOTED
    : '\'' ( '\'\'' | ~'\'' )* '\''
    ;

// ── Identifier (for -Tag parameter name detection) ────────────────────

IDENTIFIER : [a-z_] [a-z0-9_\-]* ;

// ── Comments ──────────────────────────────────────────────────────────

LINE_COMMENT  : '#' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '<#' .*? '#>' -> skip ;

// ── Whitespace ────────────────────────────────────────────────────────

WS : [ \t\r\n]+ -> skip ;
