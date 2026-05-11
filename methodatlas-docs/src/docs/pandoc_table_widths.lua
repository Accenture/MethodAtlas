-- pandoc_table_widths.lua
--
-- Normalize Pandoc table column widths for LaTeX/PDF output.
--
-- This avoids odd column distributions caused by Markdown pipe-table source
-- formatting. The source Markdown can remain MkDocs-friendly.

local function lower(text)
  return string.lower(text or "")
end

local function cell_text(cell)
  return lower(pandoc.utils.stringify(cell))
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

    if contains(headers[2], "details")
        or contains(headers[2], "meaning")
        or contains(headers[2], "description")
        or contains(headers[2], "explanation") then
      return set_widths(tbl, {0.30, 0.70})
    end

    return set_widths(tbl, {0.35, 0.65})
  end

  -- Workflow/platform/trigger tables.
  --
  -- Platform is usually short; trigger text needs more room.
  if column_count == 3 then
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
      return set_widths(tbl, {0.3, 0.2, 0.2, 0.3})
    end

    if contains(headers[1], "field")
        and contains(headers[2], "required")
        and contains(headers[3], "type")
        and contains(headers[4], "meaning") then
      return set_widths(tbl, {0.25, 0.1, 0.15, 0.50})
    end

    return set_widths(tbl, {0.25, 0.25, 0.25, 0.25})
  end

  if column_count == 5 then
    return set_widths(tbl, {0.20, 0.20, 0.20, 0.20, 0.20})
  end

  return nil
end
