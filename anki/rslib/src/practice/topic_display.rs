// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! Display-only formatting for SpeedyCAT topic labels.
//!
//! Canonical `topic_tags` in JSON/DB stay lowercase or as-authored; UIs call
//! [`format_topic_display`] at render time so desktop and mobile match.

/// Known science / exam acronyms (matched case-insensitively).
const ACRONYMS: &[&str] = &[
    "MCAT", "DNA", "RNA", "mRNA", "tRNA", "rRNA", "ATP", "ADP", "NAD", "FAD", "GDP", "GTP", "PCR",
    "AAMC", "pH", "pKa", "pKb", "UV", "IR", "NMR", "HIV", "AIDS", "CNS", "PNS",
];

/// Title-case a topic label for UI display. Splits on whitespace, `-`, and `_`.
/// Words that are all-caps in the source (e.g. `DNA`) and known acronyms are preserved.
pub(crate) fn format_topic_display(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    trimmed
        .split(|c: char| c.is_whitespace() || c == '_' || c == '-')
        .filter(|w| !w.is_empty())
        .map(format_topic_word)
        .collect::<Vec<_>>()
        .join(" ")
}

fn format_topic_word(word: &str) -> String {
    if word.is_empty() {
        return String::new();
    }
    let upper = word.to_ascii_uppercase();
    for acr in ACRONYMS {
        if upper == acr.to_ascii_uppercase() {
            return acr.to_string();
        }
    }
    let mut chars = word.chars();
    match chars.next() {
        None => String::new(),
        Some(first) => {
            let mut out = String::new();
            out.extend(first.to_uppercase());
            out.extend(chars.flat_map(|c| c.to_lowercase()));
            out
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lowercase_and_snake_case() {
        assert_eq!(format_topic_display("general chemistry"), "General Chemistry");
        assert_eq!(format_topic_display("GENERAL_CHEMISTRY"), "General Chemistry");
        assert_eq!(format_topic_display("kinetics"), "Kinetics");
    }

    #[test]
    fn kebab_and_mixed() {
        assert_eq!(format_topic_display("acids-and-bases"), "Acids And Bases");
        assert_eq!(format_topic_display("acids and bases"), "Acids And Bases");
    }

    #[test]
    fn preserves_acronyms_and_title_case() {
        assert_eq!(format_topic_display("DNA"), "DNA");
        assert_eq!(format_topic_display("dna replication"), "DNA Replication");
        assert_eq!(format_topic_display("General Chemistry"), "General Chemistry");
        assert_eq!(format_topic_display("mcat prep"), "MCAT Prep");
    }

    #[test]
    fn psychology_not_treated_as_p_acronym() {
        assert_eq!(format_topic_display("psychology"), "Psychology");
        assert_eq!(format_topic_display("PSYCHOLOGY"), "Psychology");
        assert_eq!(format_topic_display("pSYCHOLOGY"), "Psychology");
        assert_eq!(format_topic_display("pHYSICS"), "Physics");
        assert_eq!(
            format_topic_display("development & social psychology"),
            "Development & Social Psychology"
        );
    }

    #[test]
    fn preserves_p_acronyms() {
        assert_eq!(format_topic_display("ph"), "pH");
        assert_eq!(format_topic_display("pka"), "pKa");
        assert_eq!(format_topic_display("pkb"), "pKb");
    }
}
