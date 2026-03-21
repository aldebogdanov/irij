parser grammar IrijParser;

options { tokenVocab = IrijLexer; }

// ═══════════════════════════════════════════════════════════════════════
// Top Level
// ═══════════════════════════════════════════════════════════════════════

compilationUnit
    : NEWLINE* (topLevelDecl (NEWLINE+ topLevelDecl)*)? NEWLINE* EOF
    ;

topLevelDecl
    : modulDecl
    | useDecl
    | pubDecl
    | fnDecl
    | typeDecl
    | newtypeDecl
    | effectDecl
    | handlerDecl
    | capDecl
    | protoDecl
    | implDecl
    | roleDecl
    | matchStmt
    | ifStmt
    | withExpr
    | scopeExpr
    | binding
    | expr
    ;

// ═══════════════════════════════════════════════════════════════════════
// Declarations
// ═══════════════════════════════════════════════════════════════════════

modulDecl
    : MOD qualifiedName
    ;

useDecl
    : USE qualifiedName useModifier?
    ;

useModifier
    : KEYWORD                                // use std.json :open
    | LBRACE nameList RBRACE                 // use std.http {get post}
    ;

nameList
    : nameListItem+
    ;

nameListItem
    : IDENT | TYPE_NAME
    | FN | DO | IF | ELSE | MATCH | TYPE | NEWTYPE | MOD | USE | PUB
    | WITH | SCOPE | EFFECT | ROLE | CAP | HANDLER | IMPL | PROTO
    | PRE | POST | LAW | CONTRACT | SELECT | ENCLAVE | FORALL
    | PAR_EACH | ON_FAILURE | IN | OUT | FOR | PROOF
    ;

pubDecl
    : PUB (fnDecl | useDecl | binding)
    ;

roleDecl
    : ROLE ROLE_NAME
    ;

// ── fn ───────────────────────────────────────────────────────────────

fnDecl
    : FN fnName typeAnnotation? fnBody?
    ;

fnName
    : IDENT
    ;

fnBody
    : NEWLINE INDENT fnBodyContent NEWLINE* DEDENT
    ;

// Disambiguation by first token:
//   ( → lambda
//   => → imperative block
//   otherwise → pattern-match arms
fnBodyContent
    : contractClause* lambdaBody
    | contractClause* imperativeBlock
    | contractClause* matchArms
    ;

lambdaBody
    : LPAREN lambdaParams ARROW exprSeq RPAREN
    ;

lambdaParams
    : pattern*
    ;

imperativeBlock
    : FAT_ARROW pattern* NEWLINE stmtList
    ;

matchArms
    : matchArm (NEWLINE matchArm)*
    ;

matchArm
    : pattern guard? FAT_ARROW armBody
    ;

armBody
    : expr                                   // single-line arm
    | NEWLINE INDENT stmtList NEWLINE* DEDENT // multi-line arm
    ;

guard
    : BAR expr
    ;

contractClause
    : PRE expr NEWLINE
    | POST expr NEWLINE
    | LAW IDENT EQUALS forallBinders? expr NEWLINE
    ;

// ── type / newtype ───────────────────────────────────────────────────

typeDecl
    : TYPE typeName typeParams? NEWLINE INDENT typeBody NEWLINE* DEDENT
    ;

typeName
    : TYPE_NAME
    ;

typeParams
    : IDENT+
    ;

typeBody
    : typeVariant (NEWLINE typeVariant)*      // sum type
    | typeField (NEWLINE typeField)*          // product type (record)
    ;

typeVariant
    : TYPE_NAME typeExpr*
    ;

typeField
    : IDENT TYPE_ANN typeExpr
    ;

newtypeDecl
    : NEWTYPE typeName BIND typeExpr
    ;

// ── effect ───────────────────────────────────────────────────────────

effectDecl
    : EFFECT typeName typeParams? (NEWLINE INDENT effectBody NEWLINE* DEDENT)?
    ;

effectBody
    : effectOp (NEWLINE effectOp)*
    ;

effectOp
    : IDENT TYPE_ANN typeExpr
    ;

// ── handler ──────────────────────────────────────────────────────────

handlerDecl
    : HANDLER fnName TYPE_ANN typeName NEWLINE INDENT handlerBody NEWLINE* DEDENT
    ;

handlerBody
    : handlerClause (NEWLINE handlerClause)*
    ;

handlerClause
    : IDENT pattern* FAT_ARROW armBody       // effect op impl
    | binding                                // handler-local state
    ;

// ── cap ──────────────────────────────────────────────────────────────

capDecl
    : CAP typeName TYPE_ANN EFFECT (NEWLINE INDENT effectBody NEWLINE* DEDENT)?
    ;

// ── proto ────────────────────────────────────────────────────────────

protoDecl
    : PROTO typeName typeParams? NEWLINE INDENT protoBody NEWLINE* DEDENT
    ;

protoBody
    : protoMember (NEWLINE protoMember)*
    ;

protoMember
    : IDENT TYPE_ANN typeExpr                // method signature
    | LAW IDENT EQUALS forallBinders? expr   // law
    ;

// ── impl ─────────────────────────────────────────────────────────────

implDecl
    : IMPL typeName FOR typeName NEWLINE INDENT implBody NEWLINE* DEDENT
    ;

implBody
    : implMember (NEWLINE implMember)*
    ;

implMember
    : IDENT BIND expr                        // method implementation
    ;

// ── contract block (module-boundary) ─────────────────────────────────

contractBlock
    : CONTRACT NEWLINE INDENT contractInOut+ NEWLINE* DEDENT
    ;

contractInOut
    : IN expr NEWLINE?
    | OUT expr NEWLINE?
    ;

forallBinders
    : FORALL IDENT+ DOT
    ;

// ═══════════════════════════════════════════════════════════════════════
// Type Expressions
// ═══════════════════════════════════════════════════════════════════════

typeExpr
    : typeApp ARROW typeExpr                  // A -> B
    | typeApp effectArrow typeExpr             // A -[E]> B
    | typeApp
    ;

typeApp
    : typeAtom+                               // Result a e  (type application)
    ;

effectArrow
    : EFFECT_OPEN typeName* EFFECT_CLOSE
    ;

typeAtom
    : typeName                                // Int, Str, Result
    | IDENT                                   // type variable: a, b
    | UNDERSCORE                              // type hole: _
    | LPAREN RPAREN                           // unit type: ()
    | LPAREN typeExpr RPAREN                  // grouped: (A -> B)
    | VEC_OPEN typeExpr RBRACKET              // #[a]
    | SET_OPEN typeExpr RBRACE                // #{a}
    | TUPLE_OPEN typeExpr typeExpr* RPAREN    // #(a b)
    | LBRACE refinementBody RBRACE            // {x :: Int | x >= 0}
    | typeAtom MAP_AT ROLE_NAME               // located: T @$ROLE
    ;

refinementBody
    : IDENT TYPE_ANN typeExpr BAR expr
    ;

// ═══════════════════════════════════════════════════════════════════════
// Statements & Bindings
// ═══════════════════════════════════════════════════════════════════════

stmtList
    : stmt (NEWLINE stmt)*
    ;

stmt
    : binding
    | withExpr
    | scopeExpr
    | matchStmt
    | selectExpr
    | ifStmt
    | parEachExpr
    | enclaveExpr
    | expr
    ;

binding
    : bindTarget BIND expr typeAnnotationSuffix?    // x := 42  or  x := 42 :: Int
    | bindTarget MUT_BIND expr typeAnnotationSuffix? // x :! 0
    | bindTarget ASSIGN expr                         // x <- x + 1
    ;

bindTarget
    : IDENT
    | destructurePattern
    ;

typeAnnotationSuffix
    : TYPE_ANN typeExpr
    ;

typeAnnotation
    : TYPE_ANN typeExpr
    ;

// ═══════════════════════════════════════════════════════════════════════
// Expressions (precedence from lowest to highest)
// ═══════════════════════════════════════════════════════════════════════

expr
    : applyToExpr
    ;

// Apply-to-rest: f ~ rest  ≡  f(rest)   (lowest precedence, right-associative)
applyToExpr
    : choreographyExpr TILDE applyToExpr       // f ~ rest
    | choreographyExpr                          // no ~
    ;

// Choreography: ~> <~ ~*> ~/
choreographyExpr
    : pipeExpr ((SEND | RECV | BROADCAST | CH_SELECT) pipeExpr)*
    ;

// Pipeline: |>  <|
pipeExpr
    : composeExpr ((PIPE | PIPE_BACK) composeExpr)*
    ;

// Composition: >>  <<
composeExpr
    : orExpr ((COMPOSE | COMPOSE_BACK) orExpr)*
    ;

// Logical OR
orExpr
    : andExpr (OR andExpr)*
    ;

// Logical AND
andExpr
    : eqExpr (AND eqExpr)*
    ;

// Equality: == /=
eqExpr
    : compExpr ((EQ | NEQ) compExpr)*
    ;

// Comparison: < > <= >=
compExpr
    : concatExpr ((LT | GT | LTE | GTE) concatExpr)*
    ;

// Concatenation: ++
concatExpr
    : rangeExpr (CONCAT rangeExpr)*
    ;

// Range: ..  ..<
rangeExpr
    : addExpr ((RANGE | RANGE_EXCL) addExpr)?
    ;

// Addition: + -
addExpr
    : mulExpr ((PLUS | MINUS) mulExpr)*
    ;

// Multiplication: * / %
mulExpr
    : powExpr ((STAR | SLASH | PERCENT) powExpr)*
    ;

// Power: **
powExpr
    : unaryExpr (POW unaryExpr)?
    ;

// Unary: ! -
unaryExpr
    : NOT unaryExpr
    | MINUS unaryExpr
    | seqOpExpr
    ;

// Sequence operators: /+ /* /# /& /| /? /! /^ /$ @ @i
// Can appear with or without argument (standalone = used as function value)
seqOpExpr
    : seqOp appExpr?
    | appExpr
    ;

seqOp
    : SEQ_PLUS | SEQ_STAR | SEQ_COUNT | SEQ_AND | SEQ_OR
    | SEQ_FILTER | SEQ_FIND | REDUCE | SCAN_OP | MAP_AT | MAP_INDEXED
    ;

// Application: f x y (juxtaposition of postfixed expressions)
appExpr
    : postfixExpr+
    ;

// Postfix: dot access, located-at
postfixExpr
    : atomExpr (DOT (IDENT | TYPE_NAME))* (MAP_AT ROLE_NAME)?
    ;

// ═══════════════════════════════════════════════════════════════════════
// Atomic Expressions
// ═══════════════════════════════════════════════════════════════════════

atomExpr
    : literal
    | IDENT
    | TYPE_NAME
    | ROLE_NAME
    | KEYWORD
    | UNDERSCORE
    | ifExpr
    | matchExpr
    | lambdaExpr
    | operatorAsValue
    | unitExpr
    | parenExpr
    | vectorLiteral
    | setLiteral
    | tupleLiteral
    | mapLiteral
    | doExpr
    ;

// ── Match expression (block-level, usable in expression context) ────

matchExpr
    : MATCH expr (MAP_AT ROLE_NAME)?
      NEWLINE INDENT matchArms NEWLINE* DEDENT
    ;

// ── Unit value ───────────────────────────────────────────────────────

unitExpr
    : LPAREN RPAREN
    ;

// ── Operator as value: (+), (-), (*), etc. ──────────────────────────

operatorAsValue
    : LPAREN (PLUS | MINUS | STAR | SLASH | PERCENT | POW | CONCAT
             | EQ | NEQ | LT | GT | LTE | GTE | AND | OR) RPAREN
    ;

// ── Inline if expression ─────────────────────────────────────────────

ifExpr
    : IF atomExpr atomExpr ELSE atomExpr
    ;

// ── Lambda ───────────────────────────────────────────────────────────

lambdaExpr
    : LPAREN lambdaParams ARROW exprSeq RPAREN
    ;

// ── Parenthesized Expression (with optional semicolons) ──────────────

parenExpr
    : LPAREN exprSeq RPAREN
    ;

exprSeq
    : expr (SEMICOLON expr)*
    ;

// ── Collection Literals ──────────────────────────────────────────────

vectorLiteral
    : VEC_OPEN exprList? RBRACKET
    ;

setLiteral
    : SET_OPEN exprList? RBRACE
    ;

tupleLiteral
    : TUPLE_OPEN exprList? RPAREN
    ;

mapLiteral
    : LBRACE mapEntryList? RBRACE
    ;

mapEntryList
    : mapEntry+
    ;

mapEntry
    : SPREAD IDENT                            // {...record} spread
    | IDENT EQUALS expr                       // name= "jo"
    ;

exprList
    : expr+
    ;

// ── do expression ────────────────────────────────────────────────────

doExpr
    : DO parenExpr+
    ;

// ── if / else (as statement, block-level) ────────────────────────────

ifStmt
    : IF expr NEWLINE INDENT stmtList NEWLINE* DEDENT
      (NEWLINE? ELSE NEWLINE INDENT stmtList NEWLINE* DEDENT)?
    ;

// ── match (as statement, block-level) ────────────────────────────────

matchStmt
    : MATCH expr (MAP_AT ROLE_NAME)?
      NEWLINE INDENT matchArms NEWLINE* DEDENT
    ;

// ── select ───────────────────────────────────────────────────────────

selectExpr
    : SELECT NEWLINE INDENT selectArms NEWLINE* DEDENT
    ;

selectArms
    : selectArm (NEWLINE selectArm)*
    ;

selectArm
    : expr FAT_ARROW expr
    ;

// ── with / on-failure ────────────────────────────────────────────────

withExpr
    : WITH expr NEWLINE INDENT stmtList NEWLINE* DEDENT
      (NEWLINE? ON_FAILURE NEWLINE INDENT stmtList NEWLINE* DEDENT)?
    ;

// ── scope ────────────────────────────────────────────────────────────

scopeExpr
    : SCOPE (DOT IDENT)? IDENT?
      NEWLINE INDENT stmtList NEWLINE* DEDENT
    ;

// ── enclave ──────────────────────────────────────────────────────────

enclaveExpr
    : ENCLAVE vectorLiteral
      NEWLINE INDENT stmtList NEWLINE* DEDENT
    ;

// ── par-each ─────────────────────────────────────────────────────────

parEachExpr
    : PAR_EACH IDENT ASSIGN ROLE_NAME
      NEWLINE INDENT stmtList NEWLINE* DEDENT
    ;

// ═══════════════════════════════════════════════════════════════════════
// Patterns
// ═══════════════════════════════════════════════════════════════════════

pattern
    : TYPE_NAME pattern*                       // Constructor Pat1 Pat2
    | KEYWORD pattern?                         // :ok value
    | IDENT                                    // variable binding
    | UNDERSCORE                               // wildcard _
    | literal                                  // literal pattern
    | LPAREN RPAREN                            // unit pattern ()
    | LPAREN pattern RPAREN                    // grouped pattern
    | vectorPattern                            // #[x ...r]
    | tuplePattern                             // #(a b)
    | destructurePattern                       // {name= n age= a}
    | SPREAD IDENT                             // ...rest
    | SPREAD UNDERSCORE                        // ..._  (ignore rest)
    ;

vectorPattern
    : VEC_OPEN patternListWithSpread? RBRACKET
    ;

tuplePattern
    : TUPLE_OPEN pattern* RPAREN
    ;

destructurePattern
    : LBRACE destructureField+ RBRACE
    ;

destructureField
    : IDENT EQUALS pattern
    ;

patternListWithSpread
    : pattern (pattern | SPREAD (IDENT | UNDERSCORE))*
    ;

// ═══════════════════════════════════════════════════════════════════════
// Literals & Helpers
// ═══════════════════════════════════════════════════════════════════════

literal
    : INT_LIT
    | FLOAT_LIT
    | HEX_LIT
    | RATIONAL
    | STRING
    ;

qualifiedName
    : (IDENT | TYPE_NAME) (DOT (IDENT | TYPE_NAME))*
    ;

anyId
    : IDENT
    | TYPE_NAME
    ;
