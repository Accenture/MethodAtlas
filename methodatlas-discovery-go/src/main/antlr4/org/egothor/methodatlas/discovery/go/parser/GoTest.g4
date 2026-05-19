/*
 * GoTest.g4 — Focused Go grammar for MethodAtlas test-method detection.
 *
 * Handles: package declaration, import declarations, top-level function
 * and method declarations.  Function bodies are treated as opaque
 * balanced-brace content so that arbitrary Go code inside a function does
 * not affect structural parsing.
 *
 * Go test-function convention (go help testfunc):
 *   func TestXxx(t *testing.T)
 *   - name starts with "Test"
 *   - the character immediately after "Test" (if any) must be upper-case or '_'
 *   - exactly one parameter of type *testing.T
 * Benchmark (BenchmarkXxx), Example (ExampleXxx), and Fuzz (FuzzXxx)
 * functions are NOT matched by this grammar's funcDecl visitor.
 *
 * Design note: Go normally inserts semicolons at line endings.  Because
 * this grammar does not parse statements, all whitespace — including
 * newlines — is simply skipped, avoiding the need for a custom lexer
 * semicolon-insertion rule.
 *
 * This grammar was written independently for this project.  It is not
 * derived from the grammars-v4 Go grammar (antlr/grammars-v4, MIT).
 * Key differences: bodies are opaque; semicolons are skipped; no
 * expression or statement rules are present.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Egothor and Accenture
 */
grammar GoTest;

// ═══════════════════════════════════════════════════════════════════════
// PARSER RULES
// ═══════════════════════════════════════════════════════════════════════

sourceFile : topLevelDecl* EOF ;

topLevelDecl
    : packageDecl
    | importDecl
    | funcDecl
    | methodDecl
    | opaqueDecl
    ;

packageDecl : PACKAGE IDENTIFIER ;

importDecl
    : IMPORT ( importSpec | LPAREN importSpec* RPAREN )
    ;

importSpec : ( DOT | IDENTIFIER )? STRING_LIT ;

// Top-level function: func Name TypeParams? Params Result? Body
funcDecl : FUNC IDENTIFIER typeParams? parameters result? funcBody ;

// Method: func (Receiver) Name TypeParams? Params Result? Body
methodDecl : FUNC receiver IDENTIFIER typeParams? parameters result? funcBody ;

receiver : LPAREN paramDecl? RPAREN ;

// Generic type-parameter list: [T any, U comparable]
typeParams : LBRACKET typeParamContent* RBRACKET ;

typeParamContent
    : LBRACKET typeParamContent* RBRACKET
    | ~( LBRACKET | RBRACKET | EOF )
    ;

// Parameter list enclosed in parentheses
parameters : LPAREN paramList? RPAREN ;

// Result can be either a parenthesised parameter list or a bare type
result
    : LPAREN paramList? RPAREN
    | type_
    ;

paramList : paramDecl ( COMMA paramDecl )* COMMA? ;

// paramDecl covers: T, name T, name1 name2 T, ...T, name ...T
paramDecl : identList? ELLIPSIS? type_ ;

identList : IDENTIFIER ( COMMA IDENTIFIER )* ;

// Type expressions; covers all Go type forms needed for function signatures
type_
    : STAR type_                                         // pointer
    | LBRACKET RBRACKET type_                            // slice
    | LBRACKET typeParamContent* RBRACKET type_          // array / generic constraint
    | MAP LBRACKET type_ RBRACKET type_                  // map
    | CHAN ARROW? type_                                   // chan / chan<-
    | ARROW CHAN type_                                    // <-chan
    | FUNC typeParams? parameters result?                // func type
    | STRUCT LBRACE opaqueContent* RBRACE                // struct type
    | INTERFACE LBRACE opaqueContent* RBRACE             // interface type
    | TILDE type_                                        // type constraint ~T
    | typeName typeArgs?                                 // named type / qualified name
    | LPAREN type_ RPAREN                                // parenthesised type
    ;

typeName : IDENTIFIER ( DOT IDENTIFIER )? ;

typeArgs : LBRACKET type_ ( COMMA type_ )* COMMA? RBRACKET ;

// Function body: balanced braces with opaque content
funcBody : LBRACE opaqueContent* RBRACE ;

opaqueContent
    : LBRACE opaqueContent* RBRACE
    | ~( LBRACE | RBRACE | EOF )
    ;

// Top-level opaque declaration: anything that is not a func/package/import
// declaration, used to consume var/const/type blocks and init() bodies.
opaqueDecl : opaqueTopContent+ ;

opaqueTopContent
    : LBRACE opaqueContent* RBRACE
    | ~( FUNC | PACKAGE | IMPORT | LBRACE | RBRACE | EOF )
    ;

// ═══════════════════════════════════════════════════════════════════════
// LEXER RULES
// ═══════════════════════════════════════════════════════════════════════

// ── Keywords ──────────────────────────────────────────────────────────

FUNC      : 'func'      ;
PACKAGE   : 'package'   ;
IMPORT    : 'import'    ;
MAP       : 'map'       ;
CHAN      : 'chan'       ;
STRUCT    : 'struct'    ;
INTERFACE : 'interface' ;

// ── Punctuation ───────────────────────────────────────────────────────

STAR      : '*'   ;
LBRACE    : '{'   ;
RBRACE    : '}'   ;
LPAREN    : '('   ;
RPAREN    : ')'   ;
LBRACKET  : '['   ;
RBRACKET  : ']'   ;
COMMA     : ','   ;
DOT       : '.'   ;
TILDE     : '~'   ;
ARROW     : '<-'  ;
ELLIPSIS  : '...' ;

// ── Identifiers ───────────────────────────────────────────────────────

// Go identifier: Unicode letter or _ followed by letters/digits/_.
// The Unicode property classes [\p{L}\p{Nl}] (letters) and
// [\p{Nd}\p{Mn}\p{Mc}\p{Pc}] (digits and combining marks) match the spec.
IDENTIFIER
    : [\p{L}\p{Nl}_] [\p{L}\p{Nl}\p{Nd}\p{Mn}\p{Mc}\p{Pc}]*
    ;

// ── Literals ──────────────────────────────────────────────────────────

// String literal: interpreted ("...") or raw-string (` ... `).
// Inside interpreted strings, escape sequences are consumed so that
// embedded braces do not appear as LBRACE/RBRACE tokens.
STRING_LIT
    : '"'  ( '\\' . | ~["\\\r\n] )* '"'
    | '`'  ~'`'* '`'
    ;

// Rune literal: 'x' or '\n' etc.
RUNE_LIT : '\'' ( '\\' . | ~['\\\r\n] ) '\'' ;

// Numeric literals consumed so that digits do not interfere with other rules.
INT_LIT   : [0-9] [0-9_xXaAbBcCdDeEfF]* ;
FLOAT_LIT : [0-9]+ '.' [0-9]* | '.' [0-9]+ ;

// ── Comments and whitespace ───────────────────────────────────────────

LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;

// Semicolons are auto-inserted by Go at line endings; since this grammar
// does not parse statements we can simply skip all whitespace including '\n'.
WS : [ \t\r\n]+ -> skip ;
