# Les chantiers de Storify

> *Type : doc technique.*
> *Modèle : les règles générales de documents-markdown.md (The Human Readme).*

Ce document est l'avis d'expert rendu actionnable : le bilan des forces et des faiblesses de la lib, puis la liste priorisée de tout ce qui est à
revoir, à refaire ou à construire, à dérouler chantier par chantier. Il réalise la grille de `info.md` (bilan, micro-améliorations, vision macro),
absorbe les trois points de `TODO`, et s'appuie sur les constats du banc Storibench et des tests. Chaque chantier porte une case : cochée quand
c'est fait, avec la date.

## 1. Le bilan

### 1.1 Les forces

- **Un créneau réel et une idée nette.** Un store = une data class + un fichier + les services autour (callbacks, auto-save, validation). Dans
  l'écosystème Fabric, les libs de config font l'écran (Cloth Config, ModMenu) ou le fichier statique ; peu couvrent les données vivantes typées
  avec callbacks et validation, côté serveur.
- **Une API d'accès élégante.** Les mises à jour par référence de propriété (`set`, `mutate`, `mutateIn`), les callbacks ciblés par propriété et
  la transaction avec rollback donnent une surface expressive et sûre à consommer ; le banc l'a prouvée agréable à l'usage.
- **Une validation au-dessus du lot.** `ValidationContext` composable (imbrication, collections, chemins complets), rapport d'erreurs soigné, et
  l'enrichissement aux numéros de ligne JSON, vérifié en conditions réelles (« → line 19 » dans le crash du 2026-09-13).
- **Le deep copy CBOR.** Copier n'importe quelle data class `@Serializable` sans interface de clonage est un choix malin, mesuré par un benchmark
  dédié, avec un raccourci pour les immuables.
- **La concurrence pensée.** Read/write lock systématique, callbacks hors lock : le gros du travail est fait.
- **Un banc d'essai en conditions réelles.** Storibench exerce la lib dans un vrai serveur Fabric, avec un observatoire de callbacks : les
  constats de ce document viennent de mesures, pas d'impressions.

### 1.2 Les faiblesses

- **Le cycle de vie est inachevé** : pas de `close()`, des threads et des hooks qui survivent au store, des données ressuscitées par-dessus les
  éditions manuelles ; c'est la faiblesse la plus grave, elle a mordu au banc et jusque dans les tests.
- **La persistance n'est pas robuste** : pas d'écriture atomique, un crash pendant l'écriture tronque le fichier.
- **La validation promet plus qu'elle ne tient** : jamais rejouée après le chargement (ni update, ni reload, ni sur demande), une opération
  d'échec orpheline dans l'API, un défaut `SKIP` qui éteint la moitié de la lib en silence.
- **L'API porte les traces de son histoire** : deux factories dont une « Better », des internes exposés, un point d'extension des formats qui
  n'en est pas un.
- **L'outillage retarde** : pas de Git, une publication cassée, une licence placeholder, des tests longtemps décoratifs (remis au vert le
  2026-09-13, la généralisation reste à faire).

## 2. La lecture du tableau

- **Priorités** : P1, les fondations (fiabilité et comportements par défaut) ; P2, l'API et le ménage ; P3, la vision (ce qui fait grandir).
- **Effort** : S (une séance courte), M (une vraie séance), L (plusieurs séances ou une conception préalable).
- **Sources** : `B1` à `B4` = constats n° 1 à 4 de la section 6 du README du banc ; `CRASH` = le crash client du 2026-09-13 à 11:30 ;
  `TODO-1/2/3` = les trois points de l'ancien `Docs\TODO` ; `TESTS` = la remise au vert du 2026-09-13 ; `LECTURE` = la lecture du code.

## 3. P1, les fondations

- [ ] **C-01 : `close()` sur les stores** (M ; B3, CRASH, TESTS). Le chantier le plus important. Un store doit pouvoir mourir : annuler le tick
  d'auto-save, arrêter le scheduler, désarmer le hook d'arrêt JVM, sauvegarde finale optionnelle, appels idempotents ; `AutoCloseable` en prime.
  Sans lui : le hook zombie a réécrit `homes.json` par-dessus une édition manuelle après le crash qu'elle avait provoqué, les mondes solo empilent
  les stores, et tout test qui active l'auto-save retient la JVM (thread non-daemon). Storibench l'appellera à `SERVER_STOPPING` dès qu'il existe.
- [ ] **C-02 : l'écriture atomique** (M ; TODO-2). Écrire dans `<fichier>.tmp`, forcer l'écriture, puis remplacer par déplacement atomique.
  Couvre le crash en cours d'écriture ; aujourd'hui l'encodage écrit directement dans le flux du fichier cible. À appliquer au fichier de données
  et au sidecar meta.
- [ ] **C-03 : le défaut `SKIP` devient `SNAPSHOT`** (S ; B2, TESTS). Le défaut actuel éteint callbacks, dirty et auto-save, et contredit la KDoc
  de `StoreConfig` qui annonce déjà `SNAPSHOT`. Au passage : exposer `defaultUpdatePolicy` dans `@StoreConfiguration` (introuvable par annotation
  aujourd'hui), et ajuster le test qui documente le défaut actuel.
- [ ] **C-04 : `TomlFormat` crée les dossiers parents** (S ; B1). Deux lignes, symétrie avec `JsonFormat` ; le banc retirera son
  `createDirectories` de contournement.
- [ ] **C-05 : trancher la validation à l'update** (M/L ; B4, LECTURE, TESTS). La mécanique a disparu : `ValidationFailedOperation` n'est jamais
  émise, les démos « update bloqué » laissent tout passer. Décision à prendre : la réintroduire (valider après mutation, rollback via le snapshot
  déjà capturé en `SNAPSHOT`, émettre l'opération d'échec) ou l'abandonner et retirer l'opération orpheline. Dans les deux cas : un
  `validateNow()` public, et la revalidation optionnelle de `reloadFromFile` (aujourd'hui les valeurs invalides entrent sans un mot).
- [ ] **C-06 : le premier chargement invalide** (S/M ; TODO-1, TESTS). Deux défauts liés : le fichier initial s'écrit avant la validation (des
  défauts invalides naissent sur disque, vu avec `BadPlayerData`), et les erreurs d'origine `DEFAULT` ne sont pas enrichies des lignes alors que
  le fichier vient justement d'être écrit. Inverser l'ordre ou assumer l'écriture, et enrichir dans les deux origines.

## 4. P2, l'API et le ménage

- [ ] **C-07 : une seule factory** (M ; LECTURE). `StoreFactoryBetter` absorbe l'ancienne et reprend le nom `StoreFactory` ; les doublons
  (`createEncoder`/`createDecoder`, `ResolvedAnnotations`, la résolution d'annotations) fusionnent. Consommateurs à migrer : le banc et les tests.
  Le nom « Better » ne doit pas survivre à la stabilisation.
- [ ] **C-08 : encapsulation et visibilités** (M ; LECTURE). Les `MutableList` de callbacks sont publiques dans l'interface `Store` ; le setter
  public de `data` est un reload déguisé (il émet une `ReloadOperation`) ; `transactionInternal` est public quand ses frères sont
  `@PublishedApi internal` ; `internalCopyCbor` traîne en public. Fermer ce qui doit l'être, nommer ce qui reste.
- [ ] **C-09 : le vrai point d'extension des formats** (M/L ; LECTURE). `Utils.registerFormat` accepte un format tiers que les factories rejettent
  aussitôt (le `when` figé sur `JsonFormat`/`TomlFormat` dans les encoders). Le format doit porter lui-même son encode/decode générique ; à
  concevoir avec soin (la réification des types s'y oppose naïvement).
- [ ] **C-10 : `registerOnUpdateOn` typé** (S ; LECTURE). La signature `KProperty1<*, *>` accepte n'importe quelle propriété de n'importe quelle
  classe ; typer sur DATA ce qui peut l'être, et documenter l'égalité des références de propriétés (le mécanisme repose dessus).
- [ ] **C-11 : le logging au cordeau** (S ; LECTURE). `Store.log` est un getter qui refabrique un logger à chaque accès ; le préfixe `[Storify]`
  est présent ou absent selon les messages. Un logger par store, préfixe systématique, niveaux revus (les ticks en debug, c'est bien).
- [ ] **C-12 : l'hygiène des chemins** (S ; LECTURE). Normaliser le path à l'entrée du store (`toAbsolutePath().normalize()`) : le banc affiche
  aujourd'hui `saves\New World\.\data\...`. En profiter pour fixer la politique d'affichage des données dans les logs (troncature, types).
- [ ] **C-13 : `StoreMeta` sous-exploité** (S ; LECTURE). `version` jamais incrémentée, `touch()` jamais appelé par la lib, `custom` sans
  consommateur. Brancher ce qui sert (lien C-17), tailler ce qui ne sert pas.
- [ ] **C-14 : une vraie suite de tests** (L ; TESTS). Généraliser le geste du 2026-09-13 (assertions réelles, fichiers sous `build\tmp`,
  séparation nette démos/tests) : couvrir TOML, les transactions, la concurrence, le sidecar meta, et convertir les démos de validation en tests
  quand C-05 aura tranché ce qu'elles doivent affirmer.
- [ ] **C-15 : `git init` de la racine Gradle** (S ; LECTURE). Le standard l'exige et les chantiers ci-dessus le réclament comme filet. L'ordre de
  la doctrine : l'instantané `gradle\libs.versions.toml.avant-stack-2026-08` monte dans `_archives\` du classeur avant le `git init`, pour
  qu'aucun `git add` n'avale une copie de sauvegarde.
- [ ] **C-16 : le build au propre** (S ; LECTURE). Le `jar` embarque `from("LICENSE")`, un chemin qui n'existe pas (le fichier s'appelle
  `LICENSE.txt`) : l'inclusion échoue en silence. S'y ajoutent : les blocs shadow morts en commentaire, les commentaires pédagogiques à élaguer,
  la numérotation de sections orpheline (« 2. IDENTITÉ » sans 1), le warning `global.properties` qui pollue chaque build tant que la publication
  est en veille (lien C-18), et la licence elle-même à trancher (placeholder de 41 octets, sans nom après le copyright).

## 5. P3, la vision

- [ ] **C-17 : versionnage et migration des fichiers** (L ; TODO-3). Un fichier de config porte la version de son schéma ; au chargement, la lib
  migre ce qu'elle sait migrer et refuse le reste avec un message net. `StoreMeta.version` est un début de piste (C-13) ; la conception (où vit la
  version, qui écrit les migrations) mérite sa propre séance.
- [ ] **C-18 : la distribution Minecraft** (M/L ; LECTURE). Comment un mod embarque Storify : dépendance externe publiée, jar-in-jar, ou shading ;
  l'articulation avec fabric-language-kotlin (qui fournit stdlib et kotlinx.serialization au runtime) ; et le circuit Repsy à réparer ou à geler
  proprement. Le banc a réservé ce chantier dès sa naissance (section 1 de son README).
- [ ] **C-19 : les écrans de configuration** (L ; LECTURE). Le partage des rôles visé : l'écran édite, Storify persiste. ModMenu et Cloth Config
  attendent déjà au banc en dépendances facultatives ; c'est le volet 4 de la reprise.
- [ ] **C-20 : le positionnement** (S ; LECTURE). L'étude comparative sérieuse (Cloth Config, owo-lib, Night Config, les configs Forge/NeoForge,
  et le monde JVM hors Minecraft), vérifiée en direct le jour venu, pour dire le créneau exact de Storify et ce qui mérite d'exister ici plutôt
  qu'ailleurs.

## 6. La méthode, chantier par chantier

1. Relire le constat et poser le périmètre exact du chantier.
2. Proposer le design (aperçu du code ou du geste), valider avant d'écrire.
3. Appliquer, build et tests verts côté lib, build vert côté banc.
4. Si le comportement à l'exécution est touché : un passage au banc, en jeu, avec le log pour témoin.
5. Cocher la case ici, avec la date, et raconter au journal du classeur.

Un chantier à la fois ; un chantier qui en révèle un autre l'ajoute à la liste au lieu de s'étendre en silence.

---

*Dernière vérification : 2026-09-13, jour de l'écriture, sur le code compilé et testé de `src\main` et les constats du banc.*
