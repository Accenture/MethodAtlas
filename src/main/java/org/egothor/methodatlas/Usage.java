package org.egothor.methodatlas;

import java.io.PrintWriter;

/**
 * Renders the {@code -help} usage screen.
 *
 * <p>
 * The screen is a deliberately terse signpost, not an exhaustive manual: it
 * lists the synopsis and a one-line summary per option group, then points at
 * the canonical, always-current CLI reference on the documentation site. Keeping
 * it terse avoids a third full copy of the flag list (alongside the
 * {@link MethodAtlasApp} class Javadoc and {@code docs/cli-reference.md}) drifting
 * out of sync.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see MethodAtlasApp
 * @since 4.0.0
 */
final class Usage {

    /** Canonical, always-current CLI reference. */
    /* default */ static final String CLI_REFERENCE_URL =
            "https://accenture.github.io/MethodAtlas/cli-reference/";

    private static final String TEXT = """
            MethodAtlas — scan test source trees and classify test methods.

            Usage:
              methodatlas [options] [path ...]

            If no path is given, the current directory is scanned. Multiple roots are allowed.

            Output modes (default: CSV to stdout):
              -plain                 Plain text instead of CSV
              -sarif                 SARIF 2.1.0 JSON (security-only by default)
              -json                  Flat JSON array
              -github-annotations    GitHub Actions ::notice/::warning commands

            Discovery:
              -file-suffix <suffix>  File-name suffix to scan; repeatable (default: java:Test.java)
              -test-marker <name>    Annotation/attribute marking a test method; repeatable
              -property <key>=<val>  Plugin-specific property; repeatable
              -config <file>         Load defaults from a YAML config file (CLI flags override)

            AI enrichment:
              -ai                    Enable AI classification
              -ai-provider <name>    auto | ollama | openai | anthropic | azure_openai | ...
              -ai-model <model>      Provider-specific model name
              -ai-api-key-env <env>  Resolve the API key from an environment variable
              (see the reference for the full -ai-* and manual-workflow options)

            Source write-back:
              -apply-tags            Write AI @Tag/@DisplayName back to source (requires -ai)
              -apply-tags-from-csv <file>
                                     Apply a reviewed CSV as the desired state (Java and C#)
              -mismatch-limit <n>    Abort apply-from-csv if mismatches reach n (default: -1 = warn)
              -promote-ai            RISKY, NOT RECOMMENDED. With -apply-tags-from-csv, fills blank
                                     tags/display_name from ai_tags/ai_display_name — i.e. writes
                                     UNVALIDATED AI output into source, bypassing human review. Off
                                     by default; do not enable unless reviewed and approved.

            Diagnostics:
              -verbose               Detailed diagnostics (notably for -apply-tags-from-csv)
              -help, --help, -h      Show this help and exit

            This is a summary. The complete, authoritative option reference is at:
              """ + CLI_REFERENCE_URL;

    private Usage() {
    }

    /**
     * Prints the usage screen.
     *
     * @param out writer that receives the usage text; never {@code null}
     */
    /* default */ static void print(PrintWriter out) {
        out.println(TEXT);
    }
}
