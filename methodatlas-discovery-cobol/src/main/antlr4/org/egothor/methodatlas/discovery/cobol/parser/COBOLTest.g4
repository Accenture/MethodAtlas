/*
 * COBOLTest.g4 — Focused COBOL grammar for MethodAtlas test detection.
 *
 * Supports two test conventions:
 *
 *   1. Micro Focus MFUnit — paragraphs whose names start with MFU-TC-
 *      in the PROCEDURE DIVISION.  Each such paragraph is one test case.
 *
 *   2. COBOL-Check — TestSuite / TestCase directive keywords (typically
 *      found in .cut files alongside the COBOL source, or embedded inside
 *      specially marked comment blocks).
 *
 * COBOL is case-insensitive; the `caseInsensitive` option is set accordingly.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Egothor and Accenture
 */
grammar COBOLTest;

options { caseInsensitive = true; }

// ═══════════════════════════════════════════════════════════════════════
// PARSER RULES
// ═══════════════════════════════════════════════════════════════════════

sourceFile : element* EOF ;

element
    : mfunitParagraph
    | cobolCheckCase
    | cobolCheckSuite
    | otherElement
    ;

// ── MFUnit ────────────────────────────────────────────────────────────
// Paragraph names that start with MFU-TC- are MFUnit test cases.
// The paragraph ends before the next MFU-TC- paragraph, TestCase keyword,
// or end of file.

mfunitParagraph
    : MFU_TC_ID PERIOD
      paragraphContent*
    ;

paragraphContent
    : ~( MFU_TC_ID | TESTCASE | TESTSUITE | EOF )
    ;

// ── COBOL-Check ───────────────────────────────────────────────────────
// TestSuite 'name'   — suite declaration (recorded for context, not emitted)
// TestCase  'name'   — individual test case

cobolCheckSuite
    : TESTSUITE string_
    ;

cobolCheckCase
    : TESTCASE string_
      caseContent*
    ;

caseContent
    : ~( TESTCASE | TESTSUITE | MFU_TC_ID | EOF )
    ;

string_
    : QUOTED_STRING
    | IDENTIFIER
    ;

// ── Opaque ────────────────────────────────────────────────────────────

otherElement
    : ~( MFU_TC_ID | TESTCASE | TESTSUITE | EOF )+
    ;

// ═══════════════════════════════════════════════════════════════════════
// LEXER RULES
// ═══════════════════════════════════════════════════════════════════════

// MFUnit paragraph prefix — matched before generic IDENTIFIER.
// Character classes use lower-case only because grammar option
// caseInsensitive=true makes [a-z] match both cases.
MFU_TC_ID  : 'MFU-TC-' [a-z0-9\-]+ ;

// COBOL-Check keywords
TESTSUITE  : 'TESTSUITE' ;
TESTCASE   : 'TESTCASE'  ;
SECTION    : 'SECTION'   ;
DIVISION   : 'DIVISION'  ;
PROCEDURE  : 'PROCEDURE' ;

PERIOD : '.' ;
COMMA  : ',' ;

// COBOL identifiers (letters, digits, hyphens; cannot start/end with hyphen)
IDENTIFIER : [a-z_] [a-z0-9_\-]* ;

// Both single-quoted and double-quoted strings
QUOTED_STRING
    : '\'' ( '\'\'' | ~'\'' )* '\''
    | '"'  ( '""'  | ~'"'  )* '"'
    ;

INT_LIT : [0-9]+ ;
OTHER   : . ;

// Fixed-form column 7 asterisk comments and free-form line comments
LINE_COMMENT : '*>' ~[\r\n]* -> skip ;
WS : [ \t\r\n]+ -> skip ;
