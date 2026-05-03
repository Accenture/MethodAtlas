#!/usr/bin/env python3
"""
mkdocs_to_pandoc.py — Convert MkDocs-flavoured Markdown into Pandoc-friendly Markdown.

This script is intended only for the PDF build path. The source documentation
remains MkDocs-native.

Currently handled:
    - MkDocs/Python-Markdown admonitions:
          !!! note "Title"
              Body
      converted to Pandoc fenced Divs:
          ::: {.note title="Title"}
          Body
          :::

    - Collapsible Material/MkDocs blocks:
          ??? note "Title"
          ???+ note "Title"
      converted to fenced Divs with .collapsible / .open classes.

Environment variables:
    OUTPUT_DIR      Directory where converted Markdown files are written.
    LIST_FILE       Optional path where converted file names are written.
    SOURCE_ROOT     Optional root used to preserve relative paths.
"""

from __future__ import annotations

import os
import pathlib
import re
import sys


OUTPUT_DIR = pathlib.Path(os.environ.get("OUTPUT_DIR", "/tmp/pandoc_source"))
LIST_FILE = os.environ.get("LIST_FILE", "")
SOURCE_ROOT = pathlib.Path(os.environ.get("SOURCE_ROOT", os.getcwd())).resolve()

OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


ADMONITION_RE = re.compile(
    r"""
    ^(?P<indent>[ \t]*)
    (?P<marker>!!!|\?\?\+?|\?\?)
    [ \t]+
    (?P<kind>[A-Za-z][A-Za-z0-9_-]*)
    (?:[ \t]+(?P<title>"[^"]*"|'[^']*'|.+?))?
    [ \t]*$
    """,
    re.VERBOSE,
)

TAB_RE = re.compile(
    r"""
    ^(?P<indent>[ \t]*)
    ===
    [ \t]+
    (?P<quote>["'])
    (?P<title>.*?)
    (?P=quote)
    (?:[ \t]+\{(?P<attrs>[^}]*)\})?
    [ \t]*$
    """,
    re.VERBOSE,
)

def indentation_width(text: str) -> int:
    width = 0
    for char in text:
        if char == " ":
            width += 1
        elif char == "\t":
            width += 4
        else:
            break
    return width


def strip_indent(line: str, columns: int) -> str:
    remaining = columns
    index = 0

    while index < len(line) and remaining > 0:
        char = line[index]

        if char == " ":
            remaining -= 1
            index += 1
        elif char == "\t":
            remaining -= 4
            index += 1
        else:
            break

    return line[index:]


def normalize_title(raw: str | None) -> str | None:
    if raw is None:
        return None

    value = raw.strip()

    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        value = value[1:-1]

    return value.replace('"', '\\"')


def convert_admonitions(text: str) -> str:
    lines = text.splitlines(keepends=True)
    output: list[str] = []

    # Stack entries:
    #   (admonition_indent, body_indent)
    stack: list[tuple[int, int]] = []

    for line in lines:
        without_newline = line.rstrip("\n")
        is_blank = without_newline.strip() == ""
        current_indent = indentation_width(without_newline)

        match = ADMONITION_RE.match(without_newline)

        if not is_blank and match is None:
            while stack and current_indent < stack[-1][1]:
                admonition_indent, _ = stack.pop()
                output.append(" " * admonition_indent + ":::\n")

        if match is not None:
            indent_text = match.group("indent")
            marker = match.group("marker")
            kind = match.group("kind")
            title = normalize_title(match.group("title"))

            admonition_indent = indentation_width(indent_text)
            body_indent = admonition_indent + 4

            while stack and admonition_indent < stack[-1][1]:
                closing_indent, _ = stack.pop()
                output.append(" " * closing_indent + ":::\n")

            classes = [kind]

            if marker.startswith("??"):
                classes.append("collapsible")
                if marker == "???+":
                    classes.append("open")

            class_attr = " ".join("." + item for item in classes)

            if title:
                output.append(f'{indent_text}::: {{{class_attr} title="{title}"}}\n')
            else:
                output.append(f"{indent_text}::: {{{class_attr}}}\n")

            stack.append((admonition_indent, body_indent))
            continue

        if stack and not is_blank:
            _, body_indent = stack[-1]
            output.append(strip_indent(line, body_indent))
        else:
            output.append(line)

    while stack:
        admonition_indent, _ = stack.pop()
        output.append(" " * admonition_indent + ":::\n")

    return "".join(output)


def convert_tabs(text: str) -> str:
    """
    Convert PyMdown/MkDocs tabbed blocks into Pandoc fenced Divs.

    Input:

        === "Linux"
            Content.

        === "Windows"
            Content.

    Output:

        :::: {.tabset}

        ::: {.tab title="Linux"}
        Content.
        :::

        ::: {.tab title="Windows"}
        Content.
        :::

        ::::

    Consecutive tabs at the same indentation level are grouped into one tabset.
    """

    lines = text.splitlines(keepends=True)
    output: list[str] = []

    # Stack entries:
    #   {
    #       "indent": tab_marker_indent,
    #       "body_indent": tab_body_indent,
    #       "open_tab": bool
    #   }
    stack: list[dict[str, int | bool]] = []

    def close_tab() -> None:
        if stack and stack[-1]["open_tab"]:
            output.append(" " * int(stack[-1]["indent"]) + ":::\n")
            stack[-1]["open_tab"] = False

    def close_tabset() -> None:
        close_tab()
        if stack:
            indent = int(stack[-1]["indent"])
            output.append(" " * indent + "::::\n")
            stack.pop()

    for line in lines:
        without_newline = line.rstrip("\n")
        is_blank = without_newline.strip() == ""
        current_indent = indentation_width(without_newline)

        match = TAB_RE.match(without_newline)

        # Close tabsets when normal content returns to their parent indentation.
        if not is_blank and match is None:
            while stack and current_indent < int(stack[-1]["body_indent"]):
                close_tabset()

        if match is not None:
            indent_text = match.group("indent")
            tab_indent = indentation_width(indent_text)
            body_indent = tab_indent + 4
            title = normalize_title(match.group("title")) or "Tab"

            # If this tab starts at a shallower indentation, close nested tabsets.
            while stack and tab_indent < int(stack[-1]["body_indent"]):
                close_tabset()

            # If there is no active tabset at this indentation, open one.
            if not stack or tab_indent != int(stack[-1]["indent"]):
                output.append(f"{indent_text}:::: {{.tabset}}\n\n")
                stack.append(
                    {
                        "indent": tab_indent,
                        "body_indent": body_indent,
                        "open_tab": False,
                    }
                )
            else:
                # Same tabset, next tab.
                close_tab()
                output.append("\n")

            output.append(f'{indent_text}::: {{.tab title="{title}"}}\n')
            stack[-1]["open_tab"] = True
            continue

        if stack and not is_blank:
            body_indent = int(stack[-1]["body_indent"])
            output.append(strip_indent(line, body_indent))
        else:
            output.append(line)

    while stack:
        close_tabset()

    return "".join(output)


def output_path_for(source: pathlib.Path) -> pathlib.Path:
    source = source.resolve()

    try:
        relative = source.relative_to(SOURCE_ROOT)
    except ValueError:
        relative = pathlib.Path(source.name)

    return OUTPUT_DIR / relative


def process_file(source: pathlib.Path) -> pathlib.Path:
    text = source.read_text(encoding="utf-8")
    converted = convert_admonitions(text)
    converted = convert_tabs(converted)

    target = output_path_for(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(converted, encoding="utf-8")

    return target


def main() -> int:
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} file1.md [file2.md ...]", file=sys.stderr)
        return 1

    processed_paths: list[pathlib.Path] = []

    for argument in sys.argv[1:]:
        source = pathlib.Path(argument)

        if not source.is_file():
            print(f"[WARN] File not found, skipping: {source}", file=sys.stderr)
            continue

        target = process_file(source)
        processed_paths.append(target)
        print(str(target))

    if LIST_FILE:
        pathlib.Path(LIST_FILE).write_text(
            "\n".join(str(path) for path in processed_paths),
            encoding="utf-8",
        )

    print(
        f"[INFO] Converted {len(processed_paths)} MkDocs Markdown file(s) for Pandoc.",
        file=sys.stderr,
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
