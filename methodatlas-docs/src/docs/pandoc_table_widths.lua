-- pandoc_table_widths.lua
--
-- Normalize Pandoc table column widths for LaTeX/PDF output.
--
-- This avoids odd column distributions caused by Markdown pipe-table source
-- formatting. The source Markdown can remain MkDocs-friendly.

local function lower(text)
  return string.lower(text or "")
end

-- Stringify a header cell across pandoc Lua API versions.
--
-- Pandoc < 2.10 represented a header cell directly as a list of blocks,
-- which `pandoc.utils.stringify` accepts. Pandoc 2.10+ introduced the
-- row-based table model where each entry is a `Cell` object; on
-- versions before 2.17 `stringify` cannot accept a `Cell` directly and
-- raises "table expected, got pandoc Cell". Reaching into `.contents`
-- works on every version in the 2.10–2.17 range and is the canonical
-- accessor in the new model.
local function cell_text(cell)
  if cell == nil then
    return ""
  end
  local target = cell
  if cell.contents ~= nil then
    target = cell.contents
  end
  return lower(pandoc.utils.stringify(target))
end

local function header_texts(tbl)
  local headers = {}

  if tbl.head == nil or tbl.head.rows == nil or tbl.head.rows[1] == nil then
    return headers
  end

  for index, cell in ipairs(tbl.head.rows[1].cells) do
    headers[index] = cell_text(cell)
  end

  return headers
end

local function set_widths(tbl, widths)
  for index, width in ipairs(widths) do
    if tbl.colspecs[index] ~= nil then
      -- Pandoc colspecs are normally {alignment, width}.
      tbl.colspecs[index][2] = width

      -- Some Pandoc versions expose named fields as well; setting this is
      -- harmless where unsupported and helpful where supported.
      tbl.colspecs[index].width = width
    end
  end

  return tbl
end

local function contains(text, fragment)
  return text ~= nil and text:find(fragment, 1, true) ~= nil
end

function Table(tbl)
  if not FORMAT:match("latex") then
    return nil
  end

  local column_count = #tbl.colspecs
  local headers = header_texts(tbl)

  -- Common 2-column reference tables:
  --
  --   Requirement | Details
  --   Token       | Meaning
  --   Option      | Description
  --
  -- The second column should normally dominate.
  if column_count == 2 then
    -- Evidence-pack / gen-signing-key flag tables: "Flag | Purpose" and
    -- "Sub-flag | Purpose". Option names in column 1 run long
    -- (-evidence-pack-keyring-env <name>), so the left column needs real
    -- room. Must precede the generic "purpose" rule below.
    if contains(headers[1], "flag")
        and contains(headers[2], "purpose") then
      return set_widths(tbl, {0.45, 0.55})
    end

    if contains(headers[1], "situation")
        and contains(headers[2], "mode") then
      return set_widths(tbl, {0.60, 0.40})
    end

    if contains(headers[1], "button")
        and contains(headers[2], "what") then
      return set_widths(tbl, {0.25, 0.75})
    end

    if contains(headers[1], "symbol")
        and contains(headers[2], "meaning") then
      return set_widths(tbl, {0.2, 0.8})
    end

    -- Track-summary "Read | Why … / What it answers / Why it matters / When …"
    if contains(headers[1], "read") then
      return set_widths(tbl, {0.32, 0.68})
    end

    -- Discovery-plugin "Suffix | Example"
    if contains(headers[1], "suffix")
        and contains(headers[2], "example") then
      return set_widths(tbl, {0.35, 0.65})
    end

    -- Fast-track "If you want to… | Read"
    if contains(headers[1], "if you want")
        or contains(headers[1], "you want") then
      return set_widths(tbl, {0.55, 0.45})
    end

    if contains(headers[1], "field")
        and contains(headers[2], "compared") then
      return set_widths(tbl, {0.2, 0.8})
    end

    if contains(headers[1], "score")
        and contains(headers[2], "meaning") then
      return set_widths(tbl, {0.1, 0.9})
    end

    if contains(headers[1], "indicator")
        and contains(headers[2], "meaning") then
      return set_widths(tbl, {0.1, 0.9})
    end

    -- Specific shape "Command | Condition" — Command holds full GitHub
    -- Actions workflow-command syntax like
    --   ::warning file=…,line=…,title=…::…
    -- which is much wider than a single-token value. Must come BEFORE the
    -- short-token rule below so the generic 'command' match does not fire.
    if contains(headers[1], "command")
        and contains(headers[2], "condition") then
      return set_widths(tbl, {0.42, 0.58})
    end

    -- Short-token left column paired with prose right column.
    -- Values like 'none', 'auto', '1.0', '-1', or single-flag identifiers
    -- only need 20 %; the explanation column gets the rest. Matching is
    -- on the header word in column 1.
    if contains(headers[1], "value")
        or contains(headers[1], "setting")
        or contains(headers[1], "code")
        or contains(headers[1], "token")
        or contains(headers[1], "level")
        or contains(headers[1], "limit") then
      return set_widths(tbl, {0.20, 0.80})
    end

    -- Property | Description — property keys in the docs are camelCase
    -- identifiers up to ~22 chars (aiInteractionScore). Give them 28 %
    -- so they never collide with the description column.
    if contains(headers[1], "property")
        and (contains(headers[2], "description")
             or contains(headers[2], "meaning")
             or contains(headers[2], "details")) then
      return set_widths(tbl, {0.28, 0.72})
    end

    -- Module names in this project are monospace dotted/hyphenated paths
    -- up to 23+ chars (methodatlas-discovery-typescript). Give them more
    -- room than the generic medium-identifier rule.
    if contains(headers[1], "module") then
      return set_widths(tbl, {0.30, 0.70})
    end

    -- Slightly longer identifiers in column 1, but the prose still
    -- dominates.
    if contains(headers[1], "extension")
        or contains(headers[1], "aspect")
        or contains(headers[1], "area")
        or contains(headers[1], "element")
        or contains(headers[1], "framework")
        or contains(headers[1], "convention")
        or contains(headers[1], "imports")
        or contains(headers[1], "source")
        or contains(headers[1], "icon")
        or contains(headers[1], "challenge")
        or contains(headers[1], "document")
        or contains(headers[1], "series")
        or contains(headers[1], "key") then
      return set_widths(tbl, {0.25, 0.75})
    end

    if contains(headers[2], "details")
        or contains(headers[2], "meaning")
        or contains(headers[2], "description")
        or contains(headers[2], "explanation")
        or contains(headers[2], "behaviour")
        or contains(headers[2], "behavior")
        or contains(headers[2], "condition")
        or contains(headers[2], "responsibility")
        or contains(headers[2], "contents")
        or contains(headers[2], "notes")
        or contains(headers[2], "purpose") then
      return set_widths(tbl, {0.25, 0.75})
    end

    return set_widths(tbl, {0.35, 0.65})
  end

  -- Workflow/platform/trigger tables.
  --
  -- Platform is usually short; trigger text needs more room.
  if column_count == 3 then
    -- Evidence-pack signing-algorithm table:
    --   -evidence-pack-sign-algo value | Kind | Backed by
    -- Column 1 holds algorithm tokens (Ed25519+SPHINCS+), Kind is one short
    -- word, "Backed by" carries the widest code expression.
    if contains(headers[2], "kind")
        and contains(headers[3], "backed") then
      return set_widths(tbl, {0.34, 0.16, 0.50})
    end

    -- Evidence-pack supported-frameworks table:
    --   Token (CLI input) | Canonical form | What it maps to
    if contains(headers[1], "token")
        and contains(headers[3], "maps") then
      return set_widths(tbl, {0.24, 0.22, 0.54})
    end

    if contains(headers[1], "workflow")
        and contains(headers[2], "platform")
        and contains(headers[3], "trigger") then
      return set_widths(tbl, {0.43, 0.17, 0.40})
    end

    if contains(headers[1], "argument")
        and contains(headers[2], "meaning")
        and contains(headers[3], "default") then
      return set_widths(tbl, {0.28, 0.58, 0.14})
    end

    if contains(headers[1], "rule")
        and contains(headers[2], "level")
        and contains(headers[3], "meaning") then
      return set_widths(tbl, {0.3, 0.15, 0.55})
    end

    if contains(headers[1], "framework")
        and contains(headers[2], "detected")
        and contains(headers[3], "markers") then
      return set_widths(tbl, {0.2, 0.4, 0.4})
    end

    if contains(headers[1], "framework")
        and contains(headers[2], "tag")
        and contains(headers[3], "name") then
      return set_widths(tbl, {0.2, 0.4, 0.4})
    end

    if contains(headers[1], "property")
        and contains(headers[2], "meaning")
        and contains(headers[3], "values") then
      return set_widths(tbl, {0.4, 0.4, 0.2})
    end

    if contains(headers[1], "icon")
        and contains(headers[2], "colour")
        and contains(headers[3], "meaning") then
      return set_widths(tbl, {0.2, 0.2, 0.6})
    end

    if contains(headers[1], "range")
        and contains(headers[2], "meaning")
        and contains(headers[3], "example") then
      return set_widths(tbl, {0.2, 0.4, 0.4})
    end

    if contains(headers[1], "secret")
        and contains(headers[2], "feature")
        and contains(headers[3], "obtain") then
      return set_widths(tbl, {0.2, 0.4, 0.4})
    end

    if contains(headers[1], "column")
        and contains(headers[2], "when")
        and contains(headers[3], "meaning") then
      return set_widths(tbl, {0.2, 0.2, 0.6})
    end

    if contains(headers[1], "value")
        and contains(headers[2], "meaning")
        and contains(headers[3], "action") then
      return set_widths(tbl, {0.15, 0.5, 0.35})
    end

    -- README "Source-derived fields" / "AI enrichment fields":
    --   Field | Present when | Description
    -- Field and Present when are short; description is the main column.
    if contains(headers[1], "field")
        and contains(headers[2], "present when") then
      return set_widths(tbl, {0.24, 0.20, 0.56})
    end

    -- Audit-trail "Field | Always present | Notes" — same shape as field tables.
    if contains(headers[1], "field")
        and contains(headers[2], "always present") then
      return set_widths(tbl, {0.20, 0.18, 0.62})
    end

    -- CLI reference framework auto-detection:
    --   Detected framework | Imports matched | Annotation set used
    if contains(headers[1], "detected framework")
        and contains(headers[2], "imports matched") then
      return set_widths(tbl, {0.18, 0.30, 0.52})
    end

    -- Detection table after the split:
    --   Language | Plugin module | Test frameworks
    if contains(headers[1], "language")
        and contains(headers[2], "plugin module")
        and contains(headers[3], "test framework") then
      return set_widths(tbl, {0.18, 0.30, 0.52})
    end

    -- Per-provider 3-column tables built from short keys:
    --   Provider value | AI product / platform | Deployment
    if contains(headers[1], "provider value")
        and contains(headers[2], "ai product") then
      return set_widths(tbl, {0.22, 0.40, 0.38})
    end

    -- Installation Requirements table:
    --   Requirement | Version | Required for
    if contains(headers[1], "requirement")
        and contains(headers[2], "version") then
      return set_widths(tbl, {0.22, 0.30, 0.48})
    end

    -- Fast Track option picker:
    --   Option | When it fits | Cost
    if contains(headers[1], "option")
        and contains(headers[2], "when it fits")
        and contains(headers[3], "cost") then
      return set_widths(tbl, {0.22, 0.55, 0.23})
    end

    -- C# / .NET attribute mapping:
    --   Framework | Attribute read | Argument used
    if contains(headers[1], "framework")
        and contains(headers[2], "attribute read") then
      return set_widths(tbl, {0.18, 0.42, 0.40})
    end

    -- C# / .NET tag + display name:
    --   Framework | Tag written as | Display name
    if contains(headers[1], "framework")
        and contains(headers[2], "tag written") then
      return set_widths(tbl, {0.18, 0.42, 0.40})
    end

    -- C# / .NET detection:
    --   Framework | Detected by | Default markers
    if contains(headers[1], "framework")
        and contains(headers[2], "detected by") then
      return set_widths(tbl, {0.18, 0.42, 0.40})
    end

    -- Parser-internals element / detected / notes:
    --   Element | Detected | Notes
    if contains(headers[1], "element")
        and contains(headers[2], "detected") then
      return set_widths(tbl, {0.42, 0.13, 0.45})
    end

    -- Architecture extension / SPI / module:
    --   Extension | SPI | Module
    if contains(headers[1], "extension")
        and contains(headers[2], "spi")
        and contains(headers[3], "module") then
      return set_widths(tbl, {0.22, 0.55, 0.23})
    end

    -- Architecture per-module gates:
    --   Module | JaCoCo floor | PIT floor | Notes (handled in column_count==4)
    -- CI-setup secret table:
    --   Secret | Feature unlocked | How to obtain
    if contains(headers[1], "secret")
        and contains(headers[2], "feature unlocked") then
      return set_widths(tbl, {0.22, 0.38, 0.40})
    end

    -- CI-setup workflow table:
    --   Workflow | Platform | Trigger (also covered below for variations)
    -- Compliance mapping tables (3-col):
    --   <Standard> requirement | MethodAtlas feature | Evidence produced
    if (contains(headers[1], "activity") or contains(headers[1], "task")
            or contains(headers[1], "objective") or contains(headers[1], "requirement")
            or contains(headers[1], "control"))
        and (contains(headers[2], "methodatlas feature") or contains(headers[2], "feature")) then
      return set_widths(tbl, {0.30, 0.40, 0.30})
    end

    -- Discovery plugins "Property key | Meaning | Default" / "Default values":
    -- Property-key column holds long dotted paths (python.maxConsecutiveRestarts,
    -- typescript.workerTimeoutSec) in monospace; needs enough room for the
    -- widest identifier on a single line.
    if contains(headers[1], "property key")
        and contains(headers[2], "meaning") then
      return set_widths(tbl, {0.40, 0.42, 0.18})
    end

    -- Discovery plugins COBOL/ABAP "Convention | File extension(s) | What is emitted"
    -- already handled. Add: "Convention | Example | Active when"
    if contains(headers[1], "convention")
        and contains(headers[2], "example")
        and contains(headers[3], "active when") then
      return set_widths(tbl, {0.22, 0.38, 0.40})
    end

    -- Migration "Area | What changed | Action required":
    if contains(headers[1], "area")
        and contains(headers[2], "what changed")
        and contains(headers[3], "action") then
      return set_widths(tbl, {0.18, 0.46, 0.36})
    end

    -- Output formats CSV vs JSON aspect comparison:
    --   Aspect | CSV | JSON
    if contains(headers[1], "aspect")
        and contains(headers[2], "csv")
        and contains(headers[3], "json") then
      return set_widths(tbl, {0.30, 0.35, 0.35})
    end

    -- Output formats field-reference type table:
    --   Field | Type | When present
    if contains(headers[1], "field")
        and contains(headers[2], "type")
        and contains(headers[3], "when present") then
      return set_widths(tbl, {0.30, 0.20, 0.50})
    end

    -- SARIF rule table:
    --   Rule ID | Level | Meaning
    if contains(headers[1], "rule id")
        and contains(headers[2], "level")
        and contains(headers[3], "meaning") then
      return set_widths(tbl, {0.32, 0.14, 0.54})
    end

    -- CLI-reference "Argument | Meaning | Default" — already covered earlier.
    -- CLI-reference 3-col "Detected framework | Imports matched | Annotation set used"
    -- — already covered earlier.

    if contains(headers[1], "priority")
        and contains(headers[2], "condition")
        and contains(headers[3], "action") then
      return set_widths(tbl, {0.15, 0.3, 0.55})
    end

    if contains(headers[1], "level")
        and contains(headers[2], "target")
        and contains(headers[3], "description") then
      return set_widths(tbl, {0.15, 0.2, 0.65})
    end

    if contains(headers[1], "provider")
        and contains(headers[2], "residency")
        and contains(headers[3], "credentials") then
      return set_widths(tbl, {0.25, 0.5, 0.25})
    end

    if contains(headers[1], "artefact")
        and contains(headers[2], "source")
        and contains(headers[3], "retention") then
      return set_widths(tbl, {0.32, 0.43, 0.25})
    end

    if contains(headers[1], "artefact")
        and contains(headers[2], "content")
        and contains(headers[3], "retention") then
      return set_widths(tbl, {0.32, 0.43, 0.25})
    end

    if contains(headers[1], "model")
        and contains(headers[2], "size")
        and contains(headers[3], "notes") then
      return set_widths(tbl, {0.25, 0.15, 0.6})
    end

    if contains(headers[1], "area")
        and contains(headers[2], "what")
        and contains(headers[3], "action") then
      return set_widths(tbl, {0.25, 0.35, 0.4})
    end

    if contains(headers[2], "feature")
        and contains(headers[3], "evidence") then
      return set_widths(tbl, {0.3, 0.4, 0.3})
    end

    -- Fast Track "You have | What to do | Reference"
    if contains(headers[1], "you have")
        and contains(headers[2], "what to do") then
      return set_widths(tbl, {0.28, 0.47, 0.25})
    end

    -- Fast Track "Flag | Output | When to use"
    if contains(headers[1], "flag")
        and contains(headers[2], "output") then
      return set_widths(tbl, {0.22, 0.20, 0.58})
    end

    -- Implementer Track "Platform | Recipe | Notes"
    if contains(headers[1], "platform")
        and contains(headers[2], "recipe") then
      return set_widths(tbl, {0.22, 0.30, 0.48})
    end

    -- Implementer Track "Format | When | Schema page"
    if contains(headers[1], "format")
        and contains(headers[2], "when")
        and contains(headers[3], "schema") then
      return set_widths(tbl, {0.25, 0.40, 0.35})
    end

    -- Implementer Track "Framework | Page | Substantive citations"
    if contains(headers[1], "framework")
        and contains(headers[2], "page")
        and contains(headers[3], "substantive") then
      return set_widths(tbl, {0.28, 0.27, 0.45})
    end

    -- Discovery plugins "Convention | File extension(s) | What is emitted"
    if contains(headers[1], "convention")
        and contains(headers[3], "emitted") then
      return set_widths(tbl, {0.24, 0.26, 0.50})
    end

    -- Generic 3-column table: keep the last column reasonably wide.
    return set_widths(tbl, {0.32, 0.23, 0.45})
  end

  -- Generic fallback for wider tables.
  if column_count == 4 then
    if contains(headers[1], "knob")
        and contains(headers[2], "flag")
        and contains(headers[3], "key")
        and contains(headers[4], "what") then
      return set_widths(tbl, {0.2, 0.25, 0.2, 0.35})
    end

    if contains(headers[1], "key")
        and contains(headers[2], "type")
        and contains(headers[3], "default")
        and contains(headers[4], "description") then
      -- Keys are full dotted property paths up to ~32 chars in monospace
      -- (typescript.maxConsecutiveRestarts) with no internal break
      -- opportunity, so the column must fit them on one line. Type and
      -- Default columns still need enough room for List<String> and
      -- common literal values; Description wraps freely.
      return set_widths(tbl, {0.44, 0.16, 0.13, 0.27})
    end

    if contains(headers[1], "field")
        and contains(headers[2], "required")
        and contains(headers[3], "type")
        and contains(headers[4], "meaning") then
      return set_widths(tbl, {0.25, 0.1, 0.15, 0.50})
    end

    -- Architecture per-module gates:
    --   Module | JaCoCo floor | PIT floor | Notes
    if contains(headers[1], "module")
        and contains(headers[2], "jacoco")
        and contains(headers[3], "pit") then
      return set_widths(tbl, {0.28, 0.14, 0.14, 0.44})
    end

    -- Discovery plugin knob table:
    --   Config knob | CLI flag | YAML key | What it does
    if contains(headers[1], "config knob")
        and contains(headers[2], "cli flag")
        and contains(headers[3], "yaml key") then
      return set_widths(tbl, {0.18, 0.22, 0.18, 0.42})
    end

    -- Migration "Area | What changed | Action required" (only three columns
    -- but listed here for completeness; rule lives in column_count==3 branch).

    -- Quality-gate per-module table:
    --   Gate | Tool | Threshold | Scope
    if contains(headers[1], "gate")
        and contains(headers[2], "tool")
        and contains(headers[3], "threshold") then
      return set_widths(tbl, {0.30, 0.15, 0.25, 0.30})
    end

    -- Discovery plugins source-write-back support:
    --   Plugin | Discovery | SourcePatcher | What -apply-tags does
    -- First three columns are short identifiers; last column carries prose.
    if contains(headers[1], "plugin")
        and contains(headers[2], "discovery")
        and (contains(headers[3], "sourcepatcher") or contains(headers[3], "source-patcher")) then
      return set_widths(tbl, {0.13, 0.13, 0.24, 0.50})
    end

    -- Audit-trail artefact table:
    --   Artefact | Producer | Location | Schema
    if contains(headers[1], "artefact")
        and contains(headers[2], "producer")
        and contains(headers[3], "location") then
      return set_widths(tbl, {0.18, 0.30, 0.32, 0.20})
    end

    -- AI provider mapping table (README + providers.md):
    --   AI assistant / product | Underlying platform | MethodAtlas provider | Free tier
    if contains(headers[1], "ai assistant")
        or (contains(headers[1], "assistant") and contains(headers[2], "platform")) then
      return set_widths(tbl, {0.30, 0.30, 0.22, 0.18})
    end

    return set_widths(tbl, {0.25, 0.25, 0.25, 0.25})
  end

  if column_count == 5 then
    -- Annotation-support table (post-split):
    --   Language | Tag attribute | Display-name support | Source write-back | Requires
    if contains(headers[1], "language")
        and contains(headers[2], "tag attribute") then
      return set_widths(tbl, {0.16, 0.30, 0.24, 0.16, 0.14})
    end

    return set_widths(tbl, {0.20, 0.20, 0.20, 0.20, 0.20})
  end

  return nil
end
