/*
 * CSharpTest.g4 — Focused C# grammar for MethodAtlas test-method detection.
 *
 * Handles: namespace and type declarations, method declarations with attribute
 * sections, and generic/nullable/array type expressions. Method bodies are
 * treated as opaque balanced-brace content so that arbitrary C# code inside
 * a method does not affect structural parsing.
 *
 * This grammar was written independently for this project based on the C#
 * language specification (ECMA-334). It is not derived from the grammars-v4
 * C# grammars (antlr/grammars-v4, EPL-1.0 + MIT). Key differences: the
 * IDENTIFIER rule uses direct Unicode ranges rather than named Unicode-class
 * fragment rules; naming uses camelCase throughout; the grammar is structural-
 * only (method bodies are opaque); RAW_STRING (C# 11) is supported.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Egothor and Accenture
 */
grammar CSharpTest;

// ═══════════════════════════════════════════════════════════════════════
// PARSER RULES
// ═══════════════════════════════════════════════════════════════════════

compilationUnit
    : externalAliasDirective* usingDirective* globalAttributeSection*
      ( fileScopedNamespaceDeclaration | member* )
      EOF
    ;

externalAliasDirective
    : EXTERN ALIAS identifier SEMI
    ;

usingDirective
    : USING STATIC? ( identifier EQ )? usingTypeName SEMI
    ;

usingTypeName
    : qualifiedName typeArguments?
    | LPAREN tupleElement ( COMMA tupleElement )+ RPAREN
    ;

globalAttributeSection
    : LBRACKET ( ASSEMBLY | MODULE ) COLON attributeList RBRACKET
    ;

fileScopedNamespaceDeclaration
    : NAMESPACE qualifiedName SEMI member*
    ;

member
    : namespaceDeclaration
    | typeDeclaration
    | delegateDeclaration
    ;

namespaceDeclaration
    : NAMESPACE qualifiedName LBRACE usingDirective* member* RBRACE
    ;

// ── Type declarations ─────────────────────────────────────────────────

typeDeclaration
    : attributeSection* modifier* typeKwd identifier typeParameterList?
      ( LPAREN parameterList? RPAREN )?
      ( COLON typeBaseList )?
      typeConstraintClause*
      ( LBRACE typeMember* RBRACE | SEMI )
    ;

typeKwd : CLASS | INTERFACE | STRUCT | RECORD ( CLASS | STRUCT )? | ENUM ;

typeMember
    : typeDeclaration
    | delegateDeclaration
    | methodDeclaration
    | constructorDeclaration
    | destructorDeclaration
    | indexerDeclaration
    | propertyOrFieldDeclaration
    | operatorDeclaration
    | conversionDeclaration
    | eventDeclaration
    ;

// ── Method declaration ────────────────────────────────────────────────

/** Matches: [attrs] mods returnType Name<T>?(params) constraints body */
methodDeclaration
    : attributeSection* modifier*
      returnType memberName typeParameterList?
      LPAREN parameterList? RPAREN
      typeConstraintClause*
      memberBody
    ;

// ── Constructor ───────────────────────────────────────────────────────

constructorDeclaration
    : attributeSection* modifier*
      identifier
      LPAREN parameterList? RPAREN
      constructorInitializer?
      ( block | FATARROW bodyContent* SEMI )
    ;

constructorInitializer
    : COLON ( BASE | THIS ) LPAREN argumentList? RPAREN
    ;

// ── Destructor ────────────────────────────────────────────────────────

destructorDeclaration
    : attributeSection* modifier* TILDE identifier LPAREN RPAREN block
    ;

// ── Property or field ─────────────────────────────────────────────────

propertyOrFieldDeclaration
    : attributeSection* modifier* CONST? type memberName propertyOrFieldBody
    ;

propertyOrFieldBody
    : LBRACE bodyContent* RBRACE ( EQ bodyContent* SEMI )?
    | FATARROW bodyContent* SEMI
    | ( EQ bodyContent* )? SEMI
    ;

// ── Operator overload ─────────────────────────────────────────────────

operatorDeclaration
    : attributeSection* modifier* type OPERATOR overloadableOperator
      LPAREN parameterList? RPAREN block
    ;

overloadableOperator
    : PLUS | MINUS | STAR | DIV | MOD | AMP | PIPE | CARET
    | BANG | TILDE | PLUSPLUS | MINUSMINUS
    | LT | GT | LTE | GTE | EQEQ | NEQ
    | TRUE | FALSE
    | LBRACKET RBRACKET
    ;

// ── Conversion operator ───────────────────────────────────────────────

conversionDeclaration
    : attributeSection* modifier* ( EXPLICIT | IMPLICIT ) OPERATOR type
      LPAREN parameterList? RPAREN block
    ;

// ── Event declaration ─────────────────────────────────────────────────

eventDeclaration
    : attributeSection* modifier* EVENT type memberName
      ( LBRACE bodyContent* RBRACE | SEMI )
    ;

// ── Delegate declaration ──────────────────────────────────────────────

delegateDeclaration
    : attributeSection* modifier* DELEGATE returnType identifier typeParameterList?
      LPAREN parameterList? RPAREN typeConstraintClause* SEMI
    ;

// ── Indexer declaration ───────────────────────────────────────────────

indexerDeclaration
    : attributeSection* modifier* type THIS LBRACKET parameterList? RBRACKET
      propertyOrFieldBody
    ;

// ═══════════════════════════════════════════════════════════════════════
// RETURN TYPE AND TYPE EXPRESSIONS
// ═══════════════════════════════════════════════════════════════════════

returnType : VOID | type ;

type
    : typeAtom typeArguments? typeRankSuffix*
    | LPAREN tupleElement ( COMMA tupleElement )+ RPAREN typeRankSuffix*
    ;

typeAtom
    : qualifiedName
    | primitiveTypeKwd
    ;

primitiveTypeKwd
    : BOOL | BYTE | SBYTE | CHAR_KW | DECIMAL | DOUBLE_KW | FLOAT_KW
    | INT | LONG | OBJECT_KW | SHORT | STRING_KW | UINT | ULONG | USHORT
    | DYNAMIC | VAR
    ;

tupleElement : type IDENTIFIER? ;

typeRankSuffix
    : QUESTION
    | STAR
    | LBRACKET COMMA* RBRACKET
    ;

typeArguments
    : LT typeArgument ( COMMA typeArgument )* GT
    ;
typeArgument : type | QUESTION ;

typeParameterList
    : LT typeParameter ( COMMA typeParameter )* GT
    ;
typeParameter : attributeSection* ( IN | OUT )? identifier ;

typeList     : type ( COMMA type )* ;
typeBaseList : typeBase ( COMMA typeBase )* ;
typeBase     : type ( LPAREN balancedContent* RPAREN )? ;

typeConstraintClause
    : WHERE identifier COLON typeConstraintList
    ;
typeConstraintList : typeConstraint ( COMMA typeConstraint )* ;
typeConstraint
    : NEW LPAREN RPAREN
    | CLASS QUESTION?
    | STRUCT
    | NOTNULL
    | UNMANAGED
    | DEFAULT
    | type
    ;

// ═══════════════════════════════════════════════════════════════════════
// MEMBER AND QUALIFIED NAMES
// ═══════════════════════════════════════════════════════════════════════

/** Supports explicit interface implementation: IInterface.Method */
memberName    : identifier ( DOT identifier )* ;
/** Supports global:: alias qualifier: global::System.Console */
qualifiedName : identifier ( ( DOT | DOUBLE_COLON ) identifier )* ;

/**
 * C# contextual keywords that are valid identifiers in most name positions.
 * Hard keywords (class, namespace, if, …) are excluded; contextual ones
 * that commonly appear as parameter names, method names, or LINQ operators
 * are all listed here so the structural parser does not reject valid code.
 */
identifier
    : IDENTIFIER
    | ALIAS | ASYNC | BY | DEFAULT | DYNAMIC | FROM | GET | GROUP
    | INIT | INTO | JOIN | LET | NAMEOF | NOTNULL | ON | ORDERBY
    | PARTIAL | RECORD | REMOVE | SCOPED | SELECT | SET | UNMANAGED
    | VALUE | VAR | WHERE | WITH | YIELD
    ;

// ═══════════════════════════════════════════════════════════════════════
// MEMBER BODY (opaque block)
// ═══════════════════════════════════════════════════════════════════════

memberBody
    : block
    | FATARROW bodyContent* SEMI
    | SEMI
    ;

/**
 * Opaque block: any tokens between balanced braces.
 * String-literal tokens already contain any { } characters as part of the
 * token text, so they never appear as LBRACE/RBRACE here.
 */
block : LBRACE blockContent* RBRACE ;

blockContent
    : block
    | LBRACKET balancedContent* RBRACKET
    | LPAREN  balancedContent* RPAREN
    | ~( LBRACE | RBRACE | LBRACKET | RBRACKET | LPAREN | RPAREN )
    ;

bodyContent
    : block
    | LBRACKET balancedContent* RBRACKET
    | LPAREN   balancedContent* RPAREN
    | ~( LBRACE | RBRACE | LBRACKET | RBRACKET | LPAREN | RPAREN )
    ;

balancedContent
    : LBRACE   balancedContent* RBRACE
    | LBRACKET balancedContent* RBRACKET
    | LPAREN   balancedContent* RPAREN
    | ~( LBRACE | RBRACE | LBRACKET | RBRACKET | LPAREN | RPAREN )
    ;

// ═══════════════════════════════════════════════════════════════════════
// ATTRIBUTE SECTIONS
// ═══════════════════════════════════════════════════════════════════════

attributeSection
    : LBRACKET ( attributeTarget COLON )? attributeList RBRACKET
    ;

attributeTarget
    : EVENT | RETURN | IDENTIFIER
    ;

attributeList : attribute ( COMMA attribute )* ;

attribute : qualifiedName ( LPAREN attributeArgs? RPAREN )? ;

attributeArgs : attributeArg ( COMMA attributeArg )* ;

attributeArg
    : identifier EQ attributeValue   // named argument: Key = Value
    | attributeValue                 // positional argument
    ;

attributeValue
    : stringLiteral
    | CHAR_LITERAL
    | TRUE | FALSE | NULL
    | MINUS? INTEGER_LITERAL
    | MINUS? REAL_LITERAL
    | qualifiedName
    | TYPEOF LPAREN type RPAREN
    | NAMEOF LPAREN qualifiedName RPAREN
    | NEW qualifiedName ( LBRACKET RBRACKET )? LPAREN ( attributeArgs )? RPAREN
    | LPAREN balancedContent* RPAREN
    ;

stringLiteral
    : STRING_LITERAL
    | VERBATIM_STRING
    | RAW_STRING
    ;

// ═══════════════════════════════════════════════════════════════════════
// PARAMETERS
// ═══════════════════════════════════════════════════════════════════════

parameterList : parameter ( COMMA parameter )* ;

parameter
    : attributeSection* parameterModifier* type identifier?
      ( EQ paramDefault )?
    ;

parameterModifier : REF | OUT | IN | PARAMS | THIS | READONLY | SCOPED ;

paramDefault
    : stringLiteral
    | CHAR_LITERAL
    | TRUE | FALSE | NULL
    | MINUS? INTEGER_LITERAL
    | MINUS? REAL_LITERAL
    | qualifiedName
    | NEW qualifiedName typeArguments? LPAREN balancedContent* RPAREN
    | DEFAULT ( LPAREN type RPAREN )?
    | LPAREN balancedContent* RPAREN
    ;

argumentList : argument ( COMMA argument )* ;
argument     : ( identifier COLON )? ( REF | OUT | IN )? qualifiedName ;

// ═══════════════════════════════════════════════════════════════════════
// MODIFIERS
// ═══════════════════════════════════════════════════════════════════════

modifier
    : PUBLIC | PRIVATE | PROTECTED | INTERNAL
    | STATIC | ABSTRACT | VIRTUAL | OVERRIDE | SEALED
    | PARTIAL | ASYNC | READONLY | EXTERN | UNSAFE
    | VOLATILE | NEW | FIXED | REQUIRED | FILE
    ;

// ═══════════════════════════════════════════════════════════════════════
// LEXER RULES
// ═══════════════════════════════════════════════════════════════════════

// ── Structural keywords ───────────────────────────────────────────────

ABSTRACT   : 'abstract'  ;
ALIAS      : 'alias'     ;
AS         : 'as'        ;
ASSEMBLY   : 'assembly'  ;
ASYNC      : 'async'     ;
BASE       : 'base'      ;
BREAK      : 'break'     ;
BY         : 'by'        ;
CASE       : 'case'      ;
CATCH      : 'catch'     ;
CHECKED    : 'checked'   ;
CLASS      : 'class'     ;
CONST      : 'const'     ;
CONTINUE   : 'continue'  ;
DEFAULT    : 'default'   ;
DELEGATE   : 'delegate'  ;
DO         : 'do'        ;
ELSE       : 'else'      ;
ENUM       : 'enum'      ;
EVENT      : 'event'     ;
EXPLICIT   : 'explicit'  ;
EXTERN     : 'extern'    ;
FALSE      : 'false'     ;
FILE       : 'file'      ;
FINALLY    : 'finally'   ;
FIXED      : 'fixed'     ;
FOR        : 'for'       ;
FOREACH    : 'foreach'   ;
FROM       : 'from'      ;
GET        : 'get'       ;
GOTO       : 'goto'      ;
GROUP      : 'group'     ;
IF         : 'if'        ;
IMPLICIT   : 'implicit'  ;
IN         : 'in'        ;
INIT       : 'init'      ;
INTERFACE  : 'interface' ;
INTERNAL   : 'internal'  ;
INTO       : 'into'      ;
IS         : 'is'        ;
JOIN       : 'join'      ;
LET        : 'let'       ;
LOCK       : 'lock'      ;
MODULE     : 'module'    ;
NAMEOF     : 'nameof'    ;
NAMESPACE  : 'namespace' ;
NEW        : 'new'       ;
NOTNULL    : 'notnull'   ;
NULL       : 'null'      ;
ON         : 'on'        ;
OPERATOR   : 'operator'  ;
ORDERBY    : 'orderby'   ;
OUT        : 'out'       ;
OVERRIDE   : 'override'  ;
PARAMS     : 'params'    ;
PARTIAL    : 'partial'   ;
PRIVATE    : 'private'   ;
PROTECTED  : 'protected' ;
PUBLIC     : 'public'    ;
READONLY   : 'readonly'  ;
RECORD     : 'record'    ;
REF        : 'ref'       ;
REQUIRED   : 'required'  ;
RETURN     : 'return'    ;
SCOPED     : 'scoped'    ;
SEALED     : 'sealed'    ;
SELECT     : 'select'    ;
SET        : 'set'       ;
STATIC     : 'static'    ;
STRUCT     : 'struct'    ;
SWITCH     : 'switch'    ;
THIS       : 'this'      ;
THROW      : 'throw'     ;
TRUE       : 'true'      ;
TRY        : 'try'       ;
TYPEOF     : 'typeof'    ;
UNMANAGED  : 'unmanaged' ;
UNSAFE     : 'unsafe'    ;
USING      : 'using'     ;
VALUE      : 'value'     ;
VIRTUAL    : 'virtual'   ;
VOID       : 'void'      ;
VOLATILE   : 'volatile'  ;
WHERE      : 'where'     ;
WHILE      : 'while'     ;
WITH       : 'with'      ;
YIELD      : 'yield'     ;

// ── Primitive type alias keywords ─────────────────────────────────────

BOOL      : 'bool'    ;
BYTE      : 'byte'    ;
CHAR_KW   : 'char'    ;
DECIMAL   : 'decimal' ;
DOUBLE_KW : 'double'  ;
DYNAMIC   : 'dynamic' ;
FLOAT_KW  : 'float'   ;
INT       : 'int'     ;
LONG      : 'long'    ;
OBJECT_KW : 'object'  ;
SBYTE     : 'sbyte'   ;
SHORT     : 'short'   ;
STRING_KW : 'string'  ;
UINT      : 'uint'    ;
ULONG     : 'ulong'   ;
USHORT    : 'ushort'  ;
VAR       : 'var'     ;

// ── Punctuation and operators ─────────────────────────────────────────

LBRACE       : '{'   ;
RBRACE       : '}'   ;
LBRACKET     : '['   ;
RBRACKET     : ']'   ;
LPAREN       : '('   ;
RPAREN       : ')'   ;
SEMI         : ';'   ;
COMMA        : ','   ;
DOT          : '.'   ;
FATARROW     : '=>'  ;
ARROW        : '->'  ;
TILDE        : '~'   ;
QUESTION     : '?'   ;
COLON        : ':'   ;
DOUBLE_COLON : '::'  ;
EQ           : '='   ;
EQEQ         : '=='  ;
NEQ          : '!='  ;
LT           : '<'   ;
GT           : '>'   ;
LTE          : '<='  ;
GTE          : '>='  ;
PLUS         : '+'   ;
MINUS        : '-'   ;
STAR         : '*'   ;
DIV          : '/'   ;
MOD          : '%'   ;
AMP          : '&'   ;
PIPE         : '|'   ;
CARET        : '^'   ;
BANG         : '!'   ;
PLUSPLUS     : '++'  ;
MINUSMINUS   : '--'  ;
ANDAND       : '&&'  ;
OROR         : '||'  ;
NULL_COND    : '?.'  ;
NULL_COAL    : '??'  ;
NULL_COALEQ  : '??=' ;
PLUSEQ       : '+='  ;
MINUSEQ      : '-='  ;
STAREQ       : '*='  ;
DIVEQ        : '/='  ;
MODEQ        : '%='  ;
AMPEQ        : '&='  ;
PIPEEQ       : '|='  ;
CARETEQ      : '^='  ;
SHIFT_LEFT   : '<<'  ;
SHIFT_RIGHT  : '>>'  ;
AT_SIGN      : '@'   ;
HASH         : '#'   ;

// ── String literals ───────────────────────────────────────────────────

/**
 * Regular double-quoted string. Escape sequences are consumed so that a \"
 * inside the string does not terminate it prematurely.
 */
STRING_LITERAL
    : '"' STRING_CHAR* '"'
    ;

fragment STRING_CHAR
    : ~["\\\r\n]
    | '\\' .
    ;

/**
 * Verbatim string @"..." — the only escape is "" for a literal quote.
 */
VERBATIM_STRING
    : '@"'  ( ~'"' | '""' )* '"'
    ;

/**
 * Raw string literal (C# 11+). We match the most common triple-quote form;
 * longer delimiter sequences ("""", """"") fall through to the same rule
 * because they begin with three quotes. ANTLR4's greedy lexer will match
 * the longest prefix.
 */
RAW_STRING
    : '"""' .*? '"""'
    ;

/**
 * Interpolated strings: simplified treatment. The content between $" and "
 * may contain {} escape pairs {{/}} and {expr} holes. We consume the string
 * as a single token; for our purposes (attribute value extraction) interpolated
 * strings do not appear in attribute arguments.
 */
INTERPOLATED_STRING
    : '$"'  INTERP_CHAR* '"'
    | '$@"' ( ~'"' | '""' )* '"'
    | '@$"' ( ~'"' | '""' )* '"'
    ;

fragment INTERP_CHAR
    : ~["\\\r\n{]
    | '\\' .
    | '{{' | '}}'
    | '{' ~[}]* '}'
    ;

CHAR_LITERAL
    : '\'' ( ~['\\\r\n] | '\\' . ) '\''
    ;

// ── Numeric literals ──────────────────────────────────────────────────

INTEGER_LITERAL
    : ( DEC_DIGITS | HEX_DIGITS | BIN_DIGITS ) INTEGER_SUFFIX?
    ;

fragment DEC_DIGITS : [0-9] [0-9_]* ;
fragment HEX_DIGITS : '0' [xX] [0-9a-fA-F] [0-9a-fA-F_]* ;
fragment BIN_DIGITS : '0' [bB] [01] [01_]* ;
fragment INTEGER_SUFFIX : [uU] [lL]? | [lL] [uU]? ;

REAL_LITERAL
    : DEC_DIGITS '.' DEC_DIGITS EXPONENT? REAL_SUFFIX?
    | '.' DEC_DIGITS EXPONENT? REAL_SUFFIX?
    | DEC_DIGITS EXPONENT REAL_SUFFIX?
    | DEC_DIGITS REAL_SUFFIX
    ;

fragment EXPONENT    : [eE] [+-]? [0-9]+ ;
fragment REAL_SUFFIX : [fFdDmM] ;

// ── Identifier ────────────────────────────────────────────────────────

/**
 * C# identifier, including verbatim identifiers (@keyword) and Unicode.
 */
IDENTIFIER
    : [a-zA-Z_À-ÖØ-öø-˿Ͱ-ͽͿ-῿‌-‍⁰-↏Ⰰ-⿯、-퟿豈-﷏ﷰ-�]
      [a-zA-Z0-9_·̀-ͯ‿-⁀À-ÖØ-öø-˿Ͱ-ͽͿ-῿‌-‍⁰-↏Ⰰ-⿯、-퟿豈-﷏ﷰ-�]*
    | '@' [a-zA-Z_] [a-zA-Z0-9_]*
    ;

// ── Comments and whitespace ───────────────────────────────────────────

LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
PREPROCESSOR  : '#' ~[\r\n]* -> skip ;
WS            : [ \t\r\n\f]+ -> skip ;
