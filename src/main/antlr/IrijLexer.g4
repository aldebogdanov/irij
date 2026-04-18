lexer grammar IrijLexer;

options { superClass = IrijLexerBase; }

// ── Tokens injected by IrijLexerBase (not matched by rules) ──────────
tokens { INDENT, DEDENT, NEWLINE }

// ── Keywords ─────────────────────────────────────────────────────────
FN          : 'fn' ;
DO          : 'do' ;
IF          : 'if' ;
ELSE        : 'else' ;
MATCH       : 'match' ;
SPEC        : 'spec' ;
NEWTYPE     : 'newtype' ;
MOD         : 'mod' ;
USE         : 'use' ;
PUB         : 'pub' ;
WITH        : 'with' ;
SCOPE       : 'scope' ;
EFFECT      : 'effect' ;
ROLE        : 'role' ;
CAP         : 'cap' ;
HANDLER     : 'handler' ;
IMPL        : 'impl' ;
PROTO       : 'proto' ;
PRE         : 'pre' ;
POST        : 'post' ;
LAW         : 'law' ;
CONTRACT    : 'contract' ;
SELECT      : 'select' ;
ENCLAVE     : 'enclave' ;
FORALL      : 'forall' ;
PAR_EACH    : 'par-each' ;
ON_FAILURE  : 'on-failure' ;
IN          : 'in' ;
OUT         : 'out' ;
FOR         : 'for' ;
PROOF       : 'proof' ;

// ── Binding & Definition ─────────────────────────────────────────────
BIND        : ':=' ;       // immutable binding
MUT_BIND    : ':!' ;       // mutable binding
ARROW       : '->' ;       // function arrow
FAT_ARROW   : '=>' ;       // match arm / imperative block
ASSIGN      : '<-' ;       // mutation
EFFECT_SEP  : ':::' ;      // effect separator (must precede SPEC_ANN for maximal munch)
SPEC_ANN    : '::' ;       // spec annotation

// ── Pipeline & Composition ───────────────────────────────────────────
PIPE        : '|>' ;       // pipe forward
PIPE_BACK   : '<|' ;       // pipe backward
COMPOSE     : '>>' ;       // compose forward
COMPOSE_BACK: '<<' ;       // compose backward

// ── Choreography ─────────────────────────────────────────────────────
SEND        : '~>' ;       // choreographic send
RECV        : '<~' ;       // choreographic receive (sugar)
BROADCAST   : '~*>' ;      // broadcast to multiple roles
CH_SELECT   : '~/' ;       // select/inform branch

// ── Comparison ───────────────────────────────────────────────────────
EQ          : '==' ;       // equality
NEQ         : '/=' ;       // not-equal
LTE         : '<=' ;       // less-or-equal
GTE         : '>=' ;       // greater-or-equal

// ── Boolean ──────────────────────────────────────────────────────────
AND         : '&&' ;       // logical and
OR          : '||' ;       // logical or
BAR         : '|' ;        // guard separator, refinement

// ── Arithmetic & Misc Digraphs ───────────────────────────────────────
POW         : '**' ;       // power
CONCAT      : '++' ;       // concatenation
RANGE       : '..' ;       // inclusive range
RANGE_EXCL  : '..<' ;      // exclusive range
SPREAD      : '...' ;      // spread/rest in patterns

// ── Sequence / Array Operations (J-inspired) ─────────────────────────
SEQ_PLUS    : '/+' ;       // reduce with +
SEQ_STAR    : '/*' ;       // reduce with *
SEQ_COUNT   : '/#' ;       // count
SEQ_AND     : '/&' ;       // all? (reduce with &&)
SEQ_OR      : '/|' ;       // any? (reduce with ||)
SEQ_FILTER  : '/?' ;       // filter
SEQ_FIND    : '/!' ;       // find-first
REDUCE      : '/^' ;       // generic reduce (fold) with function
SCAN_OP     : '/$' ;       // scan (prefix sums) with function
MAP_AT      : '@' ;        // map-over / located-at
MAP_INDEXED : '@i' ;       // map-indexed

// ── Collection Openers ───────────────────────────────────────────────
VEC_OPEN    : '#[' ;       // vector literal open
SET_OPEN    : '#{' ;       // set literal open
TUPLE_OPEN  : '#(' ;       // tuple literal open

// ── Single-Character Tokens ──────────────────────────────────────────
LPAREN      : '(' ;
RPAREN      : ')' ;
LBRACE      : '{' ;
RBRACE      : '}' ;
LBRACKET    : '[' ;
RBRACKET    : ']' ;
PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
SLASH       : '/' ;
PERCENT     : '%' ;
LT          : '<' ;
GT          : '>' ;
NOT         : '!' ;
TILDE       : '~' ;        // apply-to-rest: f ~ x + 1  ≡  f (x + 1)
DOT         : '.' ;
EQUALS      : '=' ;        // map field separator {name= "jo"}
SEMICOLON   : ';' ;        // inline separator (only inside parens)
// Line continuation: `\` at end of a line consumes the backslash and the
// following newline so the lexer never emits a NEWLINE for that line.
LINE_CONT   : '\\' [ \t]* ('\r'? '\n' | '\r') -> skip ;
BACKSLASH   : '\\' ;       // bare backslash (unused in grammar; reserved)
UNDERSCORE  : '_' ;        // wildcard pattern

// ── Keyword Atoms (Clojure-style) ────────────────────────────────────
KEYWORD     : ':' [a-z] [a-z0-9_-]* ;   // :ok  :error  :pending

// ── Role Names ───────────────────────────────────────────────────────
ROLE_NAME   : '$' [A-Z] [A-Z0-9_-]* ;   // $BUYER  $DB-PRIMARY

// ── Identifiers ──────────────────────────────────────────────────────
// Upper names: PascalCase (start with uppercase) — specs, effects, protocols, constructors
UPPER_NAME  : [A-Z] [a-zA-Z0-9]* ;

// Value names: kebab-case, may contain -> and ?, !
// NOTE: must come after keywords to let keywords win
IDENT       : [a-z] [a-z0-9]* ('-' [a-z0-9]+)* [?!]? ;

// Attribute-style identifier with internal colons (for HTML/DOM map keys).
// Only matches when there is at least one `:name` segment, so it never
// conflicts with plain IDENT or with keywords (`:kw` starts with `:`).
// Example: data-on:click, hx-on:htmx:after-request
ATTR_IDENT  : [a-z] [a-z0-9]* ('-' [a-z0-9]+)* (':' [a-z] [a-z0-9]* ('-' [a-z0-9]+)*)+ ;

// ── Numeric Literals ─────────────────────────────────────────────────
HEX_LIT     : '0' [xX] [0-9a-fA-F] ([0-9a-fA-F_]* [0-9a-fA-F])? ;
FLOAT_LIT   : [0-9] [0-9_]* '.' [0-9] [0-9_]* ;
RATIONAL    : [0-9]+ '/' [0-9]+ ;
INT_LIT     : [0-9] ([0-9_]* [0-9])? ;

// ── String Literals ──────────────────────────────────────────────────
STRING      : '"' (STR_ESCAPE | STR_INTERP | ~["\\\r\n])* '"' ;

fragment STR_ESCAPE : '\\' [nrt"\\$] ;
fragment STR_INTERP : '${' ~[}]* '}' ;    // interpolation ${expr}

// ── Whitespace & Comments ────────────────────────────────────────────
// IrijLexerBase handles INDENT/DEDENT/NEWLINE from these raw tokens.
// The base class intercepts every token and decides what to emit.

COMMENT     : ';;' ~[\r\n]* -> channel(HIDDEN) ;

// Leading whitespace at start of line is handled by IrijLexerBase.
// Inside a line, whitespace is skipped.
WS          : [ \t]+ -> channel(HIDDEN) ;

// Newlines are captured for INDENT/DEDENT processing by IrijLexerBase.
NL          : ('\r'? '\n' | '\r') ;
