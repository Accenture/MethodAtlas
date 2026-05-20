/*
 * ECATTScript.g4 — Focused ecATT grammar for MethodAtlas test detection.
 *
 * Handles exported ecATT script files (.ecl) produced by transaction SECATT.
 * Each FUNCTION block represents one test case; ATTRIBUTES blocks are
 * consumed but ignored for discovery purposes.
 *
 * ecATT is case-insensitive; the `caseInsensitive` option is set accordingly.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Egothor and Accenture
 */
grammar ECATTScript;

options { caseInsensitive = true; }

// ═══════════════════════════════════════════════════════════════════════
// PARSER RULES
// ═══════════════════════════════════════════════════════════════════════

sourceFile : topDecl* EOF ;

topDecl
    : attributesBlock
    | functionBlock
    | otherDecl
    ;

// ATTRIBUTES ... END_ATTRIBUTES
attributesBlock
    : ATTRIBUTES attrContent* END_ATTRIBUTES
    ;

attrContent
    : ~( END_ATTRIBUTES | FUNCTION | EOF )
    ;

// FUNCTION <name> ... DO ... DONE
functionBlock
    : FUNCTION IDENTIFIER funcHeader* doBlock
    ;

funcHeader
    : ~( DO | FUNCTION | DONE | EOF )
    ;

// Balanced DO … DONE block (supports nesting via inner doBlocks).
doBlock
    : DO doContent* DONE
    ;

doContent
    : doBlock
    | ~( DO | DONE | FUNCTION | EOF )
    ;

// Consume anything that is not ATTRIBUTES or FUNCTION at the top level.
otherDecl
    : ~( ATTRIBUTES | FUNCTION | EOF )+
    ;

// ═══════════════════════════════════════════════════════════════════════
// LEXER RULES
// ═══════════════════════════════════════════════════════════════════════

ATTRIBUTES     : 'ATTRIBUTES'     ;
END_ATTRIBUTES : 'END_ATTRIBUTES' ;
FUNCTION       : 'FUNCTION'       ;
DO             : 'DO'             ;
DONE           : 'DONE'           ;

// Character classes use lower-case only because grammar option
// caseInsensitive=true makes [a-z] match both cases.
IDENTIFIER : [a-z_/] [a-z0-9_/]* ;
STRING_LIT : '\'' ( '\'\'' | ~'\'' )* '\'' ;
INT_LIT    : [0-9]+ ;

// LINE_COMMENT and WS MUST appear before the OTHER catch-all so that
// single-character whitespace is skipped rather than emitted as an
// OTHER token (which would break the scanner's adjacency checks).
LINE_COMMENT : '*' ~[\r\n]* -> skip ;
WS : [ \t\r\n]+ -> skip ;

OTHER      : . ;
