// SPDX-License-Identifier: Apache-2.0
// MethodAtlas TypeScript scanner worker
//
// Communication protocol  (stdin → this process → stdout)
// ========================================================
// Every message is a single UTF-8 JSON line terminated by \n.
//
// Request  (Java → worker):
//   { "requestId": "<uuid>",
//     "filePath":  "<absolute path>",
//     "functionNames": ["test", "it"] }
//
// Response (worker → Java):
//   { "requestId": "<uuid>",
//     "methods":   [ { "name":      "<test name>",
//                      "describe":  ["<outer>", "<inner>"],  // null when top-level
//                      "beginLine": <int>,
//                      "endLine":   <int>,
//                      "loc":       <int> } ],
//     "error":     null | "<message>" }
//
// The worker stays alive processing requests until stdin closes, then exits 0.
// A fatal startup error causes an exit with a non-zero code and a JSON error
// line written to stdout so the Java side can detect and log the failure.
//
// Bundle integrity
// ================
// This file is bundled with esbuild into a single self-contained artefact.
// The build step computes sha256(bundle) and embeds it into the JAR manifest.
// At runtime the Java side verifies the hash before spawning any worker.
// The worker itself does not need to verify — that responsibility belongs to
// BundleIntegrity.java which runs before the first process is started.

'use strict';

const readline = require('readline');
const path = require('path');

// ---------------------------------------------------------------------------
// Lazy-load the parser so that startup errors surface as JSON error responses
// rather than crashing the process before the readline loop is established.
// ---------------------------------------------------------------------------
let parse;
try {
    // @typescript-eslint/typescript-estree is bundled by esbuild; require is
    // always synchronous and safe to call at module scope.
    parse = require('@typescript-eslint/typescript-estree').parse;
} catch (err) {
    process.stdout.write(
        JSON.stringify({ requestId: null, methods: [], error: 'Failed to load parser: ' + String(err) }) + '\n'
    );
    process.exit(1);
}

// ---------------------------------------------------------------------------
// Main readline event loop
// ---------------------------------------------------------------------------
const rl = readline.createInterface({ input: process.stdin, terminal: false });

rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) {
        return; // skip blank lines
    }
    let requestId = null;
    try {
        const req = JSON.parse(trimmed);
        requestId = req.requestId != null ? String(req.requestId) : null;
        const functionNames =
            Array.isArray(req.functionNames) && req.functionNames.length > 0
                ? req.functionNames.map(String)
                : ['test', 'it'];
        const methods = scanFile(req.filePath, functionNames);
        writeLine({ requestId, methods, error: null });
    } catch (err) {
        writeLine({ requestId, methods: [], error: String(err) });
    }
});

rl.on('close', () => {
    process.exit(0);
});

// ---------------------------------------------------------------------------
// Core scanning logic
// ---------------------------------------------------------------------------

/**
 * Parses one TypeScript/JavaScript file and returns all discovered test
 * invocations as an array of method descriptors.
 *
 * @param {string} filePath - Absolute path to the source file.
 * @param {string[]} functionNames - Function-call names that identify a test
 *   (e.g. ["test", "it"]).  Calls to "describe", "context", and "suite" are
 *   always recognised as suite wrappers regardless of this list.
 * @returns {Array<{name:string, describe:string[]|null, beginLine:number, endLine:number, loc:number}>}
 */
function scanFile(filePath, functionNames) {
    const fs = require('fs');
    const source = fs.readFileSync(filePath, 'utf8');

    // Determine whether to enable JSX based on file extension.
    const ext = path.extname(filePath).toLowerCase();
    const jsx = ext === '.tsx' || ext === '.jsx';

    let ast;
    try {
        ast = parse(source, {
            jsx,
            loc: true,
            range: false,
            comment: false,
            tokens: false,
            errorOnUnknownASTType: false,
            filePath,
            loggerFn: false,       // suppress internal warnings
        });
    } catch (parseErr) {
        // Re-throw with context so the error line includes the file name.
        throw new Error('Parse error in ' + filePath + ': ' + String(parseErr));
    }

    const methods = [];
    visitNode(ast, functionNames, methods, []);
    return methods;
}

// ---------------------------------------------------------------------------
// AST visitor
// ---------------------------------------------------------------------------

/** Names of scope wrappers — always treated as describe containers. */
const DESCRIBE_NAMES = new Set(['describe', 'context', 'suite', 'fdescribe', 'xdescribe']);

/**
 * Recursively walks an AST node, collecting test invocations.
 *
 * @param {object}   node          - Current AST node.
 * @param {string[]} functionNames - Test function-call names.
 * @param {Array}    methods       - Accumulator for discovered tests.
 * @param {string[]} describeStack - Current stack of describe-block names.
 */
function visitNode(node, functionNames, methods, describeStack) {
    if (node === null || typeof node !== 'object' || typeof node.type !== 'string') {
        return;
    }

    if (node.type === 'CallExpression') {
        const calleeName = resolveCalleeName(node.callee);
        if (calleeName !== null) {
            if (DESCRIBE_NAMES.has(calleeName)) {
                // Recurse into the callback argument with an extended stack.
                const suiteName = extractFirstStringArg(node) || '<anonymous>';
                const callback = findCallbackArg(node);
                if (callback !== null) {
                    describeStack.push(suiteName);
                    visitNode(callback, functionNames, methods, describeStack);
                    describeStack.pop();
                }
                return; // do not fall through to generic child traversal
            }

            if (functionNames.includes(calleeName)) {
                const testName = extractFirstStringArg(node) || '<anonymous>';
                const loc = node.loc || {};
                const beginLine = (loc.start && loc.start.line) || 0;
                const endLine   = (loc.end   && loc.end.line)   || 0;
                const lineCount = endLine >= beginLine && beginLine > 0
                    ? endLine - beginLine + 1
                    : 1;

                methods.push({
                    name:      testName,
                    describe:  describeStack.length > 0 ? describeStack.slice() : null,
                    beginLine,
                    endLine,
                    loc: lineCount,
                });
                // Do NOT recurse into the test body: nested it() calls are rare
                // and treating them as top-level tests would produce noise.
                return;
            }
        }
    }

    // Generic child traversal — visit every property that holds an AST node.
    for (const key of Object.keys(node)) {
        if (key === 'parent') {
            continue; // avoid circular references in some AST variants
        }
        const value = node[key];
        if (Array.isArray(value)) {
            for (const item of value) {
                visitNode(item, functionNames, methods, describeStack);
            }
        } else if (value !== null && typeof value === 'object' && typeof value.type === 'string') {
            visitNode(value, functionNames, methods, describeStack);
        }
    }
}

// ---------------------------------------------------------------------------
// Callee-name resolution
// ---------------------------------------------------------------------------

/**
 * Extracts the base function name from a callee expression.
 *
 * Handles:
 *   test(...)                    → "test"
 *   it.each([...])               → "it"     (MemberExpression, prop=each)
 *   test.skip(...)               → "test"   (MemberExpression, prop=skip)
 *   test.each`...`               → "test"   (TaggedTemplateExpression)
 *   it.each`...`(...)            → "it"     (CallExpression whose callee is tagged template)
 *
 * @param {object} callee - The callee node of a CallExpression.
 * @returns {string|null} The base name, or null if not resolvable.
 */
function resolveCalleeName(callee) {
    if (callee === null || typeof callee !== 'object') {
        return null;
    }
    if (callee.type === 'Identifier') {
        return callee.name;
    }
    if (callee.type === 'MemberExpression' && !callee.computed) {
        // test.each / test.skip / test.only / test.concurrent / test.todo / it.each …
        const prop = callee.property;
        if (prop && prop.type === 'Identifier') {
            return resolveCalleeName(callee.object);
        }
    }
    if (callee.type === 'TaggedTemplateExpression') {
        // test.each`…` (tagged template) — the call node wraps this
        return resolveCalleeName(callee.tag);
    }
    if (callee.type === 'CallExpression') {
        // test.each([...])(name, fn) — double call; the outer callee is a CallExpression
        return resolveCalleeName(callee.callee);
    }
    return null;
}

// ---------------------------------------------------------------------------
// Argument helpers
// ---------------------------------------------------------------------------

/**
 * Returns the string value of the first argument of a call, if it is a
 * string literal or a template literal with no substitutions.
 *
 * @param {object} callNode - A CallExpression node.
 * @returns {string|null}
 */
function extractFirstStringArg(callNode) {
    const args = callNode.arguments;
    if (!Array.isArray(args) || args.length === 0) {
        return null;
    }
    const arg = args[0];
    if (arg.type === 'Literal' && typeof arg.value === 'string') {
        return arg.value;
    }
    if (arg.type === 'TemplateLiteral') {
        // Reconstruct a readable name from the template quasi segments.
        return arg.quasis
            .map((q) => (q.value && (q.value.cooked != null ? q.value.cooked : q.value.raw)) || '')
            .join('${...}');
    }
    return null;
}

/**
 * Finds the callback argument of a describe/suite call: the last argument
 * that is a function expression or arrow function.
 *
 * @param {object} callNode - A CallExpression node.
 * @returns {object|null} The function body (BlockStatement or expression), or
 *   the entire arrow/function node if it has no body child to drill into.
 */
function findCallbackArg(callNode) {
    const args = callNode.arguments;
    if (!Array.isArray(args)) {
        return null;
    }
    // Walk arguments in reverse; the callback is conventionally the last one.
    for (let i = args.length - 1; i >= 0; i--) {
        const arg = args[i];
        if (arg.type === 'ArrowFunctionExpression' || arg.type === 'FunctionExpression') {
            // Return the body so that visitNode can walk its statements.
            return arg.body || arg;
        }
    }
    return null;
}

// ---------------------------------------------------------------------------
// Output helper
// ---------------------------------------------------------------------------

/**
 * Serialises a response object as a single JSON line and writes it to stdout.
 *
 * @param {object} obj - The response object to serialise.
 */
function writeLine(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}
