# Changelog

Les versions publiées de Storify, de la plus récente à la plus ancienne. Avant `1.0.0`, le mineur monte à chaque ajout ou rupture, le correctif pour
une correction seule ; chaque version est un tag `v<version>` du dépôt et une release GitHub qui porte ses jars.

## 0.10.0

Le 2026-10-23 : `fr.moulou:storify:0.10.0` sur Repsy, en deux variantes sous la même coordonnée, Java 11 et Java 25 ; le code au cordeau, aucune API ne bouge.

- **Le code au cordeau** (C-52) : chaque classe de `src\main`, de `src\test`, de l'essai de fumée et du banc suit l'ordre de déclaration de
  `style-de-code.md` (The Human Readme), le companion en tête, les propriétés du public au privé, `init`, les méthodes par thème et par enchaînement,
  l'appelé sous l'appelant, les types imbriqués en dernier, sans bannière de section ; un consommateur qui lit les sources publiées lit cet ordre-là.
  Aucune ligne de logique ne change : le diff trié de chaque fichier est vide, la suite (371 tests), `checkBytecode`, l'essai de fumée Java 11 et le
  banc en composite, gametests compris, sont verts. Aucun changement d'API, aucune rupture : un mod compilé contre la `0.9.0` reste compatible.

## 0.9.0

Le 2026-10-22 : `fr.moulou:storify:0.9.0` sur Repsy, en deux variantes sous la même coordonnée, Java 11 et Java 25 ; les commentaires depuis le code.

- **Les commentaires depuis le code** (C-64) : `@StoreComment(vararg lines)` sur une propriété, ou sur la classe racine pour l'en-tête du fichier ;
  en JSON5, un `//` par ligne au-dessus de la clé, écrit quand le fichier naît et quand une clé s'ajoute ; `commentPolicy` dans `StoreConfig` et
  `@StoreConfiguration`, `FILE_WINS` par défaut (l'admin gagne, C-26) ou `CODE_WINS` (le code remet ses commentaires à chaque sauvegarde). JSON
  l'ignore ; TOML écrit `@TomlComment` de tomlkt. Rupture : `StoreConfig` gagne un champ, un mod compilé contre la `0.8.0` se recompile.
- **La réconciliation JSON5 indente juste** (C-69) : une clé neuve à valeur objet et un tableau remplacé entier suivent l'indentation de leurs
  voisines, le bloc compris ; avant, le bloc inséré partait de la colonne 0 et la clé prenait deux espaces.

## 0.8.0

Le 2026-10-22 : `fr.moulou:storify:0.8.0` sur Repsy, en deux variantes sous la même coordonnée, Java 11 et Java 25 ; Java 8 n'est plus servi.

- **La variante Java 11 remplace la variante Java 8** (C-68) : `storify-0.8.0-jdk11.jar`, bytecode 55, compilée avec `-Xjdk-release=11` ; la
  variante Java 25, `storify-0.8.0.jar`, reste la variante par défaut et celle du POM. Un consommateur qui vise Java 11 à 24 reçoit la 11, Java 25
  la 25, Maven la 25. Rupture : un consommateur en cible 8 à 10 ne trouve plus de variante, Gradle refuse ; un mod pour Minecraft 1.16.5 vise
  Java 11 et lance le jeu sur Java 11. Le classifier passe de `jdk8` à `jdk11`, et l'attribut à poser sur `includeInternal` est la cible du mod,
  11 à 21 (README, section 4).
- **Les preuves suivent** : `checkBytecode` lit 69 et 55, et l'essai de fumée tourne sur un JDK 11 (`smokeJdk11Run`).
- La copie de kotlin-json5 reste (un jar de moins pour un mod, un paquet relocalisé) ; le code est celui de la `0.7.0`, aucune API ne bouge.

## 0.7.0

Première release, le 2026-10-19 : `fr.moulou:storify:0.7.0` sur Repsy, en deux variantes sous la même coordonnée, Java 8 et Java 25.

- **Deux variantes, compilées des mêmes sources** (C-65) : `storify-0.7.0.jar` en bytecode Java 25, la variante par défaut et celle du POM, et
  `storify-0.7.0-jdk8.jar` en bytecode Java 8, compilée avec `-Xjdk-release=1.8`. Les métadonnées Gradle publient les deux avec leur
  `org.gradle.jvm.version` : un consommateur qui vise Java 8 à 24 reçoit la variante 8, un consommateur qui vise 25 la variante 25, Maven la 25.
  L'`include` de Loom ne demande pas de cible : un mod sous Java 25 pose `org.gradle.jvm.version` sur `includeInternal` (README, section 4).
- **kotlin-json5 0.8.0 copiée dans Storify**, sous `fr.moulou.storify.internal.json5`, Apache-2.0 (`LICENSE.json5`, `NOTICE`), relocalisée et
  `internal` sauf `Json5EncoderConfig` et `Json5QuoteStrategy` : le POM ne déclare plus `li.songe:json5`, et un mod n'embarque plus que storify
  et tomlkt. Rupture : `Json5EncoderConfig` change de paquet, `li.songe.json5` devenant `fr.moulou.storify.internal.json5`.
- **`Utf8Bom`** lit le BOM par une boucle sur `read` : la seule API de Java 9 que le code appelait disparaît.
- **Deux preuves à chaque build** : `checkBytecode` (69 et 52, chaque classe) et `smokeJdk8Run`, un essai de fumée compilé contre le jar Java 8 et
  lancé sur un JDK 8.
- Les versions : Gradle 9.8.1, Kotlin 2.4.21 ; au runtime, fabric-language-kotlin 1.14.2 fournit le reste, et Minecraft 1.16.5, sans slf4j, le
  reçoit du mod.

## Avant la 0.7.0

Des snapshots, publiés sur Repsy sans release : `0.1.0-SNAPSHOT` (septembre 2026, la première publication, C-18) ; `0.2.0-SNAPSHOT` (2026-09-29 : la
licence LGPL-3.0-only, la copie profonde par l'arbre JSON, le mode lecture seule, le BOM toléré, les lignes de validation, `StoreDecodeException`,
l'écrivain atomique public, le JSON strict, le logger nommé) ; `0.3.0-SNAPSHOT` (2026-09-30 : la ressource embarquée copiée telle quelle,
l'écriture JSON tamponnée, le pipeline d'update hors de l'inline) ; `0.4.0-SNAPSHOT` (2026-10-01, republiée le 2026-10-05 : les clés en double
refusées, le localisateur borné à son parent) ; `0.5.0-SNAPSHOT` (2026-10-07 : `createFromProvider`, la ligne d'une faute sans offset) ;
`0.6.0-SNAPSHOT` (2026-10-08 : le chemin d'une clé en double, `ErrorPath.render`). Le détail, chantier par chantier, vit dans
`docs\technical-docs\chantiers.md`.
