# ZXTune_LHUNA — manuel de référence

Compilation (LuaLaTeX, deux passes pour la table des matières) :

    cd docs/bible
    lualatex lhuna-bible
    lualatex lhuna-bible

Tables générées (formats, composants, réglages) (à relancer après changement des formats ou des composants) :

    bin/linux/release/zxtune123 --list-plugins | python3 docs/bible/tools/formats.py > docs/bible/gen/formats.tex
    python3 docs/bible/tools/credits.py 3rdparty > docs/bible/gen/credits.tex

Les parties restant à rédiger sont marquées « À RÉDIGER » (commande `\arediger`).
Les captures sont produites par les tests de rendu (variable ZXTUNE_SCREENSHOTS).
    python3 docs/bible/tools/settings.py apps/zxtune-android/zxtune/src/main/res > docs/bible/gen/settings.tex
