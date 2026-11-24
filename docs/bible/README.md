# ZXTune_LHUNA — manuel de référence / reference manual

Compilation (LuaLaTeX, deux passes pour la table des matières) / build (LuaLaTeX, twice for the table of contents):

    cd docs/bible && lualatex lhuna-bible && lualatex lhuna-bible          # français
    cd docs/bible/en && lualatex lhuna-bible-en && lualatex lhuna-bible-en # English

Tables générées (formats, composants, réglages) / generated tables:

    bin/linux/release/zxtune123 --list-plugins | python3 docs/bible/tools/formats.py > docs/bible/gen/formats.tex
    bin/linux/release/zxtune123 --list-plugins | python3 docs/bible/tools/formats.py --lang=en > docs/bible/en/gen/formats.tex
    python3 docs/bible/tools/settings.py apps/zxtune-android/zxtune/src/main/res > docs/bible/gen/settings.tex
    python3 docs/bible/tools/settings.py apps/zxtune-android/zxtune/src/main/res --lang=en > docs/bible/en/gen/settings.tex
    python3 docs/bible/tools/credits.py 3rdparty > docs/bible/gen/credits.tex && cp docs/bible/gen/credits.tex docs/bible/en/gen/

Les captures (images/) sont produites par les tests de rendu (variable ZXTUNE_SCREENSHOTS), à 480 dp de large.
Screenshots (images/) are produced by render tests (ZXTUNE_SCREENSHOTS), at 480 dp width.
