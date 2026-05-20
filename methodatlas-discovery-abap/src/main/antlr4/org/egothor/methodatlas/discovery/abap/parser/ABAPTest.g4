/*
 * ABAPTest.g4 — Focused ABAP grammar for MethodAtlas ABAP Unit test detection.
 *
 * Handles: class definitions (CLASS … DEFINITION … FOR TESTING … ENDCLASS)
 * and class implementations (CLASS … IMPLEMENTATION … ENDCLASS), locating
 * method declarations marked FOR TESTING and their corresponding method
 * implementations for precise line-number extraction.
 *
 * ABAP is case-insensitive; the `caseInsensitive` option is set accordingly.
 *
 * Method bodies and non-method class members are treated as opaque token
 * sequences so that arbitrary ABAP code inside them does not affect
 * structural parsing.
 *
 * This grammar was written independently for this project and is not
 * derived from any third-party ABAP grammar.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Egothor and Accenture
 */
grammar ABAPTest;

options { caseInsensitive = true; }

// ═══════════════════════════════════════════════════════════════════════
// PARSER RULES
// ═══════════════════════════════════════════════════════════════════════

sourceFile : topDecl* EOF ;

topDecl
    : classDef
    | classImpl
    | opaqueDecl
    ;

// ── CLASS DEFINITION ────────────────────────────────────────────────
//
// CLASS <name> DEFINITION [PUBLIC] [FINAL] [FOR TESTING] [...].
//   PUBLIC SECTION.
//     METHODS: test_foo FOR TESTING, ...
//   PROTECTED SECTION.
//   PRIVATE SECTION.
// ENDCLASS.

classDef
    : CLASS IDENTIFIER DEFINITION classDefAttr* PERIOD
      classSec*
      ENDCLASS PERIOD
    ;

classDefAttr
    : FOR TESTING
    | RISK LEVEL? IDENTIFIER
    | DURATION IDENTIFIER
    | INHERITING FROM IDENTIFIER
    | CREATE ( PUBLIC | PROTECTED | PRIVATE )
    | PUBLIC | FINAL | ABSTRACT
    ;

classSec
    : ( PUBLIC | PROTECTED | PRIVATE ) SECTION PERIOD
      classMember*
    ;

classMember
    : METHODS COLON? methodDecl ( COMMA methodDecl )* PERIOD
    | classMemberToken+ PERIOD
    ;

classMemberToken
    : ~( METHODS | PUBLIC | PROTECTED | PRIVATE | ENDCLASS | PERIOD | EOF )
    ;

methodDecl
    : IDENTIFIER methodDeclAttr*
    ;

methodDeclAttr
    : FOR TESTING
    | FOR EVENT IDENTIFIER OF IDENTIFIER
    | IMPORTING | EXPORTING | CHANGING | RETURNING | RAISING | EXCEPTIONS
    | ABSTRACT | FINAL | STATIC | REDEFINITION
    | DEFAULT ( IGNORE | FAIL )
    | TYPE typeName
    | VALUE LPAREN IDENTIFIER RPAREN
    | OPTIONAL
    | IDENTIFIER
    ;

typeName : IDENTIFIER ( DASH IDENTIFIER )* ;

// ── CLASS IMPLEMENTATION ─────────────────────────────────────────────
//
// CLASS <name> IMPLEMENTATION.
//   METHOD <name>.  …  ENDMETHOD.
// ENDCLASS.

classImpl
    : CLASS IDENTIFIER IMPLEMENTATION PERIOD
      methodImpl*
      ENDCLASS PERIOD
    ;

methodImpl
    : METHOD IDENTIFIER PERIOD
      implContent*
      ENDMETHOD PERIOD
    ;

implContent
    : ~( METHOD | ENDMETHOD | ENDCLASS | EOF )
    ;

// ── OPAQUE TOP-LEVEL DECLARATIONS ────────────────────────────────────
// Consumes top-level ABAP statements that are not CLASS/ENDCLASS blocks.

opaqueDecl : opaqueTop+ ;

opaqueTop
    : ~( CLASS | ENDCLASS | EOF )
    ;

// ═══════════════════════════════════════════════════════════════════════
// LEXER RULES
// ═══════════════════════════════════════════════════════════════════════

// ── Keywords ──────────────────────────────────────────────────────────

CLASS          : 'CLASS'          ;
ENDCLASS       : 'ENDCLASS'       ;
DEFINITION     : 'DEFINITION'     ;
IMPLEMENTATION : 'IMPLEMENTATION' ;
FOR            : 'FOR'            ;
TESTING        : 'TESTING'        ;
RISK           : 'RISK'           ;
LEVEL          : 'LEVEL'          ;
DURATION       : 'DURATION'       ;
INHERITING     : 'INHERITING'     ;
FROM           : 'FROM'           ;
CREATE         : 'CREATE'         ;
PUBLIC         : 'PUBLIC'         ;
PROTECTED      : 'PROTECTED'      ;
PRIVATE        : 'PRIVATE'        ;
FINAL          : 'FINAL'          ;
ABSTRACT       : 'ABSTRACT'       ;
SECTION        : 'SECTION'        ;
METHODS        : 'METHODS'        ;
METHOD         : 'METHOD'         ;
ENDMETHOD      : 'ENDMETHOD'      ;
IMPORTING      : 'IMPORTING'      ;
EXPORTING      : 'EXPORTING'      ;
CHANGING       : 'CHANGING'       ;
RETURNING      : 'RETURNING'      ;
RAISING        : 'RAISING'        ;
EXCEPTIONS     : 'EXCEPTIONS'     ;
STATIC         : 'STATIC'         ;
REDEFINITION   : 'REDEFINITION'   ;
DEFAULT        : 'DEFAULT'        ;
IGNORE         : 'IGNORE'         ;
FAIL           : 'FAIL'           ;
EVENT          : 'EVENT'          ;
OF             : 'OF'             ;
TYPE           : 'TYPE'           ;
VALUE          : 'VALUE'          ;
OPTIONAL       : 'OPTIONAL'       ;

// ── Punctuation ───────────────────────────────────────────────────────

PERIOD  : '.' ;
COMMA   : ',' ;
COLON   : ':' ;
DASH    : '-' ;
LPAREN  : '(' ;
RPAREN  : ')' ;

// ── Identifiers ───────────────────────────────────────────────────────
// ABAP identifiers: letters, digits, underscore, forward-slash (namespace),
// tilde (interface method reference).

IDENTIFIER : [a-zA-Z_/] [a-zA-Z0-9_/~]* ;

// ── Literals ──────────────────────────────────────────────────────────

STRING_LIT : '\'' ( '\'\'' | ~'\'' )* '\'' ;
INT_LIT    : [0-9]+ ;

// ── Operators and other characters (consumed as opaque tokens) ────────

OTHER : . ;

// ── Comments and whitespace ───────────────────────────────────────────

// ABAP line comment starts with " (double-quote).
LINE_COMMENT : '"' ~[\r\n]* -> skip ;
WS : [ \t\r\n]+ -> skip ;
