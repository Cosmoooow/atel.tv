# ateltv

Fork de [Free My TV](https://github.com/simbasjungles/free-my-tv) (licence MIT, conservée dans `LICENSE`) pour la TV
du salon : l'écran d'accueil de Free My TV, avec l'agenda et les rappels d'atel.

## Ce qui change par rapport à Free My TV

- **Nom :** tout ce qui s'affiche dit « atel » (nom de l'appli, bannière, icône, textes, réglages). Le code interne
  garde les noms de Free My TV (`io.freemytv`, `HomeView`…) pour que les mises à jour se fusionnent facilement :
  `Ui.brand` remplace « Free My TV » par `Ui.NAME` au chargement des traductions.

- **Haut de l'écran › atel** (réglage par défaut du fork) : la météo et une carte « Aujourd'hui » côte à côte, dans le
  verre de Free My TV ; seul un point ambre devant le titre signale atel. Les recommandations restent disponibles en
  choisissant « Picks » dans *Home Settings › Top of the screen*.
- Les données viennent de l'API appareils d'atel, en lecture seule : voir [API-APPAREILS.md](API-APPAREILS.md). Sans
  serveur configuré, la carte affiche un exemple (marqué « exemple »).

Le code atel est dans des fichiers à part (`Atel.java`) et quelques points d'accroche dans `HomeView.java`, pour
récupérer facilement les nouvelles versions de Free My TV :

```bash
git remote add upstream https://github.com/simbasjungles/free-my-tv.git   # une fois
git fetch upstream && git merge upstream/main
```

## Installer sur la TV

1. Télécharger `atel-tv-apk` depuis l'onglet *Actions* de GitHub (artefact de la dernière compilation, qui contient
   `atel-tv.apk`), ou compiler avec `./build.sh` (JDK 17, Android SDK build-tools 34 et platform android-34), qui
   produit `FreeMyTV.apk`.
2. Passer d'abord Free My TV en mode *Original* sur la TV, puis désinstaller la version officielle : Android refuse une
   mise à jour signée par une autre clé. Les réglages de Free My TV sont perdus.
3. `adb connect <IP>:5555`, puis `adb install -r atel-tv.apk`.
4. Sur TCL : `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   après chaque installation.
5. Sur la TV : ouvrir l'appli *atel* › *Home Screen* › *atel*.

Le fork ne se met pas à jour tout seul (la mise à jour automatique ne vise que les versions officielles).
