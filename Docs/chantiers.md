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

- [x] **C-01 : `close()` sur les stores** (M ; B3, CRASH, TESTS). Le chantier le plus important. Un store doit pouvoir mourir : annuler le tick
  d'auto-save, arrêter le scheduler, désarmer le hook d'arrêt JVM, sauvegarde finale optionnelle, appels idempotents ; `AutoCloseable` en prime.
  Sans lui : le hook zombie a réécrit `homes.json` par-dessus une édition manuelle après le crash qu'elle avait provoqué, les mondes solo empilent
  les stores, et tout test qui active l'auto-save retient la JVM (thread non-daemon). Storibench l'appellera à `SERVER_STOPPING` dès qu'il existe.
  **Fait le 2026-09-13** : `Store` étend `AutoCloseable` (`close()` idempotent : tick annulé, scheduler arrêté avec `awaitTermination`, hook
  désarmé, sauvegarde d'adieu `SaveTrigger.CLOSE` si dirty) ; un store fermé reste lisible et refuse les écritures (`IllegalStateException`) ;
  le dirty se remet à zéro dans `save()` après un encodage réussi (le tick ne le faisait qu'en avance) ; Storibench ferme à `SERVER_STOPPING` et
  referme un survivant avant réouverture ; deux tests de bout en bout (l'auto-save réel qui persiste un update `SKIP` puis `close()`, le refus
  d'écriture après fermeture).
- [x] **C-02 : l'écriture atomique** (M ; TODO-2). Écrire dans `<fichier>.tmp`, forcer l'écriture, puis remplacer par déplacement atomique.
  Couvre le crash en cours d'écriture ; aujourd'hui l'encodage écrit directement dans le flux du fichier cible. À appliquer au fichier de données
  et au sidecar meta. S'y ajoute la course notée au C-01 : `saveImmediate` et le tick peuvent encoder en même temps vers le même fichier (le
  verrou de lecture est partagé) ; des fichiers temporaires uniques suivis d'un déplacement atomique règlent aussi cette collision, le dernier
  rename gagnant un fichier entier. **Fait le 2026-09-13** : `atomicWrite` centralisé dans `BaseStore` (temporaire unique voisin,
  `FileChannel.force`, `ATOMIC_MOVE` avec repli non atomique loggué), appliqué aux données, au sidecar meta et au fichier initial ; les
  sauvegardes d'un même store sérialisées par un verrou d'IO ; balayage des orphelins à l'ouverture ; trois tests (aucun orphelin après un save,
  le balayage, la tempête de saves concurrents relue entière). Prise de guerre : le rename Windows a débusqué une fuite dormante, `JsonFormat` ne
  fermait jamais ses flux (un handle ouvert interdit le remplacement du fichier) ; corrigée au passage par des `use`, comme `TomlFormat` le
  faisait déjà.
- [x] **C-03 : la persistance découplée de la policy** (S ; B2, TESTS). Périmètre révisé le 2026-09-13 avec l'utilisateur : le marquage dirty
  (et `meta.lastModified`) quitte les callbacks pour entrer dans le pipeline d'update, toutes les policies persistent (`SKIP` compris) ;
  `@StoreConfiguration` expose `defaultUpdatePolicy` ; le défaut **reste** `SKIP` (décision utilisateur : les callbacks s'allument par policy
  explicite), et la KDoc de `StoreConfig`, qui annonçait `SNAPSHOT` à tort, est corrigée dans ce sens. **Fait le 2026-09-13** : pipeline,
  annotation, tests (le dirty sous `SKIP`, la policy par annotation), documentation alignée, constat n° 2 soldé pour la persistance. Le choix du
  défaut reste ouvert : voir C-22.
- [x] **C-04 : `TomlFormat` crée les dossiers parents** (S ; B1). Deux lignes, symétrie avec `JsonFormat` ; le banc retirera son
  `createDirectories` de contournement. **Fait le 2026-09-13** : `path.parent?.createDirectories()` dans les deux formats (`JsonFormat` gagne le
  `?.`, son `parent` nu pouvait être nul sur un chemin sans dossier), contournement du banc retiré, constat n° 1 marqué corrigé dans le README du
  banc, et un test de régression ajouté (un store TOML naît dans un dossier encore inexistant).
- [x] **C-05 : trancher la validation à l'update** (M/L ; B4, LECTURE, TESTS). La mécanique a disparu : `ValidationFailedOperation` n'est jamais
  émise, les démos « update bloqué » laissent tout passer. Décision à prendre : la réintroduire (valider après mutation, rollback via le snapshot
  déjà capturé en `SNAPSHOT`, émettre l'opération d'échec) ou l'abandonner et retirer l'opération orpheline. Dans les deux cas : un
  `validateNow()` public, et la revalidation optionnelle de `reloadFromFile` (aujourd'hui les valeurs invalides entrent sans un mot).
  **Fait le 2026-09-13, option C décidée par l'utilisateur** : la frontière par défaut (`validateNow()` public, `reloadFromFile` revalide par
  défaut avec mémoire intacte en échec), et la validation à l'update en opt-in `validateOnUpdate` (défaut `false`, documenté non recommandé,
  exige `useDeepCopy`) : rollback par copie de racine, `ValidationFailedOperation` et `TransactionOperation(success = false)` reprennent vie sous
  ce réglage. Les démos Guild et Player passent à l'opt-in avec de vraies assertions, le banc appelle `validateNow()` et son reload revalide ;
  constat n° 4 soldé. Quatre tests neufs.
- [x] **C-06 : le premier chargement invalide** (S/M ; TODO-1, TESTS). Deux défauts liés : le fichier initial s'écrit avant la validation (des
  défauts invalides naissent sur disque, vu avec `BadPlayerData`), et les erreurs d'origine `DEFAULT` ne sont pas enrichies des lignes alors que
  le fichier vient justement d'être écrit. Inverser l'ordre ou assumer l'écriture, et enrichir dans les deux origines. **Fait le 2026-09-13**, en
  compromis fidèle aux deux moitiés du TODO n° 1 : la validation passe avant toute écriture (des défauts de code invalides ne créent jamais de
  fichier, le remède est dans le code), la copie d'une ressource embarquée reste sur disque même invalide (éditable, le voeu d'origine), et
  l'enrichissement aux lignes vaut dès qu'un fichier existe, quelle que soit l'origine. Deux tests.
- [x] **C-23 : le hook d'arrêt regarde le dirty** (S ; banc du 2026-09-13, constat n° 7). Le hook JVM sauve sans condition
  (`save(SHUTDOWN)`) là où `close()` ne sauve que dirty : un store ouvert et jamais modifié réécrit son fichier à chaque extinction, et
  écrase une édition disque faite pendant la session. Aligner le hook sur `close()` (`if (isDirty)`), et un test. **Fait le 2026-09-13** :
  le corps du hook extrait en `runShutdownHook()` interne (testable sans éteindre la JVM), la garde `isDirty` posée, architecture.md aligné
  (chapitres 4 et 7), constat n° 7 soldé au banc ; un test (l'édition disque d'un store propre survit au hook, le dirty reste sauvé).

## 4. P2, l'API et le ménage

- [x] **C-07 : une seule factory** (M ; LECTURE). `StoreFactoryBetter` absorbe l'ancienne et reprend le nom `StoreFactory` ; les doublons
  (`createEncoder`/`createDecoder`, `ResolvedAnnotations`, la résolution d'annotations) fusionnent. Consommateurs à migrer : le banc et les tests.
  Le nom « Better » ne doit pas survivre à la stabilisation. **Fait le 2026-09-13** : l'ancienne supprimée (ses variantes à path explicite
  ignoraient les annotations), la refonte renommée par `git mv`, consommateurs migrés (banc, MyOwnTest, Demo) ; les démos de validation n'ont pas
  bougé, leur import `core.StoreFactory` pointe désormais la bonne implémentation.
- [x] **C-08 : encapsulation et visibilités** (M ; LECTURE). Les `MutableList` de callbacks sont publiques dans l'interface `Store` ; le setter
  public de `data` est un reload déguisé (il émet une `ReloadOperation`) ; `transactionInternal` est public quand ses frères sont
  `@PublishedApi internal` ; `internalCopyCbor` traîne en public. Fermer ce qui doit l'être, nommer ce qui reste. **Fait le 2026-09-15** :
  l'interface `Store` au régime (les quatre conteneurs de callbacks sortent du contrat, `data` passe en lecture seule) ; les conteneurs
  deviennent privés ET thread-safe dans `BaseStore` (`CopyOnWriteArrayList`, `ConcurrentHashMap` : un callback peut s'enregistrer pendant un
  dispatch, le trou du chapitre 11 d'architecture.md est fermé, un test le verrouille) ; le setter de `data` meurt avec ses deux trous relevés
  en séance (ni `checkOpen` ni `markDirty` : écriture possible après close, modification jamais auto-sauvée), remplacé par `replaceData`
  interne au service de `reloadFromFile` ; `transactionInternal`, le constructeur de `BaseStore` et `internalCopyCbor` passent
  `@PublishedApi internal` ; et `Utils` est renommé `StoreFormats`, le registre des formats sous un nom qui dit son métier. Aucun test existant
  modifié hors le renommage : les 149 restent verts, plus un nouveau (150).
- [x] **C-09 : le vrai point d'extension des formats** (M/L ; LECTURE). `Utils.registerFormat` accepte un format tiers que la factory rejette
  aussitôt (le `when` figé sur `JsonFormat`/`TomlFormat` dans les encoders). Le format doit porter lui-même son encode/decode générique ; à
  concevoir avec soin (la réification des types s'y oppose naïvement). **Fait le 2026-09-13** : `StoreFormat` porte le contrat (`fileExtension`,
  `decodeFromPath(deserializer, path)`, `encodeToPath(serializer, data, path)`), la factory matérialise `serializer<DATA>()` à son site réifié
  et le store appelle le format en polymorphe : le `when` et les extensions `createEncoder`/`createDecoder` sont morts, tout `StoreFormat`
  traverse la factory (un sucre `inline reified` garde l'ergonomie réifiée aux sites d'appel : la réification matérialise, le virtuel
  transporte). Décisions liées : le paramètre de type inutile de `StoreFormat<T>` supprimé ; le sidecar meta toujours JSON, comme son nom le
  promet ; une extension inconnue refusée net au lieu du repli silencieux sur JSON ; `atomicWrite` garantit les dossiers parents pour tout
  format (la leçon C-04 généralisée). Quatre tests neufs (`CustomFormatTest`) : l'aller-retour d'un format tiers par le registre et en
  explicite, le refus d'extension inconnue, le sidecar JSON d'un store TOML.
- [x] **C-10 : `registerOnUpdateOn` typé** (S ; LECTURE). La signature `KProperty1<*, *>` accepte n'importe quelle propriété de n'importe quelle
  classe ; typer sur DATA ce qui peut l'être, et documenter l'égalité des références de propriétés (le mécanisme repose dessus).
  **Fait le 2026-09-15**, en mieux que prévu (S devenu M, design co-construit en séance) : `registerOnUpdateOn` typé racine
  (`KProperty1<DATA, *>`, l'erreur de store meurt à la compilation), et la vraie trouvaille, `registerOnUpdateOnIn`, le miroir de `setIn` : la
  navigation ancre la propriété imbriquée au store à la compilation ET désigne l'instance, réévaluée à chaque notification et comparée par
  identité (l'écouteur survit aux reloads, une navigation qui échoue vaut silence, une instance fabriquée ne matche jamais). Le ciblage
  d'instance est une capacité neuve : deux jumelles de la même classe s'écoutent séparément. Les policies restent arborescentes, gardées par
  l'arbre réel (`belongsToDataTree`, collecté par le scan : `setUpdatePolicy` signale l'étranger au log). L'égalité des références est
  documentée dans la KDoc du contrat. Quatre tests neufs ; l'option interface marqueur (`StorePart<in DATA>`) étudiée puis écartée au profit
  de la navigation, plus précise et sans marquage des data classes.
- [x] **C-11 : le logging au cordeau** (S ; LECTURE). `Store.log` est un getter qui refabrique un logger à chaque accès ; le préfixe `[Storify]`
  est présent ou absent selon les messages. Un logger par store, préfixe systématique, niveaux revus (les ticks en debug, c'est bien).
  **Fait le 2026-09-15** : le logger sort du contrat `Store` (une pollution d'API dans l'angle mort de C-08 ; personne dehors ne l'utilisait,
  vérifié au grep) et devient un champ privé de `BaseStore`, fabriqué une fois au lieu d'une recherche par accès. La tournée des messages :
  le préfixe `[Storify]` était en fait déjà systématique, posé au fil de l'eau par les chantiers depuis C-01 (vérifié au grep), et les niveaux
  sont confirmés : ticks en debug, cycle de vie en info, échecs en warn.
- [x] **C-12 : l'hygiène des chemins** (S ; LECTURE). Normaliser le path à l'entrée du store (`toAbsolutePath().normalize()`) : le banc affiche
  aujourd'hui `saves\New World\.\data\...`. En profiter pour fixer la politique d'affichage des données dans les logs (troncature, types).
  **Fait le 2026-09-15** : le chemin est normalisé à la construction de `BaseStore`, l'unique point d'entrée : logs, messages d'erreur, sidecar
  et temporaires atomiques en héritent tous, le `\.\` du banc est mort ; un test l'épingle. Le second volet se règle par un constat : la lib ne
  journalise aucune donnée utilisateur (des chemins et des états seulement, vérifié sur la flotte des messages) ; la politique est désormais
  écrite dans architecture.md, rien à tronquer.
- [x] **C-13 : `StoreMeta` sous-exploité** (S ; LECTURE). `version` jamais incrémentée, `touch()` jamais appelé par la lib, `custom` sans
  consommateur. Brancher ce qui sert (lien C-17), tailler ce qui ne sert pas. **Fait le 2026-09-15** : `touch()` branché, `markDirty` l'appelle
  au lieu de dupliquer sa ligne à la main (BaseStore perd deux imports au passage) ; `version` conservée et documentée « réservée au
  versionnage de schéma, C-17 » (la retirer casserait le schéma du sidecar pour la recréer ensuite) ; `custom` a trouvé ses consommateurs en
  route (le banc l'affiche en jeu, la suite l'exerce), conservé et documenté. Le vrai destin de `version` appartient à C-17.
- [x] **C-14 : une vraie suite de tests** (L ; TESTS). Généraliser le geste du 2026-09-13 (assertions réelles, fichiers sous `build\tmp`,
  séparation nette démos/tests) : couvrir TOML, les transactions, la concurrence, le sidecar meta, et convertir les démos de validation en tests
  quand C-05 aura tranché ce qu'elles doivent affirmer. **Fait le 2026-09-14**, en six tranches committées sur la branche `c14-tests` : la suite
  thématique (`support` et son zoo de fixtures, `factory`, `updates`, `persistence`, `lifecycle`, `validation`, `formats`, `meta`, `utils`),
  121 tests neufs, 149 verts au total ; les fixtures vivent dans `build\tmp` (plus jamais `C:\temp`), les démos de validation sont converties
  puis supprimées, la visite guidée vit dans `demos\`, le benchmark dans `bench\`, et l'ancien parc reste gelé dans `legacy\` (conservé sur
  décision). Règle de la suite : aucun appui sur les surfaces que C-08 fermera, tout passe par `register*`, `reloadFromFile` et `transaction`.
  Prise de guerre : le désaccord `withValidation` entre annotation et config, épinglé par `ResolutionTest` et ouvert en C-24.
- [x] **C-15 : `git init` de la racine Gradle** (S ; LECTURE). Le standard l'exige et les chantiers ci-dessus le réclament comme filet. L'ordre de
  la doctrine : l'instantané `gradle\libs.versions.toml.avant-stack-2026-08` monte dans `_archives\` du classeur avant le `git init`, pour
  qu'aucun `git add` n'avale une copie de sauvegarde. **Fait le 2026-09-13** : deux instantanés montés dans `_archives\2026-09-13\` (le second,
  `gradle-wrapper.properties.avant-9.4.1-bin`, découvert au passage), le dépôt de la lib né en `bcd5af0` (66 fichiers), celui du banc en `9bc908c`
  (20 fichiers), branche `master` des deux côtés.
- [x] **C-16 : le build au propre** (S ; LECTURE). Le `jar` embarque `from("LICENSE")`, un chemin qui n'existe pas (le fichier s'appelle
  `LICENSE.txt`) : l'inclusion échoue en silence. S'y ajoutent : les blocs shadow morts en commentaire, les commentaires pédagogiques à élaguer,
  la numérotation de sections orpheline (« 2. IDENTITÉ » sans 1), le warning `global.properties` qui pollue chaque build tant que la publication
  est en veille (lien C-18), et la licence elle-même à trancher (placeholder de 41 octets, sans nom après le copyright). **Fait le 2026-09-13** :
  `LICENSE.txt` renommé en `LICENSE` par `git mv` (la convention, et le `from("LICENSE")` réparé du même geste ; la licence entre désormais dans
  le jar, vérifié `LICENSE_storify`), sa mention de copyright complétée (`Copyright (c) 2025-2026 Roumoulou`, tous droits réservés ; le choix
  d'une licence réelle reste reporté à froid). `build.gradle.kts` nettoyé : code mort retiré (plugin shadow, bloc `shadowJar`,
  `dependsOn(shadowJar)`, fragments commentés), commentaires pédagogiques réécrits au registre neutre, numérotation orpheline corrigée, et le
  warning `global.properties` éteint en ne configurant le dépôt Repsy que si le fichier existe (gel propre ; le sort de Repsy reste à C-18).
  Builds lib et banc verts.
- [x] **C-24 : accorder `withValidation` entre l'annotation et la config** (S ; C-14). Le défaut de `@StoreConfiguration` est `true` quand celui
  de `StoreConfig()` est `false` : une classe annotée sans argument valide au chargement, une classe nue ne valide pas. Découvert en écrivant la
  suite, épinglé dans les deux sens par `ResolutionTest` ; trancher un défaut unique à froid, puis aligner KDoc et tests. **Fait le 2026-09-15,
  décision utilisateur : `true` partout.** Sans validator, la validation ne coûte rien ; poser un validator, c'est vouloir qu'il tourne, et
  `withValidation = false` devient l'échappatoire explicite. `StoreConfig()` passe à `true`, l'annotation y était déjà ; le test du désaccord de
  `ResolutionTest` devient le test de l'accord ; KDoc et docs alignées.
- [x] **C-25 : court-circuiter la capture sans auditeur** (S ; C-22). Le pipeline d'update construit captures et opération même quand aucun
  callback d'update n'est enregistré : sous une policy observante, des copies profondes partent sans public. Court-circuiter la construction
  quand `onUpdateCallbacks` et la liste ciblée de la propriété sont vides ; fait relevé en tranchant C-22. **Fait le 2026-09-15** : la garde
  `hasUpdateListeners` en tête du pipeline route l'update sans public vers le chemin rapide de SKIP (mutation, dirty, rien de construit),
  quelle que soit la policy ; la transaction ne copie plus son après sans public, son secours de rollback restant toujours pris ; le garde
  `validateOnUpdate` est intact dans la branche rapide (elle validait et restaurait déjà). La KDoc périmée du pipeline (le vieux « TODO refaire
  cette docs ») est réécrite au passage. Quatre tests au compteur de sérialisations : la preuve mesurée que plus rien ne se copie sans
  auditeur. Note au procès-verbal : l'argument « des copies sans public » de la décision C-22 tombe avec ce chantier ; la décision reste
  debout sur son premier pilier, le store type est une config que personne n'observe.
- [ ] **C-26 : la sauvegarde JSON5 préservant les commentaires** (M ; C-21). La brique offre un éditeur chirurgical du source
  (`parseToDocument`, puis `set`, `putProperty`, `remove` par plages exactes, commentaires attachés aux nœuds) : au save, réconcilier l'arbre
  encodé avec le document parsé et n'appliquer que les différences, pour que les commentaires et le style de l'admin survivent aux sauvegardes.
  À concevoir : le diff récursif, la stratégie des tableaux (le point dur), les replis (fichier absent ou invalide : encode à neuf). Se greffe
  dans `encodeToPath` sans toucher au contrat C-09.

## 5. P3, la vision

- [ ] **C-17 : versionnage et migration des fichiers** (L ; TODO-3). Un fichier de config porte la version de son schéma ; au chargement, la lib
  migre ce qu'elle sait migrer et refuse le reste avec un message net. `StoreMeta.version` est un début de piste (C-13) ; la conception (où vit la
  version, qui écrit les migrations) mérite sa propre séance.
- [ ] **C-18 : la distribution Minecraft** (M/L ; LECTURE). Comment un mod embarque Storify : dépendance externe publiée, jar-in-jar, ou shading ;
  l'articulation avec fabric-language-kotlin (qui fournit stdlib et kotlinx.serialization au runtime) ; et la publication sur Repsy à mettre en
  place (décidée le 2026-09-13) : circuit `maven-publish` remis en état, identifiants par la chaîne de secrets (BWS, `secrets-et-acces.md` de
  The Human Readme), jamais en clair ; la déprécation Gradle 10 vue dans le build s'élucidera ici si elle vient de maven-publish. Le banc a
  réservé ce chantier dès sa naissance (section 1 de son README).
- [ ] **C-19 : les écrans de configuration** (L ; LECTURE). Le partage des rôles visé : l'écran édite, Storify persiste. ModMenu et Cloth Config
  attendent déjà au banc en dépendances facultatives ; c'est le volet 4 de la reprise.
- [ ] **C-20 : le positionnement** (S ; LECTURE). L'étude comparative sérieuse (Cloth Config, owo-lib, Night Config, les configs Forge/NeoForge,
  et le monde JVM hors Minecraft), vérifiée en direct le jour venu, pour dire le créneau exact de Storify et ce qui mérite d'exister ici plutôt
  qu'ailleurs.
- [x] **C-21 : le support JSON5** (M ; demande du 2026-09-13). Le format taillé pour les configs éditées à la main : commentaires, virgules
  traînantes, clés sans guillemets. La brique existe et se marie à notre pile : `li.songe:json5` (github.com/lisonge/kotlin-json5),
  multiplateforme, bâtie pour kotlinx.serialization, vérifiée sur Maven Central le 2026-09-13 (0.8.0). Dépendait de C-09, fait le 2026-09-13 :
  la voie est libre, un `Json5Format` traverse désormais la factory ; cette envie est l'argument qui avait fait monter C-09 dans la file.
  **Fait le 2026-09-15** : `Json5Format` par le pont `JsonElement`, la conception de la brique elle-même (kotlinx en moteur d'arbre, le texte
  100 % JSON5), au sérialiseur explicite conforme C-09 ; sortie idiomatique (indentée, apostrophes, clés nues) ; `json5` au registre par défaut
  et `JSON5` dans `@StoreFileFormat` ; dépendance épinglée 0.8.0, revérifiée à la source (metadata de repo1, seize versions, dernière du
  2026-09-08). Sept tests : l'aller-retour, le confort JSON5 décodé, la sortie qui se relit, la résolution par registre et par annotation, le
  store de bout en bout, et la limite épinglée : les commentaires meurent au save (documentée au README) ; leur sauvegarde préservante est
  ouverte en C-26, au design éclairé par l'éditeur découvert dans les sources de la brique.
- [x] **C-22 : revoir le défaut de policy** (S ; décision reportée du 2026-09-13). `SKIP` par défaut est assumé aujourd'hui (silence des
  callbacks, persistance garantie depuis C-03) ; reste à trancher à froid entre `SKIP`, `SHALLOW` (callbacks gratuits, avant dégradé sur les
  mutations en place) et `SNAPSHOT` (captures figées, coût mesuré par `DeepCopyBenchmark`), guidance du chapitre 6 d'`architecture.md` à l'appui.
  **Fait le 2026-09-15, décision utilisateur : `SKIP` reste le défaut.** Les raisons : le store type est une config que personne n'observe (le
  défaut ne doit rien coûter), et le pipeline construit ses captures même sans auditeur, un `SNAPSHOT` par défaut facturerait des copies sans
  public (fait consigné en C-25). En garde-fou, `registerOnUpdateOn` avertit quand la policy effective de la propriété est `SKIP`, et
  `registerOnUpdate` quand le store entier est voué au silence (défaut `SKIP` et aucune policy posée) : le silence qui prévient n'est plus un
  piège. Aucun test modifié, le silence sous `SKIP` restant épinglé par la suite.

## 6. La méthode, chantier par chantier

1. Relire le constat et poser le périmètre exact du chantier.
2. Proposer le design (aperçu du code ou du geste), valider avant d'écrire.
3. Appliquer, build et tests verts côté lib, build vert côté banc.
4. Si le comportement à l'exécution est touché : un passage au banc, en jeu, avec le log pour témoin.
5. Cocher la case ici, avec la date, et raconter au journal du classeur.

Un chantier à la fois ; un chantier qui en révèle un autre l'ajoute à la liste au lieu de s'étendre en silence.

---

*Dernière vérification : 2026-09-13, jour de l'écriture, sur le code compilé et testé de `src\main` et les constats du banc.*
