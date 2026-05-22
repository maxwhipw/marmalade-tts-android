package app.marmalade.tts.preprocessing

import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Caller (synth path)
//     │
//     │  raw text
//     ▼
//   Preprocessor.apply(text, enabledRules)
//     │
//     ├── for each rule in PreprocessingRules.ALL (CLI priority order):
//     │       if rule.name in enabledRules: text = rule.transform(text)
//     │
//     ├── collapse whitespace runs to single spaces
//     │
//     └── trim leading/trailing whitespace
//                │
//                ▼
//           normalized text  →  engine.synthesize(text, ...)
//
//   Notes:
//     - enabledRules order is irrelevant; ALL is the source of truth
//       for application order (so toggling rules in any order in
//       Settings can never break the pipeline).
//     - Whitespace collapse is unconditional; matches the CLI's final
//       `re.sub(r"\s+", " ", text).strip()` in preprocess().
// -----------------------------------------------------------------------------

/**
 * Applies a configured subset of [PreprocessingRules] to a string.
 *
 * Wraps the rule catalog as an injectable singleton so the synth call
 * sites can take it through Hilt. Stateless — the only field is the
 * rules-by-name lookup, populated once at construction time.
 *
 * Hilt provides this via [app.marmalade.tts.di.AppModule.providePreprocessor].
 * The constructor is also public so JVM unit tests can build one
 * directly without going through Hilt.
 */
@Singleton
class Preprocessor @Inject constructor(
    private val rulesByName: Map<String, PreprocessingRule>,
) {

    /**
     * Run every rule in [enabledRules] against [text] in
     * [PreprocessingRules.ALL]'s catalog order, then collapse the
     * whitespace runs the rules may have left behind and trim ends.
     *
     * Rules referenced by name that aren't in the catalog are silently
     * ignored — keeps DataStore-persisted sets forward-compatible when
     * we add a rule and a previously-stored set predates it.
     *
     * @return the normalized text, ready to feed the engine.
     */
    fun apply(text: String, enabledRules: Set<String>): String {
        var out = text
        for (rule in PreprocessingRules.ALL) {
            if (rule.name in enabledRules) {
                out = rule.transform(out)
            }
        }
        // Final whitespace collapse — matches the CLI's final pass.
        return out.replace(Regex("\\s+"), " ").trim()
    }
}
