parser grammar IrijParser;

options {
    tokenVocab = IrijLexer;
}

// ═══════════════════════════════════════════════════════════════════════
// Top-Level
// ═══════════════════════════════════════════════════════════════════════

compilationUnit
    : sep* moduleDecl? sep* (topLevelDecl sep*)* EOF
    ;

// Separator: newline(s) or dedent (blocks emit DEDENT which acts as separator)
sep : NEWLINE | DEDENT
    ;

topLevelDecl
    : useDecl
    | pubDecl
    | fnDecl
    | typeDecl
    | newtypeDecl
    | effectDecl
    | handlerDecl
    | capDecl
    | roleDecl
    | protoDecl
    | implDecl
    | binding
    ;

// ═══════════════════════════════════════════════════════════════════════
// Module System
// ═══════════════════════════════════════════════════════════════════════

moduleDecl
    : MOD qualifiedName
    ;

useDecl
    : USE qualifiedName useModifier?
    ;

useModifier
    : OPEN                                          // :open — import all unqualified
    | LBRACE nameList RBRACE                        // {get post} — selective import
    ;

pubDecl
    : PUB useDecl                                   // pub use — re-export
    | PUB fnDecl                                    // pub fn — exported function
    | PUB typeDecl                                  // pub type — exported type
    | PUB newtypeDecl
    | PUB protoDecl
    ;

qualifiedName
    : (LOWER_ID | UPPER_ID) (DOT (LOWER_ID | UPPER_ID))*
    ;

nameList
    : anyId (anyId)*
    ;

// Allow keywords to be used as identifiers in non-keyword positions
anyId
    : LOWER_ID | UPPER_ID
    | POST | PRE | IN | OUT | ROLE | FOR | FLOW | LAZY  // contextual keywords
    ;

// ═══════════════════════════════════════════════════════════════════════
// Function Declaration
// ═══════════════════════════════════════════════════════════════════════

fnDecl
    : javaAnnotation* FN fnName typeAnnotation? fnContracts? fnLaws? NEWLINE INDENT fnBody DEDENT
    | javaAnnotation* FN fnName typeAnnotation? fnContracts? fnLaws? fnBodyInline
    ;

typeAnnotation
    : TYPE_ANN typeExpr
    ;

fnName
    : LOWER_ID
    | UPPER_ID                                       // constructors
    ;

fnBodyInline
    : lambdaExpr
    | matchArms
    ;

fnBody
    : matchArms
    | lambdaExpr NEWLINE*
    | statement (NEWLINE+ statement)* NEWLINE*
    ;

fnContracts
    : (NEWLINE INDENT contractClause+ DEDENT)
    | contractClause+
    ;

contractClause
    : PRE expr
    | POST expr
    | CONTRACT NEWLINE INDENT contractBody DEDENT
    ;

contractBody
    : contractInOut (NEWLINE+ contractInOut)* NEWLINE*
    ;

contractInOut
    : IN expr
    | OUT expr
    ;

fnLaws
    : (NEWLINE INDENT lawClause+ DEDENT)
    | lawClause+
    ;

lawClause
    : LAW LOWER_ID EQUALS lawBody
    ;

lawBody
    : FORALL forallBinders DOT expr
    | expr
    ;

forallBinders
    : LOWER_ID+
    ;

// ═══════════════════════════════════════════════════════════════════════
// Type Declarations
// ═══════════════════════════════════════════════════════════════════════

typeDecl
    : TYPE typeName typeParams? NEWLINE INDENT typeBody DEDENT
    | TYPE typeName typeParams? BIND typeExpr       // type alias: type Nat := {...}
    ;

newtypeDecl
    : NEWTYPE typeName BIND typeExpr
    ;

typeName
    : UPPER_ID
    ;

typeParams
    : LOWER_ID+
    ;

typeBody
    : typeField (NEWLINE+ typeField)* NEWLINE*       // record fields
    | typeVariant (NEWLINE+ typeVariant)* NEWLINE*    // sum type variants
    ;

typeField
    : anyId TYPE_ANN typeExpr
    ;

typeVariant
    : UPPER_ID typeExpr*
    ;

// ═══════════════════════════════════════════════════════════════════════
// Effect & Handler & Capability Declarations
// ═══════════════════════════════════════════════════════════════════════

effectDecl
    : EFFECT typeName typeParams? NEWLINE INDENT effectBody DEDENT
    ;

effectBody
    : effectOp (NEWLINE+ effectOp)* NEWLINE*
    ;

effectOp
    : LOWER_ID TYPE_ANN typeExpr
    ;

handlerDecl
    : HANDLER LOWER_ID TYPE_ANN typeName NEWLINE INDENT handlerBody DEDENT
    ;

handlerBody
    : handlerClause (NEWLINE+ handlerClause)* NEWLINE*
    ;

handlerClause
    : LOWER_ID pattern* FAT_ARROW expr              // op args => implementation
    | binding                                        // state := :! initial
    ;

capDecl
    : CAP typeName TYPE_ANN EFFECT NEWLINE INDENT effectBody DEDENT
    ;

// ═══════════════════════════════════════════════════════════════════════
// Role Declaration (Choreography)
// ═══════════════════════════════════════════════════════════════════════

roleDecl
    : ROLE UPPER_ID
    ;

// ═══════════════════════════════════════════════════════════════════════
// Protocol & Implementation (Typeclass-like)
// ═══════════════════════════════════════════════════════════════════════

protoDecl
    : PROTO typeName typeParams? NEWLINE INDENT protoBody DEDENT
    ;

protoBody
    : protoMember (NEWLINE+ protoMember)* NEWLINE*
    ;

protoMember
    : LOWER_ID TYPE_ANN typeExpr                    // method signature
    | lawClause                                     // algebraic law
    ;

implDecl
    : IMPL typeName FOR typeExpr NEWLINE INDENT implBody DEDENT
    ;

implBody
    : implMember (NEWLINE+ implMember)* NEWLINE*
    ;

implMember
    : LOWER_ID BIND expr                            // method implementation
    ;

// ═══════════════════════════════════════════════════════════════════════
// Type Expressions
// ═══════════════════════════════════════════════════════════════════════

typeExpr
    : typeExpr ARROW typeExpr                       // A -> B (function type)
    | typeExpr effectArrow typeExpr                  // A -[E1 E2]-> B
    | typeAtom AT UPPER_ID                            // T @ROLE (located type)
    | typeAtom
    ;

effectArrow
    : MINUS LBRACK typeExpr* RBRACK ARROW           // -[E1 E2]->
    ;

typeAtom
    : typeName                                       // User, Int, Str
    | LOWER_ID                                       // type variable: a, e
    | LPAREN RPAREN                                  // unit type: ()
    | LPAREN typeExpr RPAREN                         // grouped
    | UNDERSCORE                                     // wildcard / inferred
    | typeAtom typeAtom                              // type application: Result a e
    | HASH_LBRACK typeExpr RBRACK                    // #[a] — vector type
    | LBRACE refinementBody RBRACE                   // {x :: Int | x >= 0}
    | LPAREN typeExpr (COMMA typeExpr)* RPAREN       // tuple type
    ;

refinementBody
    : LOWER_ID TYPE_ANN typeExpr PIPE_CHAR expr
    ;

// ═══════════════════════════════════════════════════════════════════════
// Expressions
// ═══════════════════════════════════════════════════════════════════════

expr
    : ifExpr
    | matchExpr
    | doExpr
    | withExpr
    | scopeExpr
    | selectExpr
    | enclaveExpr
    | lambdaExpr
    | pipeExpr
    ;

// ─── Pipeline (left-to-right data flow) ─────────────────────────────
pipeExpr
    : composeExpr (PIPE composeExpr)*
    | composeExpr (PIPE_BACK composeExpr)*
    ;

composeExpr
    : choreographyExpr (COMPOSE choreographyExpr)*
    | choreographyExpr (COMPOSE_BACK choreographyExpr)*
    ;

// ─── Choreography operators ─────────────────────────────────────────
choreographyExpr
    : orExpr SEND UPPER_ID                            // val ~> ROLE
    | orExpr RECV UPPER_ID                            // val <~ ROLE
    | orExpr BROADCAST vectorLiteral                 // val ~*> #[R1 R2]
    | orExpr CHOREO_SEL UPPER_ID                      // cond ~/ ROLE
    | orExpr
    ;

// ─── Logical operators ──────────────────────────────────────────────
orExpr
    : andExpr (OR andExpr)*
    ;

andExpr
    : eqExpr (AND eqExpr)*
    ;

eqExpr
    : compExpr ((EQ | NEQ) compExpr)?
    ;

compExpr
    : concatExpr ((LT | GT | LTE | GTE) concatExpr)?
    ;

concatExpr
    : rangeExpr (CONCAT rangeExpr)*
    ;

rangeExpr
    : addExpr (RANGE addExpr)?
    | addExpr (RANGE_EXCL addExpr)?
    ;

// ─── Arithmetic ─────────────────────────────────────────────────────
addExpr
    : mulExpr ((PLUS | MINUS) mulExpr)*
    ;

mulExpr
    : powExpr ((STAR | SLASH | PERCENT) powExpr)*
    ;

powExpr
    : unaryExpr (POWER unaryExpr)?
    ;

unaryExpr
    : NOT unaryExpr
    | MINUS unaryExpr
    | seqOpExpr
    ;

// ─── Sequence/Array operations (J-inspired) ─────────────────────────
seqOpExpr
    : REDUCE_PLUS                                    // /+
    | REDUCE_STAR                                    // /*
    | COUNT                                          // /#
    | REDUCE_AND                                     // /&
    | REDUCE_OR                                      // /|
    | SCAN LOWER_ID?                                 // \.  or \.+
    | AT_INDEX lambdaExpr                            // @i (i v -> ...)
    | AT postfixExpr                                 // @ f
    | FILTER postfixExpr                             // ? pred
    | NOT postfixExpr                                // ! pred (find first)
    | postfixExpr
    ;

// ─── Postfix: field access, method call, located computation ────────
postfixExpr
    : appExpr (DOT (LOWER_ID | UPPER_ID) appArgs?)*
    ;

appArgs
    : atomExpr+
    ;

// ─── Application ────────────────────────────────────────────────────
appExpr
    : atomExpr atomExpr+                             // f x y — function application
    | atomExpr typeAnnotationSuffix?
    ;

typeAnnotationSuffix
    : TYPE_ANN typeExpr
    ;

// ─── Atoms ──────────────────────────────────────────────────────────
atomExpr
    : literal
    | LOWER_ID
    | UPPER_ID
    | UNDERSCORE
    | LPAREN RPAREN                                  // unit value: ()
    | LPAREN expr RPAREN                             // grouped expression
    | vectorLiteral                                  // #[1 2 3]
    | setLiteral                                     // #{:a :b}
    | tupleLiteral                                   // #(1 "a" :ok)
    | mapLiteral                                     // {name: "jo" age: 5}
    | DETACH_BANG lambdaExpr                         // detach! (-> ...)
    | LOOP lambdaExpr                                // loop (-> body)
    | RECUR expr lambdaExpr                          // recur init (acc -> ...) — loop setup
    | RECUR atomExpr*                                // recur val — signal restart inside loop
    | FLOW lambdaExpr                                // flow (-> body)
    | CHAN_KW INT_LIT typeAnnotationSuffix?           // chan 64 :: Chan Event
    | javaMethodCall                                 // JList/new ()
    ;

// ─── Java Interop ───────────────────────────────────────────────────
javaMethodCall
    : UPPER_ID SLASH (LOWER_ID | UPPER_ID) appArgs?    // JList/add "hello"
    ;

javaAnnotation
    : JAVA_ANNOTATION
    ;

// ─── Control Flow ───────────────────────────────────────────────────
ifExpr
    : IF expr expr ELSE expr                         // if cond then else (inline)
    | IF expr NEWLINE INDENT fnBody DEDENT ELSE NEWLINE INDENT fnBody DEDENT
    ;

matchExpr
    : MATCH expr NEWLINE INDENT matchArms DEDENT
    | MATCH expr AT UPPER_ID NEWLINE INDENT matchArms DEDENT   // match cmd @PRIMARY
    ;

matchArms
    : matchArm (NEWLINE+ matchArm)* NEWLINE*
    ;

matchArm
    : pattern guard? FAT_ARROW expr
    | pattern guard? FAT_ARROW NEWLINE INDENT fnBody DEDENT
    ;

guard
    : PIPE_CHAR expr
    ;

doExpr
    : DO NEWLINE INDENT statement (NEWLINE+ statement)* NEWLINE* DEDENT
    | DO LPAREN expr RPAREN (LPAREN expr RPAREN)*    // do (expr) (expr)
    ;

withExpr
    : WITH expr NEWLINE INDENT fnBody DEDENT onFailure?
    | WITH expr expr                                 // inline: with handler expr
    ;

onFailure
    : ON_FAILURE NEWLINE INDENT fnBody DEDENT
    ;

scopeExpr
    : SCOPE LOWER_ID NEWLINE INDENT fnBody DEDENT
    | SCOPE DOT LOWER_ID NEWLINE INDENT fnBody DEDENT   // scope.race, scope.supervised
    ;

selectExpr
    : SELECT NEWLINE INDENT selectArms DEDENT
    ;

selectArms
    : selectArm (NEWLINE+ selectArm)* NEWLINE*
    ;

selectArm
    : LOWER_ID LOWER_ID FAT_ARROW expr               // recv ch => (msg -> ...)
    | TIMEOUT expr FAT_ARROW expr                     // timeout 5s => (-> ...)
    ;

enclaveExpr
    : ENCLAVE vectorLiteral NEWLINE INDENT fnBody DEDENT
    ;

// ─── Choreography ───────────────────────────────────────────────────
parEachExpr
    : PAR_EACH LOWER_ID ASSIGN UPPER_ID NEWLINE INDENT fnBody DEDENT
    ;

// ─── Literals ───────────────────────────────────────────────────────
literal
    : INT_LIT
    | FLOAT_LIT
    | HEX_LIT
    | RATIONAL_LIT
    | STRING_LIT
    | KEYWORD_LIT
    ;

vectorLiteral
    : HASH_LBRACK exprList? RBRACK
    ;

setLiteral
    : HASH_LBRACE exprList? RBRACE
    ;

tupleLiteral
    : HASH_LPAREN exprList? RPAREN
    ;

mapLiteral
    : LBRACE mapEntryList? RBRACE
    ;

mapEntryList
    : mapEntry (mapEntry)* spreadExpr?
    ;

mapEntry
    : LOWER_ID COLON expr                              // name: "jo"
    | TYPE_ANN LOWER_ID expr                         // record update
    ;

spreadExpr
    : SPREAD LOWER_ID                                // ...rest
    ;

exprList
    : expr (expr)*
    ;

// ═══════════════════════════════════════════════════════════════════════
// Statements (in do blocks / function bodies)
// ═══════════════════════════════════════════════════════════════════════

statement
    : binding
    | expr
    | parEachExpr
    ;

binding
    : LOWER_ID BIND expr typeAnnotationSuffix?       // x := 42 :: Int
    | LOWER_ID MUT_BIND expr typeAnnotationSuffix?   // x :! 0 :: Int
    | LOWER_ID ASSIGN expr                           // x <- x + 1 (mutation)
    | destructurePattern BIND expr                   // {name: n} := get-user id
    ;

// ═══════════════════════════════════════════════════════════════════════
// Patterns
// ═══════════════════════════════════════════════════════════════════════

pattern
    : UPPER_ID pattern*                               // Ok value, Node l x r
    | literal                                        // 42, "hello", :ok
    | LOWER_ID                                       // variable binding
    | UNDERSCORE                                     // wildcard
    | LPAREN RPAREN                                  // unit pattern: ()
    | vectorPattern                                  // #[x ...rest]
    | destructurePattern                             // {name: n age: a}
    | tuplePattern                                   // #(x y z)
    | LPAREN pattern RPAREN                          // grouped
    ;

vectorPattern
    : HASH_LBRACK patternListWithSpread? RBRACK
    ;

tuplePattern
    : HASH_LPAREN patternList? RPAREN
    ;

destructurePattern
    : LBRACE destructureField+ RBRACE
    ;

destructureField
    : LOWER_ID COLON pattern                           // name: n
    ;

patternListWithSpread
    : pattern (pattern)* (SPREAD LOWER_ID)?          // x y ...rest
    ;

patternList
    : pattern (pattern)*
    ;

// ═══════════════════════════════════════════════════════════════════════
// Lambda
// ═══════════════════════════════════════════════════════════════════════

lambdaExpr
    : LPAREN lambdaParams ARROW expr RPAREN
    | LPAREN ARROW expr RPAREN                       // thunk: (-> expr)
    ;

lambdaParams
    : pattern+
    ;
