# AI Accuracy Evaluation

MethodAtlas constrains the AI to a closed, versioned taxonomy, so every answer is *well-formed*. That does not make it *correct*. `-eval-accuracy` measures correctness: it scores the AI's classifications against a set of human-reviewed labels and reports precision, recall, F1 and support.

The command is offline and deterministic. It reads two files and calls no AI provider.

## Ground truth: the override file

The recommended ground-truth source is the same YAML you already maintain for [classification overrides](overrides.md). Each entry is a decision a human has reviewed, so it is already a vetted label — there is no separate labelling format to learn or keep in sync.

| Override entry | Label it provides |
| --- | --- |
| `securityRelevant: true` / `false` | the relevance label |
| `tags: [...]` | the tag label |
| `securityRelevant: false` without `tags` | relevance *and* "no security tag applies" |
| `securityRelevant: true` without `tags` | relevance only; tags are not scored for that method |
| only `note`, `reason`, `displayName` | not a label; ignored |

Resolution follows the override rules: a method-level entry wins over a class-level entry for the same class, and the last duplicate wins. Tag names are trimmed and lower-cased before comparison.

!!! warning "Predictions must be genuine AI output"
    Produce the predictions CSV **without** `-override-file`. Overrides replace the AI values, so scoring a scan that had them applied compares the human labels with themselves and reports a meaningless 100 %.

## Workflow

```bash
# 1. Scan with AI, no overrides applied.
methodatlas -ai -ai-provider ollama src/test/java > predictions.csv

# 2. Score the predictions against the reviewed labels.
methodatlas -eval-accuracy overrides.yaml predictions.csv
```

| Argument | Meaning | Default |
| --- | --- | --- |
| `-eval-accuracy <labels.yaml> <predictions.csv>` | Run the evaluation and exit. All other scan flags are ignored | — |
| `-eval-format markdown\|json` | Report format | `markdown` |
| `-eval-min-f1 <0..1>` | Exit `1` when the `securityRelevant` F1 or the micro-averaged tag F1 is below the threshold, or when neither can be computed. The macro-average is reported but is **not** what the gate uses | no gate |

## Metrics

Two decisions are evaluated, each only over the methods whose label decides it.

**`securityRelevant`** is a binary decision, reported as a confusion matrix where *positive* means security-relevant.

|  | Truth: relevant | Truth: not relevant |
| --- | --- | --- |
| **Predicted: relevant** | TP | FP |
| **Predicted: not relevant** | FN | TN |

**Taxonomy tags** are a multi-label decision, scored one-vs-rest for every tag: a tag is a TP when predicted and labelled, an FP when predicted only, an FN when labelled only. The report adds a micro-average (counts summed over tags) and a macro-average F1: the unweighted mean of the per-tag F1 over **every tag that appears in any prediction or any label** (the scikit-learn convention). A tag the AI invented has zero support and F1 0, so hallucinated tags lower the macro average instead of being silently dropped.

| Metric | Definition |
| --- | --- |
| Precision | `TP / (TP + FP)` |
| Recall | `TP / (TP + FN)` |
| F1 | `2·TP / (2·TP + FP + FN)` |
| Accuracy (relevance only) | `(TP + TN) / total` |
| Support | `TP + FN` — the number of labelled positives |

A metric whose denominator is zero is **undefined** and is shown as `n/a` (JSON `null`), never as 0 or 1: "no evidence" must not read as "perfect" or "terrible". A tag the AI predicted but no label contains has zero support and an undefined recall (its F1 is 0 and it counts in the macro average).

### Coverage counters

Missing and extra entries never fail the run. They are counted so you can judge how representative the numbers are:

| Counter | Meaning |
| --- | --- |
| Prediction rows read | rows in the predictions CSV |
| Evaluated | methods that were both labelled and predicted |
| Predictions without a label | AI predictions with no usable label (not scored) |
| Labelled methods without an AI prediction | the row has no `ai_security_relevant` value, for example the scan ran without `-ai` |
| Label entries matching no scanned method | stale entries after a rename or deletion, or a different scan scope |

## Try it

A tiny demonstrator ships in `docs/examples/accuracy/`:

```bash
methodatlas -eval-accuracy docs/examples/accuracy/labels.yaml docs/examples/accuracy/predictions.csv
```

It scores seven predicted methods against six labels (one of them stale): the relevance matrix is TP 3, FP 1, FN 1, TN 1 (F1 0.7500), and the tag micro-average F1 is 0.6250 (macro-average 0.4167: the two invented tags `logging` and `input-validation` count as F1 0). The same files back the automated tests, so the numbers above are asserted in CI.

## Output determinism

The report has no timestamp, path or host field. Lines end with `\n` on every operating system, numbers use four decimals with a `.` separator regardless of the JVM locale, and tags are sorted. Two runs over the same inputs are byte-identical on Linux, Windows and macOS, so a report can be committed or hashed into an evidence pack.

JSON reports carry `"schemaVersion": 1`; field renames or retyping will bump it.

## What the numbers mean — and what they do not

!!! note "Ongoing accuracy requires a maintained labelled set"
    The command is a measuring instrument; the figure it reports is only as good as the labels behind it. A one-off run on a handful of methods says little. To track accuracy over time, keep a labelled set that is large enough to give every tag meaningful support, representative of the code you scan, and re-scored whenever the model, the prompt templates or the taxonomy change (the [reproducibility receipt](../usage-modes/reproducibility-receipts.md) records the inputs that influenced a scan).

Choosing the acceptable precision and recall — and therefore the `-eval-min-f1` value for CI — is a risk-owner decision. MethodAtlas ships no default target.

The bias of an override-derived label set can run **in both directions**. Reviewed methods are often the ones the AI got wrong, so overrides over-represent errors and understate accuracy. But a reviewer who accepts AI suggestions in bulk (for example a GUI session that saves every suggestion unchanged) produces labels that merely echo the AI, which overstates accuracy. Where an unbiased estimate matters, add labels for a random sample of methods the reviewer *agreed with*, recorded as override entries that restate the decision, and label a random sample of methods the AI was never asked to flag.

!!! warning "A GUI-generated `overrides.yaml` is not an independent label set"
    The GUI writes `.methodatlas/overrides.yaml` from the reviewer's *saved* decisions, which start from the AI suggestion. Unless the reviewer authored or consciously confirmed each entry, scoring the AI against that file measures agreement with itself, not correctness. Use it as ground truth only when the entries were independently authored by the reviewer.

## See also

- [Classification Overrides](overrides.md) — the label format
- [Security Taxonomy](taxonomy.md) — the closed tag set being scored
- [Confidence Scoring](confidence.md)
