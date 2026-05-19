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
# The worker stays alive processing requests until stdin closes, then exits 0.
# A fatal startup error causes an exit with a non-zero code and a JSON error
# line written to stdout so the Java side can detect and log the failure.
#
# Requirements: Python 3.8+ (ast.Node.end_lineno is available since 3.8).

import ast
import json
import sys


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


def _write_response(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = None
        try:
            req = json.loads(line)
            request_id = req.get("requestId")
            methods = scan_file(req["filePath"])
            _write_response({"requestId": request_id, "methods": methods, "error": None})
        except Exception as exc:
            _write_response({"requestId": request_id, "methods": [], "error": str(exc)})


if __name__ == "__main__":
    main()
