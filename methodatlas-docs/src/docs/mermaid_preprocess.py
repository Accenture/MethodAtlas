#!/usr/bin/env python3
"""
mermaid_preprocess.py — Render Mermaid code blocks to PNG and emit clean Markdown.

Called by the Gradle :methodatlas-docs:preprocessMermaid task.
Can also be used standalone from the repository root:

    python3 methodatlas-docs/src/docs/mermaid_preprocess.py docs/ai/overrides.md ...

Environment variables (set automatically by the Gradle task):
    OUTPUT_DIR      Directory for processed Markdown files
                    (default: /tmp/docs_processed)
    IMG_DIR         Directory for rendered PNG images
                    (default: /tmp/mermaid_imgs)
    PUPPETEER_CFG   Path to a puppeteer configuration JSON file (optional)
    LIST_FILE       Path where the newline-separated processed-file list is
                    written, for consumption by the pandoc step (optional)

Exit codes:
    0   All files processed (individual mmdc failures are warnings, not errors)
    1   Fatal error (missing arguments, I/O failure)
"""

import os
import re
import shutil
import subprocess
import sys

# ---------------------------------------------------------------------------
# Configuration from environment
# ---------------------------------------------------------------------------

IMG_DIR       = os.environ.get('IMG_DIR',       '/tmp/mermaid_imgs')
OUTPUT_DIR    = os.environ.get('OUTPUT_DIR',    '/tmp/docs_processed')
PUPPETEER_CFG = os.environ.get('PUPPETEER_CFG', '')
LIST_FILE     = os.environ.get('LIST_FILE',     '')

os.makedirs(IMG_DIR,    exist_ok=True)
os.makedirs(OUTPUT_DIR, exist_ok=True)

# ---------------------------------------------------------------------------
# Mermaid code-block pattern
# ---------------------------------------------------------------------------

PATTERN = re.compile(r'```mermaid\s*\n(.*?)```', re.DOTALL)

# ---------------------------------------------------------------------------
# mmdc executable detection
# ---------------------------------------------------------------------------

_counter = 0


def _find_mmdc():
    """Return the path to the mmdc executable, or None if not installed.

    On Windows npm global binaries are installed as .cmd wrappers, so both
    'mmdc' and 'mmdc.cmd' are tried.
    """
    candidates = ['mmdc.cmd', 'mmdc'] if sys.platform == 'win32' else ['mmdc']
    for name in candidates:
        path = shutil.which(name)
        if path:
            return path
    return None


MMDC = _find_mmdc()

# ---------------------------------------------------------------------------
# Diagram rendering
# ---------------------------------------------------------------------------


def render(code):
    """Render a single Mermaid diagram string to a PNG and return an image reference.

    If mmdc is not available or fails, a plain fenced code block is returned as
    a graceful fallback so the Markdown files remain valid without the tool.
    """
    global _counter
    _counter += 1
    mmd_path = os.path.join(IMG_DIR, f'diagram_{_counter:04d}.mmd')
    png_path = os.path.join(IMG_DIR, f'diagram_{_counter:04d}.png')

    with open(mmd_path, 'w', encoding='utf-8') as fh:
        fh.write(code.strip())

    if MMDC is None:
        print(
            f'[WARN] mmdc not found on PATH -- diagram {_counter:04d} left as plain code block. '
            'Install @mermaid-js/mermaid-cli: npm install -g @mermaid-js/mermaid-cli',
            file=sys.stderr,
        )
        return f'```\n{code}```'

    cmd = [MMDC, '-i', mmd_path, '-o', png_path, '-b', 'white', '--scale', '4']
    if PUPPETEER_CFG and os.path.isfile(PUPPETEER_CFG):
        cmd += ['--puppeteerConfigFile', PUPPETEER_CFG]

    print(f'[INFO] diagram {_counter:04d}: {" ".join(cmd)}', file=sys.stderr)

    # 90-second timeout guards against mmdc hanging when the puppeteer/Chrome
    # browser cannot be located (common on first install or restricted machines).
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        print(
            f'[WARN] mmdc timed out for diagram {_counter:04d} '
            '-- Chromium/Chrome may not be installed or puppeteer download incomplete. '
            'Falling back to plain code block.',
            file=sys.stderr,
        )
        return f'```\n{code}```'

    if result.returncode != 0:
        print(
            f'[WARN] mmdc failed for diagram {_counter:04d}: {result.stderr.strip()} '
            '-- falling back to plain code block',
            file=sys.stderr,
        )
        return f'```\n{code}```'

    # Use forward slashes so pandoc and XeLaTeX accept the path on Windows.
    return f'![]({png_path.replace(chr(92), "/")})'


# ---------------------------------------------------------------------------
# File processing
# ---------------------------------------------------------------------------


def process_file(src_path):
    """Read src_path, replace all Mermaid fences with PNG references, return result."""
    with open(src_path, encoding='utf-8') as fh:
        content = fh.read()
    return PATTERN.sub(lambda m: render(m.group(1)), content)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    if len(sys.argv) < 2:
        print(f'Usage: {sys.argv[0]} file1.md [file2.md ...]', file=sys.stderr)
        sys.exit(1)

    processed_paths = []

    for src_path in sys.argv[1:]:
        if not os.path.isfile(src_path):
            print(f'[WARN] File not found, skipping: {src_path}', file=sys.stderr)
            continue

        processed = process_file(src_path)

        # Preserve the relative path structure under OUTPUT_DIR so that files
        # with the same basename (e.g. docs/ai/index.md and
        # docs/usage-modes/index.md) do not overwrite one another.
        try:
            rel = os.path.relpath(src_path, start=os.getcwd())
        except ValueError:
            # On Windows, os.path.relpath raises ValueError for paths on
            # different drives.  Fall back to a flat name in that case.
            rel = os.path.basename(src_path)

        out_path = os.path.join(OUTPUT_DIR, rel)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)

        with open(out_path, 'w', encoding='utf-8') as fh:
            fh.write(processed)

        processed_paths.append(out_path)
        # Print each output path to stdout for shell-script compatibility
        # (mirrors the original behaviour; the Gradle task also captures LIST_FILE).
        print(out_path)

    if LIST_FILE:
        with open(LIST_FILE, 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(processed_paths))

    print(
        f'[INFO] Processed {len(processed_paths)} file(s); '
        f'{_counter} Mermaid diagram(s) handled.',
        file=sys.stderr,
    )


if __name__ == '__main__':
    main()
