# Les chantiers de Storify

> *Type : doc technique.*
> *Modèle : les règles générales de documents-markdown.md (The Human Readme).*

Vaut pour la lib : le bilan de ses forces et de ses faiblesses, puis tout ce qui est à revoir, à refaire ou à construire, chantier par chantier.
Chaque chantier porte une case, cochée quand c'est fait, avec la date.

## 1. Le bilan

### 1.1 Les forces

- **Un créneau réel et une idée nette.** Un store = une data class + un fichier + les services autour (callbacks, auto-save, validation). Dans
  l'écosystème Fabric, les libs de config font l'écran (Cloth Config, ModMenu) ou le fichier statique ; peu couvrent les données vivantes typées
  avec callbacks et validation, côté serveur.
- **Une API d'accès élégante, et légère chez le consommateur.** Les mises à jour par référence de propriété (`set`, `mutate`, `mutateIn`), les
  callbacks ciblés par propriété ou par instance imbriquée, la transaction avec rollback : le banc l'a prouvée agréable à l'usage, et le jar d'un
  mod n'embarque que l'appel aux points d'entrée, ni le pipeline d'update ni la factory (C-45, C-46).
- **Une validation au-dessus du lot.** `ValidationContext` composable (imbrication, collections, maps, chemins complets), les erreurs pointées à
  la ligne du fichier en JSON et JSON5, la validation d'un fichier sans store, et une famille d'exceptions (`StorifyException`) qui couvre d'un
  seul `catch` le fichier illisible et le fichier invalide.
- **La copie profonde par l'arbre JSON.** Copier n'importe quelle data class `@Serializable` sans interface de clonage, sérialiseurs écrits pour
  le JSON compris, au coût mesuré par `DeepCopyBenchmark`, et aucune copie sans public ni sans `useDeepCopy`.
- **Une persistance robuste.** L'écriture atomique partout, la règle dirty (rien ne s'écrit sans modification en mémoire, au tick, à la fermeture
  comme au hook d'arrêt), `close()`, le mode lecture seule, le fichier qui gagne au rechargement, et la sauvegarde JSON5 qui préserve les
  commentaires de l'admin.
- **La concurrence pensée et mesurée.** Read/write lock systématique, callbacks hors lock, conteneurs thread-safe, et le coût d'une sauvegarde
  sous verrou mesuré par une démo.
- **Une suite de tests réelle, et des démos.** Une suite thématique avec ses fixtures, des garde-fous qui lisent le bytecode d'un appelant, et
  une démo par mécanisme, qui imprime ce que la lib fait avant et après.
- **Un banc d'essai en conditions réelles.** Storibench exerce la lib dans un vrai serveur Fabric, avec un observatoire de callbacks : les
  constats de ce document viennent de mesures, pas d'impressions.

### 1.2 Les faiblesses

- **Pas de versionnage de schéma** : un fichier écrit par la data class d'hier se charge tel quel ou échoue, sans migration ni message net
  (C-17, dont la conception attend sa séance).
- **Une API non stabilisée, sans release figée** : un champ ajouté à `StoreConfig` change un constructeur auquel un mod compilé est lié, et le
  POM déclare en `runtime` des dépendances dont les types traversent l'API ; les deux se règlent avec la première release figée.
- **Les trois formats toujours embarqués** : un mod JSON seul emporte tomlkt et json5 (C-38, en attente).
- **Le banc ne se vérifie en jeu que côté serveur** : ses étages 0 et 1 sont vides, et le cycle solo du client, un monde fermé puis un autre
  ouvert dans la même session, n'a pas de gametest (les gametests clients de Fabric, un étage de plus à décider).
- **Deux limites assumées** : le sidecar meta se modifie sans verrou propre, et une édition extérieure du fichier n'est vue qu'au rechargement
  que l'utilisateur demande (C-35, en attente).
- **Ni écran de configuration ni positionnement écrit** (C-19, C-20).

## 2. La lecture du tableau

- **Priorités** : P1, les fondations (fiabilité et comportements par défaut) ; P2, l'API et le ménage ; P3, la vision (ce qui fait grandir).
- **Effort** : S (une séance courte), M (une vraie séance), L (plusieurs séances ou une conception préalable).
- **Sources** : `B1` à `B4` = constats n° 1 à 4 de la section 6 du README du banc ; `CRASH` = le crash client du 2026-09-13 à 11:30 ;
  `TODO-1/2/3` = les trois points de l'ancien `Docs\TODO` ; `TESTS` = la remise au vert du 2026-09-13 ; `LECTURE` = la lecture du code ;
  `AVIS` = l'avis externe du 2026-09-28, écrit pour AegisPerms, un consommateur dont le fichier de droits est édité hors du mod, vérifié point par
  point contre le code le même jour ; `AEGIS` = la demande d'AegisPerms du 2026-09-30, section 4.12 de son cahier des charges.

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
- [x] **C-28 : le balayage strict des temporaires** (S ; AVIS). `sweepOrphanTemps` supprime à l'ouverture tout `<nom>.*.tmp` du dossier (le motif est
  « commence par `<nom>.` et finit par `.tmp` ») : un `permissions.json.tmp` écrit par une autre application part avec les orphelins de Storify, mesuré
  le 2026-09-28. Ne balayer que le motif propre, `<nom>.<8 hexadécimaux>.tmp`, pour le fichier et pour son sidecar `<nom>.meta.json` ; un test où le
  temporaire étranger survit. **Fait le 2026-09-28** : le nom du temporaire et son motif de reconnaissance sortent en deux fonctions du companion de
  `BaseStore` (`tempFileName`, `ownTempPattern`), une seule définition pour l'écriture et le balayage, qui ne reconnaît plus que ce motif, pour le
  fichier et pour son sidecar ; un test où `homes.json.tmp` et `homes.json.backup.tmp` survivent à l'ouverture quand les temporaires propres disparaissent.
- [x] **C-29 : la copie profonde respectueuse du réglage et du format** (M ; AVIS). `useDeepCopy` n'est pas respecté partout : la racine est copiée à
  l'ouverture (`_lastSavedData`) et au rechargement (`replaceData`) quel que soit le réglage, et le véhicule est un aller-retour CBOR. Conséquences : toute
  data class doit survivre à CBOR, un sérialiseur écrit pour le JSON (un cast `JsonDecoder`, un `JsonTransformingSerializer`) casse à l'ouverture du
  store, et chaque ouverture encode puis décode la racine sans public. Respecter le réglage (captures `Unavailable` ou `Shallow` quand il est faux), puis
  remplacer CBOR par un aller-retour d'arbre `JsonElement` (le `Json` du format pour JSON et JSON5, un `Json` de copie sinon), qui copie toute valeur,
  tolère les sérialiseurs spécifiques au JSON et retire `kotlinx-serialization-cbor` ; une stratégie enfichable en option ; `DeepCopyBenchmark` mesure le
  coût. **Fait le 2026-09-28** : `DeepCopier` public (`utils\DeepCopier.kt`) et son véhicule unique `JsonTreeCopier` ; `StoreFormat.deepCopier()`
  désigne le copieur des stores du format (le `Json` de `JsonFormat` et de `Json5Format`, l'arbre JSON au module du `Toml` pour `TomlFormat`, le défaut
  pour un format tiers) ; `BaseStore` copie tout par lui et respecte `useDeepCopy` partout (ni `Initial` ni copie à l'ouverture sans lui, captures
  `Shallow` au rechargement, et aucune capture de rechargement sans public) ; `kotlinx-serialization-cbor` quitte le build, gardé en test pour la démo
  et la colonne de comparaison du benchmark ; `deepCopy()` remplace `deepCopyViaCbor` et `deepCopyValue`. Mesuré : l'arbre JSON vaut CBOR (0,6 contre
  0,4 µs sur un petit objet, 57 contre 74 µs sur 200 records). Six tests neufs et quatre refaits, dont l'ouverture, l'update observé et le rechargement
  d'un store au sérialiseur écrit pour le JSON, la fixture `ShapedRule`.
- [x] **C-30 : le mode lecture seule, et le hook débrayable** (M ; AVIS). Rien ne déclare « ce fichier ne se réécrit jamais » : `saveImmediate()` écrit
  sans condition, une transaction au bloc vide pose le dirty, et le hook d'arrêt est armé dans tous les cas (un `Thread` jamais démarré, mais une
  référence forte, et une écriture si dirty). `StoreConfig(readOnly = true)`, miroir dans l'annotation : `set`, `mutate`, `transaction` et
  `saveImmediate` refusent comme sur un store fermé, ni planificateur ni hook, `reloadFromFile` permis, `withAutoSave` ignoré et documenté ;
  `createIfMissing` (défaut `true`) écrit une seule fois le fichier initial absent ; et `withShutdownHook` (défaut `true`), forcé à `false` par `readOnly`.
  **En attente, décision du 2026-09-29** : la question est celle du propriétaire du fichier, et elle se tranche dans le design du consommateur, pas
  dans Storify. Si le mod est propriétaire (il écrit, les éditions extérieures passent par lui ou par le rituel éditer puis `/reload`), la règle dirty
  d'aujourd'hui suffit (rien n'est écrit à l'arrêt sans modification en mémoire) et `readOnly` ne sert à rien. Si un programme externe devient
  propriétaire (le mod ne fait que lire, valider et recharger), `readOnly` transforme la discipline « ne jamais écrire » en contrat vérifié par la lib :
  c'est le seul cas qui justifie le chantier, et rien ne le confirme pour AegisPerms. Le design est prêt : les trois réglages ci-dessus, `Store.isReadOnly`,
  un `checkWritable()` en tête du pipeline d'update, de la transaction et de `save(IMMEDIATE)`, les tests dans `lifecycle\ReadOnlyTest` ; une démo
  jetable, hors dépôt, a montré le 2026-09-29 les trois gestes anodins qui écrasent une édition externe (une transaction au bloc vide, un
  `saveImmediate` sans modification, le hook d'arrêt sur un store dirty). La garantie serait à l'exécution (une exception), pas à la compilation : un
  type sans méthodes d'écriture demanderait une refonte de `Store`, écartée. S'ouvre le jour où un consommateur a un écrivain externe.
  **Rouvert et fait le 2026-09-29, décision de l'utilisateur** : `readOnly`, `withShutdownHook` et `createIfMissing` dans `StoreConfig` et dans
  l'annotation (leurs défauts sont le comportement d'avant), `Store.isReadOnly` ; `checkWritable()` en tête du pipeline d'update, de la transaction
  et de `save(IMMEDIATE)`, `save` muet sur les autres déclencheurs en lecture seule, `initAutoSave` et `initShutdownHook` conditionnés (témoins
  internes `isAutoSaveScheduled` et `isShutdownHookArmed`), `close()` fait sa sauvegarde d'adieu même sans hook, `persistInitialData` conditionné
  par `createIfMissing`, interrupteurs d'auto-save inertes en lecture seule, une ligne `info` « opened read-only ». `createFromResource` copie
  toujours sa ressource, documenté. Sept tests dans `lifecycle\ReadOnlyTest`, un dans `ResolutionTest`.
- [x] **C-31 : le BOM UTF-8 toléré** (S ; AVIS). Mesuré le 2026-09-28 : un fichier enregistré avec BOM échoue en JSON (`JsonDecodingException` à
  l'offset 0) et en TOML (`UnexpectedTokenException`, ligne 1) ; JSON5 passe. Retirer les trois octets au décodage de `JsonFormat` et `TomlFormat`, un
  test par format, et vérifier que la réconciliation JSON5 tolère un texte existant qui commence par un BOM. **Fait le 2026-09-29** :
  `utils\Utf8Bom.kt`, `InputStream.withoutUtf8Bom()` (un `PushbackInputStream`, les trois octets avalés s'ils valent `EF BB BF`, remis sinon) et
  `String.withoutUtf8Bom()`, internes ; `JsonFormat` et `TomlFormat` décodent à travers, `Json5Format` retire le BOM du texte décodé et du texte
  existant avant la réconciliation, l'enrichisseur l'ôte de sa première ligne ; Storify lit avec ou sans BOM et écrit toujours sans. Cinq tests : les
  trois formats, la sauvegarde préservante qui garde ses commentaires et ressort sans BOM, le store qui s'ouvre sur un fichier du Bloc-notes et le
  réécrit sans, l'enrichisseur.
- [x] **C-40 : la ressource embarquée copiée telle quelle** (S ; LECTURE). `createFromResource` copie sa ressource puis la réécrit : l'origine des
  données est arrêtée à la construction du store, avant la copie, et vaut `DEFAULT` ; la validation passée, `persistInitialData` réencode donc le
  fichier par le format. Mesuré le 2026-09-30 : une ressource JSON ressort réindentée (42 octets devenus 45), une ressource JSON5 ou TOML commentée
  perd tous ses commentaires dès le premier lancement (258 octets devenus 41, 236 devenus 30), et la copie ne reste intacte qu'invalide, ou avec
  `createIfMissing = false`. S'y ajoute la copie elle-même, seule écriture de la lib hors de l'écrivain atomique, au flux jamais fermé. Poser
  l'origine après l'appel du fournisseur, ne plus réécrire la copie, et la faire passer par `AtomicFiles.write`. **Fait le 2026-09-30** : trois
  origines (`FILE`, `RESOURCE`, `DEFAULT`), posées par `initData` une fois le fournisseur appelé ; `persistInitialData` n'écrit que pour `DEFAULT`,
  la copie reste la ressource à l'octet, et la première sauvegarde JSON5 en préserve les commentaires ; le message d'une ressource invalide dit
  `copied from the default resource` ; la copie passe par `AtomicFiles.write`, flux fermé, sous le logger du store (la fabrique du fournisseur
  reçoit la config résolue), et sa logique sort de l'inline (`DefaultProvider.resourceProvider`) ; `createIfMissing` est sans effet sur
  `createFromResource`. Quatre tests neufs et un étendu dans `CreateFromResourceTest`, dont la copie qui casse en route sans laisser ni fichier
  tronqué ni temporaire (vu échouer sur la copie directe) ; la démo `ResourceCopyDemo.kt`.

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
- [x] **C-26 : la sauvegarde JSON5 préservant les commentaires** (M ; C-21). La brique offre un éditeur chirurgical du source
  (`parseToDocument`, puis `set`, `putProperty`, `remove` par plages exactes, commentaires attachés aux nœuds) : au save, réconcilier l'arbre
  encodé avec le document parsé et n'appliquer que les différences, pour que les commentaires et le style de l'admin survivent aux sauvegardes.
  À concevoir : le diff récursif, la stratégie des tableaux (le point dur), les replis (fichier absent ou invalide : encode à neuf). Se greffe
  dans `encodeToPath` sans toucher au contrat C-09. **Fait le 2026-09-15**, avec une correction de design en route : la greffe ne pouvait pas
  vivre dans le seul `encodeToPath` (le save atomique encode vers un temporaire, le format ne voit jamais la cible) ; d'où la capacité
  optionnelle `PreservingStoreFormat`, détectée par `BaseStore` au save, qui fournit le texte actuel de la cible : le contrat C-09 reste
  intact, les formats ordinaires ne savent rien. `Json5Format` la déclare : diff récursif de l'arbre encodé contre le document parsé, retouches
  seules appliquées, commentaires et style préservés, et l'idempotence en prime : un save sans changement est identique à l'octet, épinglé.
  Décisions v1 : un tableau modifié se remplace entier (l'appariement commentaire-élément d'un diff par index mentirait), fichier absent ou
  invalide vaut encode à neuf. Six tests neufs, et le test C-21 « les commentaires meurent au save » s'est inversé en « ils survivent ».
- [x] **C-27 : la licence LGPL-3.0-only** (S ; décision du 2026-09-28). La lib quitte « tous droits réservés » pour la GNU Lesser General Public License,
  version 3 seulement. **Fait le 2026-09-28** : `LICENSE` porte le texte de la LGPL v3 et `LICENSE.GPL` celui de la GPL v3 qu'elle incorpore (textes pris
  à gnu.org) ; le POM déclare la licence, l'URL du projet, le SCM et le développeur ; les deux textes entrent dans le jar et dans le jar de sources
  (`LICENSE_storify`, `LICENSE.GPL_storify`) ; chaque `.kt` de `src\main` et `src\test` porte ses deux lignes SPDX (`SPDX-FileCopyrightText`,
  `SPDX-License-Identifier`) ; le README résume ce que la licence permet à un consommateur ; le dépôt GitHub est public. Le banc, compagnon, reste tous
  droits réservés.
- [x] **C-32 : les lignes de validation, robustes et publiques** (M ; AVIS). Mesuré le 2026-09-28 : une erreur dont le chemin porte une clé de map entre
  crochets (`players[steve].joinCount`, ce que `HomesDataValidator` du banc écrit) fait perdre la ligne à tout le lot, parce que le segment passe par
  `.toInt()` et que l'exception est attrapée au niveau du lot. S'y ajoutent l'enrichisseur `internal`, JSON seul, et `validateNow()` qui n'enrichit pas.
  Accepter les clés de map (`players[steve]`, `players["steve"]`), isoler l'échec par erreur, rendre l'enrichisseur public et couvrir JSON5 (clés nues,
  apostrophes), et offrir une validation de fichier sans store, `StoreFormat.validateFile(path, deserializer, validator)`, qui décode, valide et enrichit.
  **Fait le 2026-09-29** : `validation\ErrorLineLocator.kt` (la grammaire `ErrorPath` et `PathSegment`, l'interface `ErrorLineLocator` et
  `JsonLineLocator`, le parcours ligne à ligne aveugle aux chaînes et aux commentaires, les trois graphies de clé, les index sur objets et
  primitives, la valeur en ligne rendue au mieux) ; `StoreFormat.lineLocator()` (JSON et JSON5 le rendent, TOML non) et
  `StoreFormat.validateFile(path, deserializer, validator)` avec son sucre réifié ; `Store.validateFile()` inspecte le disque sans toucher la
  mémoire, `validateNow()` reste sans lignes et le dit ; `ValidationErrorEnricher` public, l'échec isolé par erreur, `ValidationError.jsonLine`
  devient `line` ; `Validator.evaluate(data)` partagé par le store et la validation de fichier ; `validateEach` sur les maps dans
  `ValidationContext`. Seize tests neufs ou refaits (`ErrorEnricherTest`, `ValidateFileTest`, `ValidationContextTest`) ; le banc gagne ses lignes
  sur `players[<uuid>]` sans changer.
- [x] **C-33 : les erreurs de décodage enveloppées** (S ; AVIS). Une syntaxe fausse ou une clé inconnue lève la `SerializationException` nue de kotlinx
  (ou de tomlkt, ou de json5), sans le chemin du fichier. Une `StoreDecodeException(path, cause)` au chargement et au rechargement, dont le message porte
  le chemin et celui du parseur ; un ancêtre commun `StorifyException` avec `ValidationException`, pour attraper d'un seul `catch` tout ce qui empêche
  d'ouvrir. **Fait le 2026-09-29, en version petite** : `StorifyException` (une `RuntimeException`, ancêtre des fautes du fichier ;
  `ValidationException` quitte `IllegalStateException` pour la rejoindre) et `StoreDecodeException(path, format, cause)`, le chemin, le format,
  la ligne au mieux (l'offset de kotlinx et l'index de json5 convertis en ligne, le `(L2)` de tomlkt), la cause conservée ; levée par
  `StoreFormat.decodeFile`, la voie de tout décodage fait pour un consommateur (ouverture, rechargement, les deux `validateFile`, sidecar meta,
  ressource embarquée), le contrat brut `decodeFromPath` intact. Cinq tests dans `lifecycle\DecodeErrorTest`, deux existants adaptés.
- [x] **C-34 : l'écrivain atomique public** (S ; AVIS). `atomicWrite` est privé, et `encodeToPath` des trois formats écrit directement dans la cible :
  tout fichier qu'un consommateur écrit hors d'un store réclame son propre écrivain. Un objet public `AtomicFiles.write(target) { temp -> ... }` aux
  mêmes garanties (temporaire voisin, `force`, `ATOMIC_MOVE` avec repli), utilisé par `BaseStore`, et l'extension
  `StoreFormat.encodeToPathAtomically(serializer, data, path)` avec son sucre réifié ; le motif de C-28 y vit, en une seule définition.
  **Fait le 2026-09-29** : `utils\AtomicFiles` (`write`, `tempFileName`, `ownTempPattern`, `sweepOrphanTemps`), `BaseStore` délègue son
  `atomicWrite` et son balayage sans changer de comportement, `encodeToPathAtomically` et son sucre réifié sur `StoreFormat`. Trois tests dans
  `AtomicWriteTest`, dont le premier de la suite sur la panne au milieu de l'écriture : le fichier précédent reste entier, par le store comme par
  `AtomicFiles.write`, sans temporaire.
- [ ] **C-35 : la surveillance du fichier** (M ; AVIS). Un store ne voit pas les modifications extérieures, alors que l'édition par une application de
  bureau pendant que le serveur tourne est le cas d'usage du JSON. `watchFile` dans `StoreConfig` : un sondage de la date de modification et de la taille
  sur le planificateur déjà présent (aucun fil de plus), intervalle `watchIntervalMs` ; à chaque changement, un rechargement validé avec le callback
  `onReload` ; en échec, `warn` et mémoire intacte, jamais d'exception depuis un fil de fond ; les propres écritures du store reconnues par l'empreinte
  relevée après chaque save ; store dirty et fichier changé : `warn` sans rechargement. `WatchService` écarté (un fil par dossier, des notifications
  doublées par les éditeurs). **En attente avec C-30 (2026-09-29)** : même condition, un programme externe propriétaire du fichier ; avec le mod
  propriétaire, le rituel éditer puis `/reload` couvre le besoin. **Décision du 2026-09-29 au soir** : reste en attente et peut ne jamais se faire,
  AegisPerms pouvant surveiller ses fichiers lui-même (son brouillon le faisait pour sa base, `watch_database`). Si le chantier s'ouvre un jour,
  quatre points s'ajoutent au design : un callback d'échec de rechargement, le planificateur à créer pour un store en lecture seule, le retour sur
  le fil du serveur laissé au consommateur, et la règle des deux écrivains, qui appartient au consommateur.
- [x] **C-36 : le JSON strict par défaut** (S ; AVIS). `JsonFormat()` accepte les commentaires, les chaînes sans guillemets et `NaN` (`isLenient`,
  `allowComments`, `allowSpecialFloatingPointValues`), quand `ignoreUnknownKeys` reste faux : strict sur les clés, laxiste sur la syntaxe ; et TOML
  tolère les clés inconnues (le curseur 5 de C-17). `JsonFormat()` strict (`isLenient` et `allowComments` à faux) et une fabrique `JsonFormat.lenient()`
  qui rend l'actuel ; le sort d'`allowSpecialFloatingPointValues` reste à trancher (le garder évite qu'une sauvegarde échoue sur un `NaN`). Un fichier
  JSON à commentaires cesse alors de charger : JSON5 est fait pour lui. **Fait le 2026-09-29** : le `Json` par défaut perd `isLenient` et
  `allowComments`, un commentaire ou une clé sans guillemets lèvent `StoreDecodeException` avec la ligne, comme une virgule finale ou une clé
  inconnue déjà ; `JsonFormat.lenient()` rend le lecteur tolérant ; le registre, l'annotation et le sidecar meta prennent le défaut.
  `allowSpecialFloatingPointValues` gardé, décision de l'utilisateur : refuser le `NaN` ferait échouer chaque sauvegarde du store loin du code
  fautif (mesuré : `saveImmediate()` puis `close()` lèvent, le fichier reste intact), quand un `NaN` écrit se relit ; `allowStructuredMapKeys`
  gardé, il ne tolère aucune syntaxe. Le strict s'accorde avec ce que Storify écrit et avec AegisPerms, qui construisait son propre `Json` strict
  pour l'obtenir. Trois tests (`JsonFormatTest`, `DecodeErrorTest`).
- [x] **C-37 : le logger nommé** (S ; AVIS). Le logger est nommé d'après la classe et le préfixe `[Storify]` est en dur (dix-sept fois dans `BaseStore`) :
  les messages n'apparaissent pas sous le journal du mod. `StoreConfig.loggerName` (défaut `Storify`, miroir dans l'annotation) ; le préfixe reste, il
  identifie la lib dans le journal d'un mod qui passe son propre nom. **Fait le 2026-09-29** : `StoreConfig.loggerName` (défaut `Storify`) et
  son miroir dans `@StoreConfiguration`, résolus par la factory ; `BaseStore` fabrique son logger sur ce nom, les messages et leur préfixe
  inchangés ; `AtomicFiles.write` reçoit en option le logger de son repli non atomique, et le store lui passe le sien (l'écrivain de C-34 avait
  un logger à lui, sous lequel les sauvegardes d'un store auraient parlé). Trois tests (`LoggerNameTest`, `ResolutionTest`).
- [x] **C-39 : la version 0.2.0-SNAPSHOT et sa republication** (S ; AVIS). Repsy ne porte que `0.1.0-SNAPSHOT`, sans étiquette Git : deux jars d'un
  consommateur construits à deux dates peuvent embarquer deux Storify sous le même nom. L'avis proposait une `0.1.0` figée ; décision du 2026-09-28 :
  bump direct, sans release figée. **Le bump est fait le 2026-09-28** (`mod_version=0.2.0-SNAPSHOT`, docs alignées). Reste, à la fin des chantiers : la
  publication par l'utilisateur, avec le jeton, puis la recette du README (section 4) et le catalogue du banc (`storify`) qui passent à
  `0.2.0-SNAPSHOT`. **Fait le 2026-09-29** : publiée sur Repsy par l'utilisateur, avec le jeton, après un build propre sans cache de build (le cache
  restaurait dans le jar un dossier vide, reste du sérialiseur retiré) ; la recette du README et le catalogue du banc citent `0.2.0-SNAPSHOT`, et le
  banc en mode `repsy` construit sur l'artefact publié.
- [x] **C-41 : le snapshot du save, devant public seulement** (S ; LECTURE). Le README promet qu'aucune copie ne part sans public, mais le store
  copie sa racine à chaque ouverture et à chaque sauvegarde pour nourrir l'avant et l'après des callbacks de save, qu'un auditeur existe ou non :
  C-29 n'avait soumis ces copies qu'à `useDeepCopy`. Mesuré le 2026-09-30 au compteur de sérialisations : 2 à l'ouverture et 2 par sauvegarde là
  où le fichier en demande 1 ; sur une racine de 5 000 joueurs (794 Ko), 1,24 ms par copie pour une sauvegarde de 93 ms, et un second exemplaire
  de la racine gardé en mémoire toute la vie du store. Le gain est petit ; la règle est celle de C-25, ne copier que devant un auditeur.
  **Fait le 2026-09-30, décision de l'utilisateur : le code plutôt que la phrase.** `initData` ne copie plus la racine ; sans auditeur de save,
  `save()` écrit, remet le dirty à zéro et s'arrête, sans copie ni opération ; `registerOnSave` prend la référence à l'arrivée du premier
  auditeur, sur un store sans modification en attente : l'`Initial` du premier save et le `DeepCopy` des suivants sont inchangés, et un store en
  lecture seule, qui ne sauve jamais, n'en prend pas. Bord assumé, écrit dans la KDoc de `registerOnSave` : un callback enregistré sur un store
  déjà modifié reçoit `Unavailable` en `old` à son premier save. Le README et `architecture.md` disent vrai : aucune capture sans public ni sans
  `useDeepCopy`, seul le secours de rollback des transactions se prend toujours. Cinq tests neufs et un refait dans `SaveTest`, au compteur ; la
  démo `SaveSnapshotDemo.kt`.
- [x] **C-42 : `NaN` et les infinis en JSON5** (S ; LECTURE). C-36 a gardé `allowSpecialFloatingPointValues` dans `JsonFormat` pour qu'un `NaN` ne
  fasse pas échouer chaque sauvegarde du store loin du code fautif ; le pont `Json` de `Json5Format` ne l'a pas. Mesuré le 2026-09-30 sur `NaN`,
  `+Infinity` et `-Infinity` : JSON et TOML les sauvent et les relisent ; en JSON5, `saveImmediate()` lève `JsonEncodingException` (le fichier
  garde l'ancienne valeur), et un fichier où la valeur est écrite à la main, du JSON5 pourtant valide, ne s'ouvre pas (`StoreDecodeException`).
  **Fait le 2026-09-30** : le pont par défaut de `Json5Format` gagne `allowSpecialFloatingPointValues` ; les trois valeurs se sauvent, se
  relisent et se chargent écrites à la main (`+Infinity` compris), et un save sans changement laisse le fichier identique à l'octet ; un `Json`
  passé par le consommateur reste pris tel quel. Trois tests neufs dans `Json5FormatTest`, un dans `TomlFormatTest`, celui de `JsonFormatTest`
  étendu aux infinis : la règle est épinglée pour les trois formats ; la démo `SpecialFloatsDemo.kt`.
- [x] **C-43 : la doc au présent** (S ; LECTURE). Les chantiers de la semaine ont laissé la doc raconter l'avant. Le bilan de ce document datait
  de l'audit de septembre : il citait « le deep copy CBOR » quand toutes ses faiblesses étaient soldées. `architecture.md` disait « depuis C-xx »,
  « jadis », « l'ex-Utils » en dix-sept endroits, et son chapitre 14 racontait les constats du banc chantier par chantier. Deux en-têtes de démo
  mentaient (`DeepCopyDemo.kt`, « aujourd'hui, CBOR » ; `JsonStrictDemo.kt`, « le point à trancher »), la KDoc d'`isDirty` ne connaissait que le
  tick, et la préséance « explicite > annotation > défaut » du README se lisait comme une fusion champ par champ. **Fait le 2026-09-30** : le
  bilan réécrit au présent, forces et faiblesses d'aujourd'hui ; les deux intros réduites à leur périmètre ; `architecture.md` au présent, le
  renvoi « (C-xx) » gardé comme pointeur vers ce document, le chapitre 14 retiré (les constats vivent à la section 6 du README du banc, ses deux
  faits encore vrais rejoignent les chapitres 4 et 8) ; les deux en-têtes, la KDoc d'`isDirty` (remis à `false` après toute écriture réussie et au
  rechargement) et trois autres KDoc ; la règle « en bloc » écrite au README, dans la KDoc de la factory et au chapitre 3. Aucun code ne change.
  Un commit à part pour la forme : le saut de ligne final que `.editorconfig` demande, ajouté aux fichiers suivis qui ne l'avaient pas,
  `simplelogger.properties` réencodé en UTF-8 du même geste (l'octet Windows-1252 d'un commentaire, noté au journal du 28), `.idea` laissé à
  IntelliJ.
- [x] **C-44 : la version 0.3.0-SNAPSHOT** (S ; LECTURE). Neuf chantiers depuis la `0.2.0-SNAPSHOT` du 2026-09-29, dont deux qui changent ce
  qu'un mod compile chez lui (C-45, C-46) : un jar de mod construit contre l'une n'en profite pas avec l'autre. Bump direct, sans release figée,
  la règle de C-39. **Fait le 2026-09-30** : `mod_version=0.3.0-SNAPSHOT` ; le README (coordonnées, compteur à 291 tests, la recette de la
  section 4), le readme et le contexte du classeur ; un build propre sans cache de build, le jar et le jar de sources relus (92 et 31 fichiers,
  aucun dossier vide, les deux licences), le POM généré en local relu (coordonnées, licence, SCM ; les scopes restent ceux d'aujourd'hui, la
  question de la release figée) ; publiée sur Repsy par l'utilisateur, avec le jeton, build 1 du snapshot ; le catalogue du banc passe à
  `0.3.0-SNAPSHOT` et le banc construit en mode `repsy` sur l'artefact publié : son jar autonome de 663 Ko emboîte `storify-0.3.0-SNAPSHOT.jar`,
  tomlkt et json5, déclarés dans son `fabric.mod.json`.
- [x] **C-45 : le pipeline d'update hors de l'inline** (M ; LECTURE). `runUpdateInternal` est `inline` de bout en bout (il lui faut
  `serializer<VALUE>()` et `VALUE::class`) : le pipeline entier se compile chez chaque appelant. Mesuré le 2026-09-30 : le fichier `.class` d'un
  appelant de trois lignes pèse 15 897 octets et référence douze membres internes de `BaseStore`, `HomeCommands.class` du banc 31 289 octets.
  Un correctif du pipeline n'atteint donc un mod qu'à sa recompilation, et un membre interne renommé casserait un mod déjà compilé, le chargeur
  Fabric ne gardant qu'une version de la lib entre les mods. La démo a montré deux défauts de plus dans la même fonction : le raccourci immuable
  ne reconnaît ni `Int`, ni `Long`, ni `Double`, ni `Boolean`, copiés en profondeur en `SNAPSHOT` (un type réifié est vu sous sa forme boxée,
  `isPrimitive` y est toujours faux), et l'`old` d'un `set` qui repose la valeur déjà en place vaut `Unavailable` ou `Shallow` selon le cache de
  boîtes de la JVM. **Fait le 2026-09-30** : le pipeline est une fonction ordinaire de `BaseStore` (`runUpdate`) ; `set`, `setIn`, `mutate` et
  `mutateIn` ne matérialisent plus que le sérialiseur de la valeur, par une lambda appelée seulement quand une copie est due, et passent par deux
  points d'entrée (`setValue`, `mutateValue`) ; `transaction` n'est plus `inline` ; le `@PublishedApi` de `BaseStore` se réduit à son
  constructeur et à ces deux points d'entrée, les autres membres redevenant privés (ou `internal` pour les tests), et les trois aides du garde
  C-05 disparaissent. L'immuabilité se juge sur la valeur (`null`, primitives, `Char`, `String`, enum) ; l'`old` d'un `set` est toujours
  l'ancienne valeur, celui d'une mutation en place sans snapshot reste `Unavailable` ; `updatePolicies` devient une `ConcurrentHashMap`. Mesuré
  après : 5 531 octets et deux membres pour l'appelant témoin, 19 667 octets pour `HomeCommands.class`. Cinq tests neufs : `CallerBytecodeTest`
  (le garde-fou, qui lit le fichier `.class` d'un appelant, et l'appelant en action) et trois dans `SetTest` (le raccourci type par type, `null`
  face à une valeur mutable, la valeur reposée) ; la suite d'update existante passe sans retouche ; la démo `UpdatePipelineDemo.kt`.
- [x] **C-46 : la factory hors de l'inline** (M ; LECTURE). Les huit `create*` sont `inline` de bout en bout, comme le pipeline avant C-45 : la
  lecture des cinq annotations, le choix du format selon l'enum, la construction de la config annotée, le fournisseur des données initiales et
  l'appel au constructeur de `BaseStore` se compilent chez l'appelant. Mesuré le 2026-09-30 : l'appelant témoin de la démo pèse 15 363 octets
  pour deux ouvertures (25 915 avec ses six classes synthétiques) et porte 49 références à la lib, dont les 11 attributs de `@StoreConfiguration`
  lus un par un ; `StoribenchStores.class` du banc, 23 295 octets. Un attribut ajouté à l'annotation ou un format de plus dans l'enum restent
  invisibles d'un mod déjà compilé, dont le bytecode est lié au constructeur de `BaseStore`. **Fait le 2026-09-30** : les fabriques ne
  matérialisent plus que `DATA::class` et `serializer<DATA>()`, puis appellent quatre points d'entrée ordinaires de `StoreFactory`, un par source
  de données initiales (`openFromCompanion`, `openFromConstructor`, `openFromDefaultable`, `openFromResource`) ; la lecture des annotations, la
  résolution et la construction vivent dans la lib ; le constructeur de `BaseStore` perd son `@PublishedApi` ; `DefaultProvider` et ses quatre
  fabriques, publics pour le seul service de l'inline, disparaissent (la copie de ressource de C-40 devient `StoreFactory.copyResource`, interne) ;
  le registre des formats devient une `ConcurrentHashMap`. Mesuré après : 3 730 octets, aucune classe synthétique et 4 références pour l'appelant
  témoin (la factory, et la config qu'il construit lui-même) ; 12 577 octets pour `StoribenchStores.class`. Deux tests neufs dans
  `FactoryCallerBytecodeTest` (le garde-fou, qui lit le fichier `.class` d'un appelant des huit fabriques, et l'appelant en action), le test de la
  copie qui casse adapté ; la suite `factory` passe sans autre retouche ; la démo `StoreOpeningDemo.kt`.
- [x] **C-47 : le rechargement d'un store modifié** (S ; LECTURE). `reloadFromFile()` remplace la mémoire par le fichier sans regarder le drapeau
  dirty : une modification en mémoire pas encore sauvegardée disparaît sans un mot, et le drapeau reste levé alors que la mémoire est le fichier,
  si bien que la sauvegarde suivante (le tick, `close()`, le hook) réécrit un fichier qui n'a pas changé, mise en forme de l'admin comprise.
  Mesuré le 2026-09-30 (`ReloadDirtyDemo.kt`) : un `count` posé en mémoire perdu au rechargement, dirty toujours levé, puis une sauvegarde
  d'adieu `CLOSE` qui réindente le JSON compact de l'admin. La règle ne change pas : l'utilisateur édite, puis recharge lui-même (C-35 reste en
  attente). **Fait le 2026-09-30** : le fichier gagne et le store le dit, une ligne `warn` quand un rechargement écarte des modifications non
  sauvegardées ; le dirty retombe après un rechargement réussi ; devant un auditeur de save, la référence du prochain `old` devient la racine
  rechargée (la règle de C-41) ; le sidecar meta n'est pas relu. Quatre tests dans `ReloadTest` (la modification écartée et le dirty retombé, la
  sauvegarde d'adieu qui ne part plus et le fichier de l'admin intact, l'`old` du save après rechargement, la ligne de log) ; la démo passe à
  sa forme « avant, depuis ».
- [x] **C-48 : l'écriture JSON tamponnée** (S ; LECTURE). Le candidat de départ visait le verrou : une sauvegarde tient le read lock pendant
  toute l'écriture, flush compris, et tout `set` d'un autre fil attend. La démo (`SaveLockDemo.kt`) l'a mesuré le 2026-09-30 sur 5 000 joueurs
  (794 Ko) : un `mutate` lancé pendant la sauvegarde attend 97 ms, mais le flush disque coûte 1,7 ms et le déplacement atomique 0,5 ms ; les
  88,7 ms restantes sont l'encodage, parce que `JsonFormat` écrivait sur le flux nu du fichier, où chaque petit morceau produit par kotlinx
  partait au système en appel séparé (le même JSON sur un flux tamponné : 4,4 ms). Sortir le flush du verrou est abandonné, il n'y a rien à y
  gagner. **Fait le 2026-09-30** : `JsonFormat` écrit et lit par un flux tamponné (`buffered()`, 8 Ko, que la mesure ne distingue pas de
  64 Ko), même texte, même atomicité ; TOML tamponnait déjà, JSON5 lit et écrit le texte entier. Mesuré après : l'encodage 4,7 ms, la
  sauvegarde 6,7 ms, le `mutate` concurrent 6,6 ms d'attente ; la lecture passe de 2,1 à 1,6 ms. Aucun test de temps dans la suite, qui serait
  fragile ; la correction est couverte par les tests JSON existants, et la démo garde les chiffres d'avant et d'après.
- [x] **C-49 : les gametests du banc, l'étage 2** (L ; décision du 2026-09-30). La couverture automatisée de « ça marche en jeu » est nulle : la
  lib a ses tests hors du jeu, le banc déclare deux étages de test, `src\test` et `src\testMC`, que `build` joue en NO-SOURCE parce que les
  dossiers n'existent pas, et tout ce qu'un vrai serveur fait, l'ouverture des stores à `SERVER_STARTING`, les commandes, le fichier sur le
  disque, la fermeture, ne se vérifie qu'à la main, par le log. Le design, décalqué du troisième étage d'AegisPermsDraft (`mod\build.gradle.kts`,
  section 5) : le bloc `fabricApi.configureTests` de Loom crée un source set `src\gametest`, mod de test à part (`storibench-gametest`, son
  `fabric.mod.json` avec l'entrypoint `fabric-gametest`), le module `fabric-gametest-api-v1` épinglé dans le catalogue `mc` (absent du jar agrégé ;
  4.0.22 pour la 0.161.0, lu dans son POM), un run `runGameTest` qui hérite du run `server`, sans fenêtre, EULA acceptée par Fabric API, dans
  `build\run\gameTest`, une tâche `Delete` qui remet les fichiers du banc à neuf avant chaque run, et le run branché sur `check`. Les tests sont
  des `@GameTest` sur un `GameTestHelper` : un joueur simulé (`makeMockServerPlayerInLevel`) pour `/sethome`, `/homes` et `/home`, la source de la
  console pour les commandes des ops, le test qui pose ses propres `registerOnSave` et `registerOnUpdateOn` pour vérifier les captures, qui
  réécrit `homes.json` avant un `/storibench reload homes`, qui y glisse une valeur invalide puis un texte malformé, qui attend le tick d'auto-save
  (`maxTicks` relevé), qui ferme et rouvre les homes par les fonctions du banc ; et `runGameTest -Pstorify_source=repsy` rejoue tout sur l'artefact
  publié. Hors périmètre : le cycle solo du client (un monde fermé, un autre ouvert), qui relève des gametests clients de Fabric
  (`enableClientGameTests`, module 6.0.2), un étage de plus à décider après celui-ci ; l'emboîtement jar-in-jar, prouvé par le serveur pur.
  **Fait le 2026-09-30, commencé dans la session sur décision** : le banc passe à Fabric API 0.161.0, la dernière publiée pour 26.2 et celle
  d'AegisPermsDraft ; `fabricApi.configureTests` dans le build, le mod de test `storibench-gametest`, `runGameTest` dans `build\run\gameTest`,
  remis à neuf avant chaque run et branché sur `check` par Loom ; un seul `@GameTest` en quatorze étapes qui se suivent, les stores étant partagés :
  les deux stores ouverts, `/sethome` et sa limite, `/home` et le callback ciblé en `Shallow`, `/delhome`, deux saves avec `Initial` puis `DeepCopy`,
  le fichier édité qui gagne au rechargement, la valeur invalide puis le fichier malformé refusés, le tick d'auto-save observé, le store rechargé
  laissé en paix par le tick suivant, la fermeture et la réouverture des homes, la config écrite depuis le jeu. Trois découvertes du premier run :
  le joueur simulé passe par le hook de connexion du banc ; le serveur GameTest ne cadence pas ses ticks, les attentes se comptent en temps réel,
  sondées à chaque tick, et l'auto-save des homes descend à 3 s par `-Dstoribench.autoSaveMs` ; le jeu range ses tâches planifiées par identité,
  replanifier le même `Runnable` le perd. Vert en composite et en mode `repsy` sur l'artefact publié, 18 s par run ; `runGameTest` est lancé par
  l'IA à chaque build, décision de l'utilisateur. Le cycle solo du client reste un étage de plus, à décider.
- [x] **C-50 : les clés en double refusées** (S/M ; AEGIS). Un objet JSON qui porte deux fois la même clé (`"vip"` déclaré deux fois dans `groups`)
  est accepté : kotlinx garde la dernière valeur, à l'arbre (`JsonTreeReader.readObjectImpl`, `result[key] = element` sur une `LinkedHashMap`,
  sources 1.11.0) comme au flux, aucun réglage du paquet `json` n'en parle jusqu'à la 1.12.0-RC, et ni `JsonFormat` ni la validation ne le voient.
  Pour un fichier de vérité édité à la main, un doublon efface une définition en silence, avant le code du consommateur, qui ne peut rien : la map
  à clé naturelle, le champ répété d'une data class et le tableau décodé en `Set` perdent l'information dans le décodeur. Mesuré le 2026-10-01
  (`DuplicateKeysDemo.kt`) : à la racine, dans un objet imbriqué et dans une map, le dernier gagne sans un avertissement, au chargement, au
  rechargement et dans `validateFile` ; JSON5 (`li.songe:json5`) fait pareil, et sa sauvegarde préservante garde le doublon dans le fichier, que
  chaque relecture tranche à nouveau ; la sauvegarde JSON réécrit une seule clé ; TOML (tomlkt) refuse déjà, avec la ligne de la seconde
  occurrence. Le modèle en liste plus une règle d'unicité dans le validator est écarté : il reporte la charge sur chaque consommateur, ne couvre ni
  le champ répété ni le `Set`, et laisse ouverte toute map à clé naturelle. Design : en mode strict (un `Json` ni `isLenient` ni `allowComments`, la
  définition de C-36, donc `lenient()` et un `Json` tolérant exemptés par construction), `JsonFormat.decodeFromPath` lit le texte une fois, le passe
  à un scanner de la lib (`validation\JsonDuplicateKeys` : les objets ouverts en pile, les clés de chacun dans un ensemble, les chaînes décodées de
  leurs échappements, la ligne comptée au passage) et lève `StoreDecodeException` avec la ligne de la seconde occurrence au premier doublon, puis
  décode par `decodeFromString` ; coût mesuré, 1,5 ms par 794 Ko, le chargement de 5 000 joueurs passant de 1,5 à 3,7 ms environ, rien sur une
  config. `Json5Format` parse par `parseToDocument`, dont l'AST garde les deux membres (noms décodés, plages source), refuse de même, puis convertit
  par `toJsonElement()` (7,3 ms contre 2,8 sur 794 Ko, le créneau étant la config). TOML ne change pas, et un format tiers n'a rien à savoir : le
  contrat `StoreFormat` est intact. Écartés : le lecteur d'arbre et le `JsonDecoder` enveloppé (le doublon est perdu avant tout crochet, et une map
  reste hors de portée d'un `DeserializationStrategy` enveloppant), la brique json5 pour lire le JSON (7,3 ms, une brique de plus sous `JsonFormat`,
  à contre-courant de C-38), la passe sur le flux (sans objet tant qu'aucun store ne pèse plusieurs Mo). Tests : un doublon à la racine, dans un objet
  imbriqué, dans une map ; la même clé dans deux objets différents, acceptée ; une clé échappée (`"ab"` contre `"ab"`), reconnue ; la ligne de
  la seconde occurrence ; le chargement initial, `reloadFromFile` mémoire intacte, `validateFile` avec et sans store, `lenient()` qui laisse passer ;
  les mêmes en JSON5 ; TOML épinglé. Docs : README section 2, `architecture.md` chapitre 9 ; la démo passe à sa forme « avant, depuis ».
  **Fait le 2026-10-01**, avec deux retouches de design en route : le format lève une `DuplicateKeyException` nue (une `SerializationException`,
  comme les fautes de kotlinx, dans `validation\JsonDuplicateKeys.kt` avec le scanner), que `decodeFile` enveloppe comme les autres et dont
  `StoreDecodeException` lit la ligne, le contrat brut restant au niveau du parseur ; et le lecteur strict décode d'abord, puis cherche le
  doublon, pour qu'un texte mal formé reste diagnostiqué par kotlinx. `JsonFormat` strict lit le texte entier (`readText`, `decodeFromString`,
  le scanner), le tolérant lit par le flux, inchangé ; `Json5Format` parse par `parseToDocument`, convertit (la syntaxe d'abord) puis parcourt
  l'AST ; TOML inchangé. Quinze tests dans `formats\DuplicateKeysTest` : les six de la demande, le contrat brut, le texte mal formé, le lecteur
  tolérant et deux `Json` tolérants d'un consommateur, l'ouverture, le rechargement mémoire intacte, `validateFile` avec et sans store, JSON5
  (clés nues, apostrophes, échappements, rechargement) et TOML épinglé ; la démo à sa forme « avant, depuis », le lecteur tolérant montrant
  l'avant.
- [x] **C-51 : la version 0.4.0-SNAPSHOT** (S ; décision du 2026-10-01). C-50 change ce que la lib accepte au chargement : un fichier à clé en
  double, accepté par la `0.3.0-SNAPSHOT`, est refusé ; un numéro visible plutôt qu'une republication silencieuse, le bump direct en snapshot, la
  règle de C-39 et C-44. La release figée attend toujours les scopes du POM et le constructeur de `StoreConfig`. **Fait le 2026-10-01** :
  `mod_version=0.4.0-SNAPSHOT` ; le README (coordonnées, la recette de la section 4), le readme et le contexte du classeur, le chapitre 5 du
  contexte d'AegisPerms ; un build propre sans cache de build, le jar et le POM relus ; publiée sur Repsy par l'utilisateur, avec le jeton, build 1
  du snapshot ; le catalogue du banc passe à `0.4.0-SNAPSHOT` et le banc construit en mode `repsy` sur l'artefact publié.
- [x] **C-52 : `BaseStore` au cordeau** (S ; LECTURE). Le fichier s'est construit par accrétion : des fonctions mêlées aux champs (`replaceData`
  entre `data` et son origine, `markDirty` entre `isDirty` et l'auto-save, les gardes entre le hook et les callbacks), `init` au milieu, après
  trois méthodes publiques, et ses aides hors de l'ordre où il les appelle, des titres de section pour la moitié du fichier, `TargetedListener`
  et `DataOrigin` loin de leurs usages, quatre champs à underscore dont un seul double une propriété publique (d'où le `@Suppress("PropertyName")`
  sur la classe), `StoreConfig`, type public de l'API, logé dans le fichier du store, et une KDoc de classe restée au brief d'origine.
  **Fait le 2026-10-01** : le corps de la classe suit l'ordre de Java, que `style-de-code.md` fixe pour Kotlin aussi : le companion en tête, les
  propriétés du public au privé (et par thème dans le privé), `init`, puis les méthodes par thème dans le sens de la vie d'un store, l'appelé sous
  l'appelant (l'ouverture avec ses aides dans l'ordre où `init` les appelle, la lecture et la validation, les mises à jour, les callbacks, les
  policies, la persistance, la fin de vie, les aides communes), et les types imbriqués en dernier ; `meta` se pose dans `init`, après les chemins
  dont il dépend, parce qu'un initialiseur Kotlin lit une propriété déclarée plus bas avant qu'elle n'existe ; `Store.kt` rangé dans le même
  ordre ; `StoreConfig` dans `core\StoreConfig.kt` ; `dataOrigin`, `lastSavedData` et `hasSavedAtLeastOnce` sans underscore, `_data` le garde en
  champ de secours de `data`, le `@Suppress` retiré ; `transactionInternal` devenu `runTransaction`, par symétrie avec `runUpdate` ; la KDoc de la
  classe dit ses quatre invariants et renvoie à `architecture.md`. Un premier commit avait suivi la convention Kotlin officielle (le companion en
  dernier, les types imbriqués près de leurs usages) ; le second la remplace par l'ordre de Java, écrit dans `style-de-code.md` le même jour.
  Aucune ligne de logique ne change : la suite entière et le banc en composite en font la preuve.

## 5. P3, la vision

- [ ] **C-17 : versionnage et migration des fichiers** (L ; TODO-3). Un fichier de config porte la version de son schéma ; au chargement, la lib
  migre ce qu'elle sait migrer et refuse le reste avec un message net. `StoreMeta.version` est un début de piste (C-13) ; la conception (où vit la
  version, qui écrit les migrations) mérite sa propre séance. Le curseur 1, le nom de la clé, est à trancher tôt, un consommateur veut l'écrire dès
  maintenant ; proposition du 2026-09-28 (AVIS) : `schema-version`, parce qu'un identifiant Kotlin ne peut pas porter de trait d'union, donc aucune
  collision possible avec une propriété sans `@SerialName`.
- [x] **C-18 : la distribution Minecraft** (M/L ; LECTURE). Comment un mod embarque Storify : dépendance externe publiée, jar-in-jar, ou shading ;
  l'articulation avec fabric-language-kotlin (qui fournit stdlib et kotlinx.serialization au runtime) ; et la publication sur Repsy à mettre en
  place (décidée le 2026-09-13) : circuit `maven-publish` remis en état, identifiants par la chaîne de secrets (BWS, `secrets-et-acces.md` de
  The Human Readme), jamais en clair ; la déprécation Gradle 10 vue dans le build s'élucidera ici si elle vient de maven-publish. Le banc a
  réservé ce chantier dès sa naissance (section 1 de son README). **Fait le 2026-09-16.** La publication : le circuit Repsy réécrit sur la
  chaîne de secrets (le jeton `REPSY_MAVEN_TOKEN` arrive en variable d'environnement par `dev-secrets.ps1 -Apply`, le dépôt n'est configuré que si
  elle est présente : plus de fichier de propriétés ni d'exec bws dans le build ; l'ancien circuit lisait un `S:/18/global.properties` mort) ;
  version `0.1.0-SNAPSHOT` (l'ancienne `0.0.1-SNAPSHOT-02` ne finissait pas par -SNAPSHOT : une release au sens Maven, non republiable) ; POM
  nommé et décrit. La distribution : jar-in-jar par `include`. Le fait décisif, établi en fouillant le jar de fabric-language-kotlin 1.14.1 :
  il fournit stdlib, reflect 2.4.20, kotlinx-serialization core/json/cbor 1.11.0 et kotlinx-datetime 0.8.0, les versions exactes du catalogue,
  et Minecraft fournit slf4j ; un mod n'embarque donc que storify, tomlkt et json5, avec le plancher `fabric-language-kotlin >= 1.14.1` en
  depends (recette au README, section 4). Le shading relocaté écarté en voie par défaut : kotlinx et reflect sont la langue commune avec les
  data classes des consommateurs, les relocater couperait la lib de ses mods ; la variante « Storify seule relocatée, kotlinx intouchée »
  consignée en option avancée. Le banc reste en composite par défaut ; sa propriété `storify_source=repsy` retire le composite, résout la lib
  depuis Repsy et l'embarque selon la recette. **Preuve en conditions réelles faite le 2026-09-23** : le jar autonome (storify, tomlkt et json5
  imbriqués, 706 Ko) déployé dans le serveur pur avec Fabric API et FLK 1.14.1, le serveur lancé sans Gradle sur le lanceur Fabric 26.2 :
  Fabric Loader 0.19.5 liste les trois jars imbriqués, la config TOML et les homes JSON se créent, le `stop` ferme les stores proprement (log du
  banc, `[Storify] Store ... closed`). La déprécation Gradle 10 élucidée et corrigée : elle ne venait pas de maven-publish mais du rename de
  la licence dans `jar`, qui touchait `project` à l'exécution (le nom se capture désormais à la configuration). En chasse, l'hygiène des
  tests (commit séparé) : cinq `!!` superflus de MetaSidecarTest tombés, l'`Instant` des fixtures déménagé vers `kotlin.time`.
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
- [ ] **C-38 : les formats à la carte** (S puis L ; AVIS). Les trois formats sont toujours embarqués : `StoreFormats` instancie `JsonFormat`,
  `TomlFormat` et `Json5Format` au premier contact, la factory les référence, tomlkt et json5 sont des dépendances `implementation`. Mesuré le
  2026-09-28 sur les jars : storify 202 Ko, tomlkt 251 Ko, json5 181 Ko. D'abord un registre paresseux (des fabriques au lieu d'instances, l'annotation
  résolue par le registre) : un consommateur JSON seul exclut tomlkt et json5 de sa dépendance sans `NoClassDefFoundError` tant qu'il ne demande pas ces
  formats ; ensuite, si un second consommateur le réclame, le découpage `storify-core` plus un artefact par format. **En attente, décision du
  2026-09-29** : le gain est la taille du jar d'un mod JSON seul (432 Ko sur des jars de plusieurs Mo, avec un loader qui déduplique les jars
  imbriqués), personne ne l'a demandé, et la preuve coûte plus que le code (un classloader filtré ou une configuration Gradle à part, une seconde
  recette au README, une panne de plus à documenter) pour une demi-mesure : le POM déclarerait toujours tomlkt et json5. S'ouvre le jour où un
  consommateur a besoin de la taille, ou d'un consommateur Maven hors Minecraft, et se fait alors en une fois par la solution complète,
  `storify-core` plus un artefact par format ; le registre paresseux seul, une demi-mesure, est abandonné (décision confirmée le 2026-09-29 au soir).

## 6. La méthode, chantier par chantier

1. Relire le constat, poser le périmètre exact du chantier, et répondre aux trois questions : utile, pour qui ; vaut-elle la peine, au coût, au
   risque et face à l'alternative ; propre et cohérente avec le reste de la lib.
2. Proposer le design (aperçu du code ou du geste), valider avant d'écrire.
3. Appliquer, build et tests verts côté lib, build vert côté banc.
4. Si le comportement à l'exécution est touché : un passage au banc, en jeu, avec le log pour témoin.
5. Cocher la case ici, avec la date, et raconter au journal du classeur.

Un chantier à la fois ; un chantier qui en révèle un autre l'ajoute à la liste au lieu de s'étendre en silence.

---

*Dernière vérification : 2026-10-01, C-28 à C-34, C-36, C-37, C-39 à C-52 cochés, C-35 et C-38 en attente ; les constats du banc à jour au 2026-09-23.*
