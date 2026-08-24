# Canonical PII corpora

These fixtures are deterministic synthetic data created specifically for Vigilant.
They contain no production payloads, telemetry, RedMadRobot records, or values
derived from user data.

Each per-type `positive` and `hard-negative` TSV contains 100 records. Positive
records cover supported representations, payload boundaries, ASCII, Cyrillic,
emoji/supplementary UTF-8 offsets, checksum variants, email punctuation/IDN
forms, IPv4/IPv6 forms, and every country in the pinned IBAN release-102
registry. `mixed.tsv` is separate scoring evidence with multiple types,
punctuation, Cyrillic, emoji, hard negatives, and a real cross-type span overlap.

All TSV files use the `# pii-corpus-v1` format fixed by EPIC-02. Regenerate them
with:

```bash
./scripts/generate-canonical-pii-corpora
```

The generator independently calculates reference checksums and explicit UTF-8
byte offsets. Any semantic change to a recognizer or corpus must update the
fixtures, focused contract test, and implementation issue in the same change.
