package app.marmalade.tts.preprocessing

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   PreprocessingRules.ALL ──► PreprocessingRule
//                                 │ name        — stable storage key (DataStore)
//                                 │ description — Settings ListItem text
//                                 │ transform   — String -> String (pure)
//
//   Preprocessor.apply(text, enabledRules)
//      └── for r in PreprocessingRules.ALL:
//              if r.name in enabledRules: text = r.transform(text)
// -----------------------------------------------------------------------------

/**
 * A single named text-preprocessing transformation.
 *
 * Stable [name] is what we persist (per-engine enabled-rule set in DataStore)
 * and what the CLI uses for the same rule — keeping these in lock-step means a
 * user moving between platforms sees the same toggle keys.
 *
 * [description] is shown in the Settings UI as the supporting text of the
 * per-rule switch. Keep it concise (one short sentence with a tiny example
 * where it helps).
 *
 * [transform] is a pure function `String -> String`. Implementations should
 * never throw on normal input — if a rule can't safely handle a value, return
 * the input unchanged.
 */
data class PreprocessingRule(
    val name: String,
    val description: String,
    val transform: (String) -> String,
)
