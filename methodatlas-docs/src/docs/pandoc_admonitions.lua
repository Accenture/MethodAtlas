-- pandoc_admonitions.lua
--
-- Render Pandoc fenced Div admonitions as LaTeX tcolorbox blocks.
--
-- Expected input, produced by the MkDocs-to-Pandoc staging script:
--
--   ::: {.note title="Configuration location"}
--   Body text.
--   :::
--
--   ::: {.warning .collapsible title="Advanced option"}
--   Body text.
--   :::
--
-- This filter does not parse MkDocs "!!! note" syntax directly. That should
-- already have been converted before Pandoc reads the Markdown file.

local admonition_kinds = {
  note      = true,
  abstract  = true,
  summary   = true,
  tldr      = true,
  info      = true,
  todo      = true,
  tip       = true,
  hint      = true,
  important = true,
  success   = true,
  check     = true,
  done      = true,
  question  = true,
  help      = true,
  faq       = true,
  warning   = true,
  caution   = true,
  attention = true,
  failure   = true,
  fail      = true,
  missing   = true,
  danger    = true,
  error     = true,
  bug       = true,
  example   = true,
  quote     = true,
  cite      = true
}

local canonical_kind = {
  summary   = "abstract",
  tldr      = "abstract",

  todo      = "info",

  hint      = "tip",
  important = "tip",

  check     = "success",
  done      = "success",

  help      = "question",
  faq       = "question",

  caution   = "warning",
  attention = "warning",

  fail      = "failure",
  missing   = "failure",

  error     = "danger",

  cite      = "quote"
}

local default_titles = {
  note     = "Note",
  abstract = "Abstract",
  info     = "Info",
  tip      = "Tip",
  success  = "Success",
  question = "Question",
  warning  = "Warning",
  failure  = "Failure",
  danger   = "Danger",
  bug      = "Bug",
  example  = "Example",
  quote    = "Quote"
}

local colors = {
  note = {
    back = "maNoteBack",
    frame = "maNoteFrame"
  },
  abstract = {
    back = "maNoteBack",
    frame = "maNoteFrame"
  },
  info = {
    back = "maInfoBack",
    frame = "maInfoFrame"
  },
  tip = {
    back = "maTipBack",
    frame = "maTipFrame"
  },
  success = {
    back = "maTipBack",
    frame = "maTipFrame"
  },
  question = {
    back = "maInfoBack",
    frame = "maInfoFrame"
  },
  warning = {
    back = "maWarningBack",
    frame = "maWarningFrame"
  },
  failure = {
    back = "maDangerBack",
    frame = "maDangerFrame"
  },
  danger = {
    back = "maDangerBack",
    frame = "maDangerFrame"
  },
  bug = {
    back = "maDangerBack",
    frame = "maDangerFrame"
  },
  example = {
    back = "maExampleBack",
    frame = "maExampleFrame"
  },
  quote = {
    back = "maNoteBack",
    frame = "maNoteFrame"
  }
}

local function has_class(el, class_name)
  for _, class in ipairs(el.classes) do
    if class == class_name then
      return true
    end
  end

  return false
end

local function first_admonition_class(el)
  for _, class in ipairs(el.classes) do
    if admonition_kinds[class] then
      return class
    end
  end

  return nil
end

local function normalize_kind(kind)
  return canonical_kind[kind] or kind
end

local function latex_escape(text)
  -- Escape text for use inside a LaTeX macro argument.
  --
  -- The title attribute is plain text coming from Markdown attributes, not a
  -- structured Pandoc inline list. Therefore we must escape it before placing
  -- it into \begin{...}{...}{title}.
  local replacements = {
    ["\\"] = "\\textbackslash{}",
    ["{"]  = "\\{",
    ["}"]  = "\\}",
    ["$"]  = "\\$",
    ["&"]  = "\\&",
    ["#"]  = "\\#",
    ["_"]  = "\\_",
    ["%"]  = "\\%",
    ["~"]  = "\\textasciitilde{}",
    ["^"]  = "\\textasciicircum{}"
  }

  return tostring(text):gsub("[\\{}%$&#_%%~^]", replacements)
end

local function title_for(el, raw_kind, kind)
  local explicit_title = el.attributes["title"]

  if explicit_title ~= nil and explicit_title ~= "" then
    return explicit_title
  end

  return default_titles[kind] or default_titles[raw_kind] or raw_kind:gsub("^%l", string.upper)
end

local function render_tab(el)
  local title = latex_escape(el.attributes["title"] or "Tab")

  local blocks = {
    pandoc.RawBlock(
      "latex",
      "\\begin{methodatlastab}{" .. title .. "}"
    )
  }

  for _, block in ipairs(el.content) do
    table.insert(blocks, block)
  end

  table.insert(blocks, pandoc.RawBlock("latex", "\\end{methodatlastab}"))

  return blocks
end

local function render_tabset(el)
  local blocks = {
    pandoc.RawBlock("latex", "\\begin{methodatlastabset}")
  }

  for _, block in ipairs(el.content) do
    table.insert(blocks, block)
  end

  table.insert(blocks, pandoc.RawBlock("latex", "\\end{methodatlastabset}"))

  return blocks
end

function Div(el)
  if not FORMAT:match("latex") then
    return el
  end

  if has_class(el, "tabset") then
    return render_tabset(el)
  end

  if has_class(el, "tab") then
    return render_tab(el)
  end

  local raw_kind = first_admonition_class(el)

  if raw_kind == nil then
    return nil
  end

  local kind = normalize_kind(raw_kind)
  local title = latex_escape(title_for(el, raw_kind, kind))
  local color = colors[kind] or colors["note"]

  local blocks = {
    pandoc.RawBlock(
      "latex",
      "\\begin{methodatlasadmonition}"
        .. "{" .. color.back .. "}"
        .. "{" .. color.frame .. "}"
        .. "{" .. title .. "}"
    )
  }

  for _, block in ipairs(el.content) do
    table.insert(blocks, block)
  end

  table.insert(
    blocks,
    pandoc.RawBlock("latex", "\\end{methodatlasadmonition}")
  )

  return blocks
end
