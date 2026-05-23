"""Phase 0 spike — verify Misaki phonemisation runs on Android via Chaquopy.

Goal: prove (or refute) on a real device that we can phonemise English
and Japanese text using the same library the CLI uses, before
committing to the full unification refactor.

Called from Kotlin via PyObject; returns IPA phoneme strings.
"""


def phonemise_en(text: str) -> str:
    """Phonemise [text] as American English via Misaki.

    Returns the IPA phoneme string. Raises if Misaki isn't installed
    (which would be the spike failing — should never happen with
    misaki[en] pinned in build.gradle.kts).
    """
    from misaki import en  # noqa: F401 — import is the test

    # American English, no transformer (avoids spaCy's heavier transformer
    # model on first launch), no fallback (errors loud if a word OOVs).
    g2p = en.G2P(trf=False, british=False, fallback=None)
    phonemes, _tokens = g2p(text)
    return phonemes


def phonemise_ja(text: str) -> str:
    """Phonemise [text] as Japanese via Misaki.

    This is the load-bearing test: misaki[ja] transitively pulls
    pyopenjtalk + unidic. If Chaquopy can't install those on Android
    ARM, this raises ImportError and the whole spike fails — we'd
    abandon the unification path.
    """
    from misaki import ja  # noqa: F401

    g2p = ja.JAG2P()
    phonemes, _tokens = g2p(text)
    return phonemes
