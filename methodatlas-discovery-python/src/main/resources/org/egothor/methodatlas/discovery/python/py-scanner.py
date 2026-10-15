# SPDX-License-Identifier: Apache-2.0
# MethodAtlas Python scanner worker
#
# Communication protocol  (stdin -> this process -> stdout)
# =========================================================
# Every message is a single UTF-8 JSON line terminated by \n.
#
# Request  (Java -> worker):
#   { "requestId": "<uuid>",
#     "filePath":  "<absolute path>" }
#
# Response (worker -> Java):
#   { "requestId": "<uuid>",
#     "methods":   [ { "name":      "<test name>",
#                      "className": "<class name>" | null,
#                      "beginLine": <int>,
#                      "endLine":   <int>,
#                      "loc":       <int>,
#                      "tags":      ["<tag>", ...] } ],
#     "error":     null | "<message>" }
#
# Redaction request (Java -> worker), used by -redact-comments:
#   { "requestId": "<uuid>",
#     "mode":      "redact",
#     "source":    "<full source text>" }
#
# Redaction response (worker -> Java):
#   { "requestId": "<uuid>",
#     "length":    <int>,                    # UTF-16 length of the source received
#     "comments":  [ [start, end], ... ],    # half-open UTF-16 offsets, ascending
#     "error":     null | "<message>" }
#
# Comments are located with the stdlib tokenizer (ast does not keep them), so a
# '#' inside a string or f-string literal is never reported. Docstrings are
# string literals, not comments, and are therefore not reported. The Java side
# blanks the ranges and re-checks the result; the worker never edits the text.
#
# The worker stays alive processing requests until stdin closes, then exits 0.
# A fatal startup error causes an exit with a non-zero code and a JSON error
# line written to stdout so the Java side can detect and log the failure.
#
# Requirements: Python 3.8+ (ast.Node.end_lineno is available since 3.8).

import ast
import json
import sys
import tokenize


def scan_file(file_path):
    """Parse a Python test file and return a list of test method descriptors."""
    with open(file_path, "r", encoding="utf-8", errors="replace") as f:
        source = f.read()

    tree = ast.parse(source, filename=file_path)
    methods = []

    for node in ast.iter_child_nodes(tree):
        if isinstance(node, ast.ClassDef):
            if _is_test_class(node.name):
                _collect_methods_from_class(node, methods)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if node.name.startswith("test_"):
                tags = _collect_pytest_marks(node)
                methods.append({
                    "name": node.name,
                    "className": None,
                    "beginLine": node.lineno,
                    "endLine": node.end_lineno,
                    "loc": node.end_lineno - node.lineno + 1,
                    "tags": tags,
                })

    return methods


def _collect_methods_from_class(class_node, methods):
    """Collect test methods from a test class node."""
    for item in class_node.body:
        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if item.name.startswith("test_"):
                tags = _collect_pytest_marks(item)
                methods.append({
                    "name": item.name,
                    "className": class_node.name,
                    "beginLine": item.lineno,
                    "endLine": item.end_lineno,
                    "loc": item.end_lineno - item.lineno + 1,
                    "tags": tags,
                })


def _is_test_class(name):
    """Return True when name follows pytest test-class naming conventions."""
    return name.startswith("Test") or name.endswith("Test") or name.endswith("Tests")


def _collect_pytest_marks(node):
    """Extract pytest.mark.xxx decorator names from a function node."""
    tags = []
    for decorator in node.decorator_list:
        # @pytest.mark.xxx
        # AST: Attribute(value=Attribute(value=Name(id='pytest'), attr='mark'), attr='xxx')
        if (
            isinstance(decorator, ast.Attribute)
            and isinstance(decorator.value, ast.Attribute)
            and isinstance(decorator.value.value, ast.Name)
            and decorator.value.value.id == "pytest"
            and decorator.value.attr == "mark"
        ):
            tags.append(decorator.attr)
    return tags


def _split_lines(source):
    """Split on LF only, keeping the terminator, exactly as offsets are counted."""
    parts = source.split("\n")
    lines = [part + "\n" for part in parts[:-1]]
    if parts[-1]:
        lines.append(parts[-1])
    return lines


def _utf16_length(text):
    """Return the length of text in UTF-16 code units (lone surrogates count 1)."""
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


def locate_comments(source):
    """Return the half-open UTF-16 ranges of every COMMENT token in source.

    Raises tokenize.TokenError, IndentationError, SyntaxError or ValueError when
    the text cannot be tokenized; the caller must then not use the text.
    """
    lines = _split_lines(source)
    starts = []
    offset = 0
    for line in lines:
        starts.append(offset)
        offset += _utf16_length(line)

    def position(row, col):
        return starts[row - 1] + _utf16_length(lines[row - 1][:col])

    feed = iter(lines)
    ranges = []
    for token in tokenize.generate_tokens(lambda: next(feed, "")):
        if token.type == tokenize.COMMENT:
            if token.start[0] != token.end[0]:
                raise ValueError("comment spans several lines")
            ranges.append([position(*token.start), position(*token.end)])
    return ranges


def redact_source(request_id, source):
    """Build the response to a redaction request.

    Args:
        request_id: correlation id echoed in the response.
        source: full source text (str); anything else yields an error response.

    Returns:
        A dict with requestId, length (UTF-16 units), comments and error; a
        tokenizer failure is reported in error, never raised.
    """
    if not isinstance(source, str):
        return {"requestId": request_id, "length": 0, "comments": [],
                "error": "redact request has no source text"}
    try:
        comments = locate_comments(source)
    except Exception as exc:
        return {"requestId": request_id, "length": _utf16_length(source), "comments": [],
                "error": "Tokenize error: %s: %s" % (type(exc).__name__, exc)}
    return {"requestId": request_id, "length": _utf16_length(source),
            "comments": comments, "error": None}


def _write_response(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main():
    # Requests are UTF-8 JSON; the platform default (cp1252 on Windows) would
    # corrupt non-ASCII source text.
    sys.stdin.reconfigure(encoding="utf-8")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = None
        try:
            req = json.loads(line)
            request_id = req.get("requestId")
            if req.get("mode") == "redact":
                _write_response(redact_source(request_id, req.get("source")))
                continue
            methods = scan_file(req["filePath"])
            _write_response({"requestId": request_id, "methods": methods, "error": None})
        except Exception as exc:
            _write_response({"requestId": request_id, "methods": [], "error": str(exc)})


if __name__ == "__main__":
    main()
