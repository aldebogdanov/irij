lexer grammar IrijLexer;

options {
    superClass = IrijLexerBase;
}

tokens {
    INDENT,
    DEDENT
}

// ─── Keywords ────────────────────────────────────────────────────────
FN          : 'fn' ;
DO          : 'do' ;
IF          : 'if' ;
THEN        : 'then' ;
ELSE        : 'else' ;
MATCH       : 'match' ;
TYPE        : 'type' ;
NEWTYPE     : 'newtype' ;
MOD         : 'mod' ;
USE         : 'use' ;
PUB         : 'pub' ;
WITH        : 'with' ;
SCOPE       : 'scope' ;
EFFECT      : 'effect' ;
HANDLER     : 'handler' ;
CAP         : 'cap' ;
ROLE        : 'role' ;
PRE         : 'pre' ;
POST        : 'post' ;
LAW         : 'law' ;
PROTO       : 'proto' ;
IMPL        : 'impl' ;
FOR         : 'for' ;
SELECT      : 'select' ;
ENCLAVE     : 'enclave' ;
DETACH_BANG : 'detach!' ;
FORALL      : 'forall' ;
CONTRACT    : 'contract' ;
IN          : 'in' ;
OUT         : 'out' ;
LOOP        : 'loop' ;
RECUR       : 'recur' ;
PROOF       : 'proof' ;
OPEN        : ':open' ;
PAR_EACH    : 'par-each' ;
ON_FAILURE  : 'on-failure' ;
FLOW        : 'flow' ;
CHAN_KW     : 'chan' ;
TIMEOUT     : 'timeout' ;
LAZY        : 'lazy' ;

// ─── Binding & Definition Operators ──────────────────────────────────
BIND        : ':=' ;
MUT_BIND    : ':!' ;
ASSIGN      : '<-' ;
ARROW       : '->' ;
FAT_ARROW   : '=>' ;
TYPE_ANN    : '::' ;

// ─── Effect Arrow ────────────────────────────────────────────────────
// -[Effects]-> is parsed as tokens: MINUS LBRACKET ... RBRACKET ARROW
// The parser assembles these into effect-annotated function types.

// ─── Pipeline & Composition ──────────────────────────────────────────
PIPE        : '|>' ;
PIPE_BACK   : '<|' ;
COMPOSE     : '>>' ;
COMPOSE_BACK: '<<' ;

// ─── Arithmetic ──────────────────────────────────────────────────────
PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
SLASH       : '/' ;
PERCENT     : '%' ;
POWER       : '**' ;

// ─── Comparison & Logic ──────────────────────────────────────────────
EQ          : '==' ;
NEQ         : '/=' ;
LTE         : '<=' ;
GTE         : '>=' ;
LT          : '<' ;
GT          : '>' ;
AND         : '&&' ;
OR          : '||' ;
NOT         : '!' ;

// ─── Concatenation ───────────────────────────────────────────────────
CONCAT      : '++' ;

// ─── Collection Literals ─────────────────────────────────────────────
HASH_LBRACK : '#[' ;
HASH_LBRACE : '#{' ;
HASH_LPAREN : '#(' ;
LBRACE      : '{' ;
RBRACE      : '}' ;
LBRACK      : '[' ;
RBRACK      : ']' ;
LPAREN      : '(' ;
RPAREN      : ')' ;

// ─── Range ───────────────────────────────────────────────────────────
RANGE_EXCL  : '..<' ;
RANGE       : '..' ;

// ─── Spread (in patterns) ────────────────────────────────────────────
SPREAD      : '...' ;

// ─── Array/Sequence Operations (J-inspired digraphs) ─────────────────
REDUCE_PLUS : '/+' ;
REDUCE_STAR : '/*' ;
COUNT       : '/#' ;
REDUCE_AND  : '/&' ;
REDUCE_OR   : '/|' ;
SCAN        : '\\.' ;
AT_INDEX    : '@i' ;
AT          : '@' ;
FILTER      : '?' ;

// ─── Choreography ────────────────────────────────────────────────────
SEND        : '~>' ;
RECV        : '<~' ;
BROADCAST   : '~*>' ;
CHOREO_SEL  : '~/' ;

// ─── Punctuation ─────────────────────────────────────────────────────
DOT         : '.' ;
COMMA       : ',' ;
SEMI        : ';' ;
COLON       : ':' ;
UNDERSCORE  : '_' ;
HASH        : '#' ;
PIPE_CHAR   : '|' ;
TILDE       : '~' ;
BACKSLASH   : '\\' ;
EQUALS      : '=' ;

// ─── Java Annotations ───────────────────────────────────────────────
// @java.lang.Override — starts with @, followed by qualified PascalCase name
JAVA_ANNOTATION
    : '@' [A-Z] [a-zA-Z0-9]* ('.' [a-zA-Z] [a-zA-Z0-9]*)*
    ;

// ─── Literals ────────────────────────────────────────────────────────
HEX_LIT
    : '0' [xX] [0-9a-fA-F] ([0-9a-fA-F_]* [0-9a-fA-F])?
    ;

FLOAT_LIT
    : [0-9] ([0-9_]* [0-9])? '.' [0-9] ([0-9_]* [0-9])? ([eE] [+-]? [0-9]+)?
    ;

RATIONAL_LIT
    : [0-9] ([0-9_]* [0-9])? '/' [0-9] ([0-9_]* [0-9])?
    ;

INT_LIT
    : [0-9] ([0-9_]* [0-9])?
    ;

STRING_LIT
    : '"' (STRING_ESCAPE | STRING_INTERP | ~["\\\r\n])* '"'
    ;

fragment STRING_ESCAPE
    : '\\' [nrt\\"$]
    ;

fragment STRING_INTERP
    : '${' ~[}]* '}'
    ;

// ─── Keyword Literal (Clojure-style :atoms) ──────────────────────────
KEYWORD_LIT
    : ':' [a-z] [a-z0-9-]*
    ;

// ─── Identifiers ────────────────────────────────────────────────────

// Upper identifiers: covers both PascalCase type names and UPPER-CASE role names.
// The parser distinguishes based on context. Examples:
//   PascalCase:  HttpResponse, User, Result, Ok, Int
//   ALL-CAPS:    ALICE, BOB, DB-PRIMARY, COORD
UPPER_ID
    : [A-Z] [a-zA-Z0-9]* ('-' [A-Z] [a-zA-Z0-9]*)*
    ;

// Value identifiers: kebab-case
// Includes names like user->json, even?, add-one
LOWER_ID
    : [a-z] [a-z0-9-]* [!?]?
    | [a-z] [a-z0-9-]* '->' [a-z] [a-z0-9-]*
    ;

// ─── Comments ────────────────────────────────────────────────────────
COMMENT
    : ';;' ~[\r\n]* -> skip
    ;

// ─── Whitespace & Newlines ───────────────────────────────────────────
// The lexer base class handles INDENT/DEDENT emission on NEWLINE.
NEWLINE
    : ('\r'? '\n' | '\r') SPACES? { onNewLine(); }
    ;

SPACES
    : [ ]+ -> channel(HIDDEN)
    ;

// Tabs are explicitly disallowed — spec mandates 2-space indentation
TAB_ERROR
    : '\t'+ { reportTabError(); } -> skip
    ;

// ─── Catch-all for unknown characters ────────────────────────────────
UNEXPECTED_CHAR
    : .
    ;
