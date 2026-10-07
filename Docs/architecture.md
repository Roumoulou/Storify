# Architecture de Storify

> *Type : doc technique.*
> *Modèle : les règles générales de documents-markdown.md (The Human Readme).*

Vaut pour la lib telle qu'elle est dans le code, mécanisme par mécanisme, défauts compris : ce qui doit changer est listé dans `chantiers.md`,
et seulement signalé ici, en place.

## 1. Vue d'ensemble

Un store est l'attelage d'une data class sérialisable (kotlinx.serialization), ou de tout type dont le sérialiseur est donné (C-54), d'un fichier (JSON, TOML
ou JSON5) et d'un `BaseStore<DATA>` qui orchestre tout le reste. Le chemin type :

```
StoreFactory.create*<DATA>(...), ou createFromProvider(serializer, ...) sans classe (C-54)
    └─> résolution : paramètre explicite > annotation de DATA (aucune par createFromProvider) > défaut, objet par objet
    └─> BaseStore.init
            1. initData            : fichier existant décodé, sinon données par défaut (sans écrire)
            2. initUpdatePolicies  : scan récursif des annotations @StoreUpdatePolicy
            3. initValidation      : validator exécuté, ValidationException si échec
            4. persistInitialData  : le fichier initial des données par défaut, validation passée
            5. initAutoSave        : scheduler périodique (si withAutoSave)
            6. initShutdownHook    : sauvegarde à l'arrêt de la JVM
    └─> vie du store : data (read lock), set/mutate/transaction (write lock), callbacks (hors lock),
                       saveImmediate / auto-save / reloadFromFile
    └─> close() : tick annulé, scheduler arrêté, hook désarmé, sauvegarde d'adieu (SaveTrigger.CLOSE)
```

Les packages :

| Package | Contenu |
|---|---|
| `fr.moulou.storify` | Le modèle public : annotations, `UpdatePolicy`, `Operation`, `CapturedValue`, `StoreMeta`, `Defaultable`, formats |
| `fr.moulou.storify.core` | Le moteur : `Store`, `BaseStore`, `StoreConfig`, la factory, les extensions `set`/`mutate`/`transaction` |
| `fr.moulou.storify.validation` | `Validator`, `ValidationContext`, `ValidationResult`, `ValidationError`, `ValidationException`, l'enrichisseur de lignes JSON |
| `fr.moulou.storify.utils` | Le copieur profond (`DeepCopier`, l'arbre JSON), l'écrivain atomique (`AtomicFiles`), le registre des formats, le formatage des dates |

## 2. La data class et ses annotations

La racine d'un store est une data class `@Serializable` dont les propriétés sont des `var` (les `val` se sérialisent mais ne se mettent pas à jour
par l'API typée). Six annotations la complètent, toutes facultatives dès lors que l'appel à la factory fournit l'information :

| Annotation | Porte sur | Rôle |
|---|---|---|
| `@StorePath(path)` | la classe | Le chemin du fichier, pour les variantes de factory sans path explicite |
| `@StoreFileFormat(type)` | la classe | Le format (`JSON`, `TOML` ou `JSON5`) ; sinon, résolution par l'extension du chemin |
| `@StoreConfiguration(...)` | la classe | Les options : `withValidation` (défaut `true`), `withAutoSave` (`true`), `withMeta` (`false`), `useDeepCopy` (`true`), `autoSaveIntervalMs` (300 000), `defaultUpdatePolicy` (`SKIP`), `validateOnUpdate` (`false`), `readOnly` (`false`), `withShutdownHook` (`true`), `createIfMissing` (`true`), `loggerName` (`Storify`) |
| `@StoreValidator(classe)` | la classe | Le `Validator` instancié par réflexion (constructeur sans argument) |
| `@StoreDefaultResource(path)` | la classe | La ressource du classpath copiée au premier lancement (`createFromResource`) |
| `@StoreUpdatePolicy(policy)` | une propriété | La politique de capture de cette propriété, où qu'elle soit dans l'arborescence |

Le défaut de `defaultUpdatePolicy` est `SKIP` (C-22) : le store type est une config que personne n'observe, et le défaut ne doit rien coûter.
Sans policy explicite, les callbacks se taisent, la persistance restant garantie (C-03), et en garde-fou l'enregistrement d'un callback d'update
voué au silence émet un avertissement au log. `withValidation` vaut `true` des deux côtés, annotation et `StoreConfig()` (C-24) : sans validator
elle ne coûte rien, poser un validator c'est vouloir qu'il tourne, et `false` reste l'échappatoire explicite.

## 3. La factory et la résolution

La factory publique est `StoreFactory`. Ses huit `create*` sont `inline` pour une seule raison : matérialiser `DATA::class` et le sérialiseur
de DATA (`serializer<DATA>()`, C-09) à leur site réifié. Tout le reste vit dans la lib, derrière quatre points d'entrée ordinaires, un par
source de données initiales (`openFromCompanion`, `openFromConstructor`, `openFromDefaultable`, `openFromResource`, C-46) : la lecture des
cinq annotations, la résolution dans l'ordre paramètre explicite, puis annotation, puis repli (`StoreFormats.getFormatForStringPath` pour le
format, `StoreConfig()` pour la config), le fournisseur des données initiales, et la construction du store, qui reçoit le sérialiseur et
appelle le format en polymorphe. La résolution choisit chaque objet entier, jamais champ par champ : une `StoreConfig` explicite remplace
`@StoreConfiguration` en bloc, et ses champs non donnés valent les défauts de la classe, pas ceux de l'annotation. Le jar d'un mod ne contient
que l'appel au point d'entrée, ce que `FactoryCallerBytecodeTest` vérifie dans le fichier `.class` d'un appelant témoin : un attribut ajouté à
`@StoreConfiguration` ou un format de plus dans l'enum sont pris en compte sans recompiler le mod, et le constructeur de `BaseStore` n'entre
pas dans son bytecode.

La neuvième fabrique, `createFromProvider(serializer, stringPath, ...) { provider }`, n'est pas `inline` : le sérialiseur et le fournisseur lui sont
donnés en valeurs, pour un fichier dont la forme naît d'une valeur et non d'une classe (C-54). Elle ne lit aucune annotation de classe, n'ayant pas de
classe à interroger, `@StoreUpdatePolicy` restant lue par le store sur la classe réelle des données, et passe par la même résolution, l'explicite puis
le repli : `open` se scinde en la lecture des annotations, `resolve`, le repli commun aux neuf, et `build`, la construction. DATA n'a plus à être une
classe `@Serializable` : une `Map` ou une classe sans annotation est une racine légale, qui se met à jour par `transaction` quand elle n'a pas de
propriété ; sous `SNAPSHOT`, `set` et `mutate` matérialisent `serializer<VALUE>()` chez l'appelant, et une propriété dont le type n'a pas de sérialiseur
échoue au premier update observé, mémoire intacte.

Chaque variante ne diffère que par sa source de données initiales :

| Variante | Données initiales quand le fichier n'existe pas |
|---|---|
| `create` | Le companion object de DATA, qui doit implémenter `Defaultable<DATA>` |
| `createFromConstructor` | `DATA::class.createInstance()` : le constructeur sans argument (tous les champs ont un défaut) |
| `createFromDefaultable<DATA, D>` | Une classe `Defaultable` externe, instanciée par constructeur sans argument |
| `createFromResource` | La ressource du classpath copiée telle quelle vers le fichier cible, par l'écrivain atomique, puis décodée (C-40) |
| `createFromProvider(serializer, ...)` | Le fournisseur donné en valeur, à côté du sérialiseur : le code de l'appelant, sans classe interrogée (C-54) |

## 4. Le cycle de vie de BaseStore

Le chemin reçu est d'abord normalisé (`toAbsolutePath().normalize()`, C-12) : logs, erreurs, sidecar et temporaires parlent tous le même chemin
net. L'initialisation enchaîne ensuite six étapes, dans l'ordre du bloc `init` :

1. **initData** : si le fichier existe, il est décodé (`_dataOrigin = FILE` ; une panne de lecture ou de parseur devient `StoreDecodeException`,
   C-33) ; sinon les données par défaut sont fabriquées, sans rien écrire (`DEFAULT`) :
   le fichier initial n'arrive qu'après la validation (C-06), des défauts invalides ne touchent jamais le disque. Exception voulue : la copie
   d'une ressource embarquée (`createFromResource`, origine `RESOURCE`) est posée à ce stade, à l'octet et par l'écrivain atomique (C-40) ; elle
   reste sur disque même invalide, éditable, erreurs pointées à la ligne.
2. **initUpdatePolicies** : parcours récursif de `DATA::class` par réflexion (`memberProperties`), avec un ensemble `visited` contre les cycles et
   une garde qui ignore les classes `kotlin.*` et `java.*` ; chaque `@StoreUpdatePolicy` rencontrée entre dans la map `updatePolicies`.
3. **initValidation** : si `withValidation`, le validator tourne sur les données chargées ; en cas d'échec, les erreurs sont enrichies des
   numéros de ligne JSON dès qu'un fichier existe (chargé, ou copié d'une ressource), puis une `ValidationException` est levée, dont le
   message nomme l'origine des données, là où est le remède : le fichier, la copie de la ressource, ou les défauts du code (C-40). Le store
   ne se construit pas.
4. **persistInitialData** : les données nées par défaut (`DEFAULT`) écrivent enfin leur fichier initial, la validation étant passée (C-06),
   sauf `createIfMissing = false`, où rien n'est écrit (C-30). La copie d'une ressource n'est jamais réécrite : le fichier du premier
   lancement est la ressource du jar, commentaires compris (C-40).
5. **initAutoSave** : si `withAutoSave` et hors lecture seule (C-30), un scheduler (`scheduleAtFixedRate`) sauvegarde à chaque tick où le drapeau dirty est
   levé, sauf pause (`pauseAutoSave`) ; au banc, les ticks tiennent l'intervalle à la seconde près. Le drapeau lui-même est posé par le pipeline
   d'update (`markDirty`, toutes policies confondues, C-03). Le thread du scheduler n'est **pas** daemon : c'est `close()` qui l'arrête (C-01) ;
   un store jamais fermé retient la JVM.
6. **initShutdownHook** : si `withShutdownHook` et hors lecture seule (C-30), un hook `Runtime.addShutdownHook` (gardé en champ) annule le tick
   en cours et, si le store est dirty, sauvegarde (C-23 : un store resté propre ne réécrit rien à l'extinction) : le filet anti-crash des stores
   encore ouverts. `close()` le désarme (C-01) : un store fermé a déjà fait sa sauvegarde d'adieu, et son hook ne doit pas ressusciter des
   données périmées par-dessus une édition faite entre-temps (constat n° 3 du banc).

La fin de vie (C-01) : `close()`, idempotent, annule le tick, arrête le planificateur (`awaitTermination` 5 s : un tick en vol se termine avant la
suite), désarme le hook, puis fait la sauvegarde d'adieu si le store est dirty (`SaveTrigger.CLOSE`). Un store fermé reste lisible, refuse toute
écriture (`IllegalStateException`), et ses interrupteurs d'auto-save deviennent inertes. Les stores sont `AutoCloseable` : `use { }` fonctionne.

Le mode lecture seule (C-30, `readOnly`) : le store lit, valide et relit, et refuse toute écriture par `checkWritable()` en tête du pipeline
d'update, de la transaction et de `saveImmediate` (`IllegalStateException`, comme sur un store fermé) ; ni planificateur ni hook, les interrupteurs
d'auto-save inertes, le sidecar meta lu mais jamais écrit ; sa seule écriture possible est le fichier initial, si `createIfMissing`, ou la
copie de sa ressource embarquée. La garantie est à l'exécution, pas à la compilation. `Store.isReadOnly` le dit au consommateur. Sans hook
armé (`withShutdownHook = false`, ou lecture seule), `close()` fait toujours sa sauvegarde d'adieu quand le store est dirty.

## 5. Les mises à jour typées

L'API publique est un jeu d'extensions sur `BaseStore` :

| Extension | Geste |
|---|---|
| `set(prop, value)` | Remplacer la valeur d'une propriété de la racine |
| `setIn(prop, value) { receiver }` | Pareil, sur un objet imbriqué désigné par une lambda de navigation |
| `mutate(prop) { block }` | Modifier en place un objet mutable de la racine (une map, une liste...) |
| `mutateIn(prop, { receiver }) { block }` | Pareil, en navigation |
| `transaction { block }` | Modifier la racine entière, tout ou rien |

Tout converge vers `runUpdate`, le pipeline central, une fonction ordinaire de `BaseStore` (C-45). `set`, `setIn`, `mutate` et `mutateIn` sont
`inline` pour une seule raison : matérialiser le sérialiseur de la valeur à leur site réifié, par une lambda que le pipeline n'appelle que si
une copie profonde est due. Le code d'un appelant, donc le jar d'un mod, ne contient que l'appel à l'un des deux points d'entrée (`setValue`,
`mutateValue`), ce que `CallerBytecodeTest` vérifie dans le fichier `.class` d'un appelant témoin : un correctif du pipeline atteint un mod
sans recompilation, et aucun autre membre interne de `BaseStore` n'est lié à son bytecode. `transaction`, qui n'a rien à matérialiser, n'est
pas `inline`. Le pipeline, sous le write lock :

1. lecture de la policy de la propriété (`updatePolicies`, sinon `config.defaultUpdatePolicy`) ;
2. `SKIP`, ou aucun auditeur d'update (ni global ni ciblé sur la propriété, C-25) : la mutation s'applique, le dirty est posé, et rien n'est
   construit : aucune capture, aucun callback ;
3. sinon, en `SNAPSHOT` (et `useDeepCopy`), l'avant est figé **avant** la mutation : une copie profonde pour une valeur mutable, la valeur
   elle-même quand elle est immuable (`null`, primitive, `Char`, `String`, enum), ce qui se juge sur la valeur et non sur le type ;
4. la mutation s'applique sur l'objet vivant ;
5. l'après est capturé, figé de la même façon en `SNAPSHOT`, en `Shallow` sinon. Sans copie, l'avant d'un `set` est l'ancienne valeur, qu'il
   a remplacée (`Shallow`), et celui d'une mutation en place n'existe pas (`Unavailable`). L'`Operation` naît ;
6. hors du lock, le dispatch aux callbacks globaux puis aux callbacks ciblés de la propriété.

Sous l'opt-in `validateOnUpdate` (chapitre 8), une étape s'intercale après la mutation : la racine est validée, et un échec restaure la copie de
sécurité puis dispatche une `ValidationFailedOperation` au lieu de l'opération normale.

`transaction` suit un autre chemin : copie profonde de la racine entière en secours, exécution du bloc, et en cas d'exception restauration du
secours (rollback) avant de relancer l'exception. Sans `useDeepCopy`, pas de secours : la transaction perd son filet.

Le ciblage par propriété (C-10) : `registerOnUpdateOn` est typé sur la racine (`KProperty1<DATA, *>`), l'erreur de store se refuse donc à la
compilation ; `registerOnUpdateOnIn` est le miroir de `setIn` : sa navigation ancre la propriété imbriquée au store à la compilation, puis elle
est réévaluée à chaque notification et comparée par identité au receiver de l'update : seul l'exemplaire visé est notifié, l'écouteur survit aux
reloads, et une navigation qui échoue vaut « ne matche pas ». Le lien entre update et callback repose sur l'égalité des références de propriété
(`Data::champ` venu de deux sites d'appel désigne la même clé). Les policies, elles, restent arborescentes (`KProperty1<*, *>`) :
`setUpdatePolicy` signale au log une propriété étrangère à l'arbre de DATA, l'arbre réel étant collecté par le scan des annotations.

## 6. Policies et captures

| Policy | Copie profonde des valeurs | Callbacks |
|---|---|---|
| `SNAPSHOT` | oui (avant et après, sauf valeur immuable) | oui |
| `SHALLOW` | non (références) | oui |
| `SKIP` | non | non |

Le drapeau dirty, lui, est posé pour toutes les policies (C-03) : la policy choisit ce qu'on observe, jamais ce qui est persisté. Le choix
pratique, chiffré par `DeepCopyBenchmark` (0,6 µs pour un petit objet, 57 µs pour 200 records imbriqués, par copie) : `SNAPSHOT` pour observer
avec des captures figées et sûres, `SHALLOW` pour observer sans copies (avant indisponible sur les mutations en place, après vivant), `SKIP` pour
le silence des points chauds. Ces coûts ne se paient que devant public (C-25) : sans aucun callback d'update enregistré, le pipeline
court-circuite captures et opération, quelle que soit la policy (la transaction garde toujours son secours de rollback, lui).

Les callbacks reçoivent les valeurs sous forme de `CapturedValue` : `DeepCopy` (copie fiable, à ne pas muter), `Shallow` (lecture au moment de la
capture, fiable pour les immuables seulement), `Initial` (la toute première donnée, au premier save), `Unavailable` (rien à montrer).

## 7. La persistance

Quatre déclencheurs, portés par `SaveTrigger` : `IMMEDIATE` (`saveImmediate()`), `AUTO_SAVE` (le tick), `SHUTDOWN` (le hook JVM, si le store est
dirty) et `CLOSE` (la sauvegarde d'adieu de `close()`, si le store est dirty). Le drapeau dirty se remet à zéro dans `save()`, après un encodage
réussi, quel que soit le déclencheur. La sauvegarde s'exécute sous le **read** lock (les lecteurs passent, les écrivains attendent la fin de
l'encodage) et écrit le sidecar meta s'il est actif. Devant un auditeur de save seulement (C-41), elle met ensuite à jour le snapshot
`_lastSavedData` (qui nourrit le `old` des callbacks de save), puis notifie hors lock. Ce snapshot naît à l'enregistrement du premier callback
de save, sur un store sans modification en attente : enregistré sur un store déjà modifié, le callback reçoit `Unavailable` en `old` à son
premier save. Un store que personne n'écoute ne copie donc sa racine ni à l'ouverture ni au save.

L'écriture est atomique (C-02 ; `AtomicFiles.write` est public pour tout fichier écrit hors store, comme `encodeToPathAtomically` sur les
formats, C-34) : chaque sauvegarde encode vers un fichier temporaire unique et voisin (`<fichier>.<8 hex>.tmp`), force le flush disque
(`FileChannel.force`), puis bascule par déplacement atomique (`ATOMIC_MOVE`, repli non atomique loggué si le système de fichiers ne sait pas
faire). La cible est donc toujours une version entière. Un verrou d'IO dédié sérialise les sauvegardes d'un même store, `saveImmediate` et le
tick n'encodent jamais en même temps vers le même fichier ; les temporaires orphelins d'un crash passé sont balayés à l'ouverture (au seul motif
`<fichier>.<8 hex>.tmp`, pour le fichier et son sidecar, jamais un temporaire étranger, C-28), et le fichier initial, la copie d'une ressource
embarquée (C-40) comme le sidecar meta passent par le même chemin. Un format préservant (`PreservingStoreFormat`, C-26) reçoit en plus le texte
actuel de la cible au moment d'encoder vers le temporaire : il ne réécrit que ce qui change. Quant à `reloadFromFile()` : il décode, revalide
par défaut (C-05, la mémoire reste intacte en échec), puis remplace la racine sous write lock et notifie les callbacks de reload, avec des
captures copiées (les références nues sans `useDeepCopy`), construites seulement devant public (C-29). Le fichier gagne (C-47) : une
modification en mémoire non sauvegardée est écartée et dite au log en `warn`, le drapeau dirty retombe, la mémoire étant le fichier, et devant
un auditeur de save la référence du prochain `old` devient la racine rechargée.

## 8. La validation

Le contrat est `Validator<T>` : `validate(data, ctx)` accumule des erreurs dans un `ValidationContext` plutôt que de lever à la première.
Le contexte offre `check(condition, field, message, rejectedValue)`, `addError`, `addObjectError`, et la composition : `validateNested` (objet
imbriqué, chemin `parent.champ`) et `validateEach` (collections, chemin `champ[index]`). Les erreurs (`ValidationError`) portent le chemin
complet, la classe, le message, la valeur rejetée et, quand il est connu, le numéro de ligne du fichier.

L'enrichisseur (`ValidationErrorEnricher`, public, C-32) retrouve ce numéro de ligne par le localisateur du format
(`StoreFormat.lineLocator()` : `JsonLineLocator` pour JSON et JSON5, aucun pour TOML). Le chemin d'une erreur suit une grammaire (`ErrorPath`) :
`a.b` pour une propriété, `a[3]` pour un index, `a[steve]` ou `a["steve"]` pour une clé de map, les points permis entre crochets ; le localisateur
parcourt le fichier ligne à ligne en suivant la profondeur des accolades et des crochets, hors chaînes et hors commentaires, reconnaît une clé sous
ses trois graphies (`"clé"`, `'clé'`, `clé` nue) et compte les éléments d'un tableau, un par ligne. Un segment ne se cherche que dans son parent
(C-53) : quand le fichier n'écrit pas le chemin entier, la ligne rendue est celle de son plus proche ancêtre écrit, au mieux, et `null` si rien ne
s'en retrouve. C'est le cas d'une clé omise qui a pris le défaut de sa data class (la ligne est celle de l'objet qui devrait la porter), d'un index
au-delà de la fin de son tableau, et d'une valeur qui tient sur la ligne de sa clé (un tableau en ligne). Limites assumées : une clé ou un élément
par ligne, ce qui s'en écarte (plusieurs éléments sur une ligne, un objet ouvert sur la ligne de sa clé de tableau) rendant la ligne de l'ancêtre,
ou celle d'un élément voisin dans un tableau qui mêle les deux styles ; un chemin illisible laisse son erreur sans ligne, les autres gardent la
leur.

La validation joue à quatre moments (C-05, C-32) : au chargement initial, au `reloadFromFile` (revalidation par défaut : l'objet relu est validé
AVANT de remplacer la mémoire, qui reste intacte en échec ; `validate = false` pour sauter), à la demande sur la mémoire via `validateNow()` (sans
lignes : une ligne ne vaut que si mémoire et fichier coïncident) et sur le fichier du disque via `validateFile()`, la mémoire intacte. Hors de tout
store, `StoreFormat.validateFile(path, deserializer, validator)` décode, valide et enrichit un fichier édité à la main.

Les fautes du fichier ont une famille (C-33) : `StorifyException`, ancêtre de `ValidationException` (bien formé mais invalide) et de
`StoreDecodeException` (illisible ou mal formé : le chemin, le format, la ligne quand elle se lit dans le message du parseur, l'offset de kotlinx
et l'index de json5 convertis en ligne, le `(L2)` de tomlkt tel quel, celle que porte la `DuplicateKeyException` d'une clé en double, C-50, et la
cause conservée). Tout décodage fait pour un consommateur passe par
`StoreFormat.decodeFile` (ouverture, rechargement, `validateFile`, sidecar meta, ressource embarquée), le contrat brut `decodeFromPath` restant
intact pour les formats. Les fautes du code, écrire sur un store fermé ou en lecture seule, restent des `IllegalStateException`. Une
`StorifyException` levée à l'initialisation d'un mod n'est rattrapée par personne : en solo Minecraft, une `ValidationException` au chargement
crashe le client entier (« Exception in server tick loop », constat n° 5 du banc) ; le `catch` est le geste du consommateur.
S'y ajoute l'opt-in `validateOnUpdate` (défaut `false`, **non recommandé**) : chaque update copie la racine, mute, valide, et en échec restaure
puis émet `ValidationFailedOperation` vers les callbacks (les transactions rendent `TransactionOperation(success = false)`) ; la valeur invalide
n'entre jamais, au prix d'une copie de racine et d'un validator sous write lock à chaque geste. Il exige `useDeepCopy`, et le bon réflexe reste
les contrôles métier avant de muter.

## 9. Les formats

`StoreFormat` est le vrai point d'extension de la lib (C-09) : le contrat porte `fileExtension()` et l'encode/decode générique à sérialiseur
explicite (`decodeFromPath(deserializer, path)`, `encodeToPath(serializer, data, path)`). Le sérialiseur est matérialisé aux sites réifiés (la
factory pour les stores, un sucre `inline reified` pour les appels directs : `format.decodeFromPath<Homes>(path)`) puis transporté par l'appel
polymorphe : un format tiers implémente l'interface et traverse la factory sans qu'elle le connaisse. La réification ne peut pas être le
mécanisme du dispatch (elle exige des méthodes inline, donc non virtuelles) ; elle est celui de la matérialisation. Le contrat porte aussi
`deepCopier()` (C-29), le copieur profond des stores du format (chapitre 10) : l'arbre JSON, sur le `Json` du format pour JSON et JSON5, au module du
`Toml` pour TOML, celui par défaut pour un format tiers. Les trois formats fournis tolèrent un BOM UTF-8 en tête de fichier à la lecture
(`withoutUtf8Bom`, C-31 ; un format tiers s'en charge lui-même) et n'en écrivent jamais. Les réglages en place :

| Format | Réglages | Particularités |
|---|---|---|
| `JsonFormat` | prettyPrint, encodeDefaults, allowStructuredMapKeys, allowSpecialFloatingPointValues ; `lenient()` ajoute isLenient et allowComments | Le JSON standard, strict à la lecture (C-36), une clé en double refusée (C-50) ; crée les dossiers parents à l'écriture ; écrit par un flux tamponné (C-48), le lecteur strict lit le texte entier, le tolérant par le flux |
| `TomlFormat` | ignoreUnknownKeys | Crée les dossiers parents à l'écriture (C-04) ; une clé en double refusée par tomlkt, avec sa ligne |
| `Json5Format` | sortie indentée quatre espaces, apostrophes simples, clés nues ; pont `Json { encodeDefaults, allowSpecialFloatingPointValues }` | Crée les dossiers parents ; sauvegarde préservante (C-26) : seules les valeurs changées se réécrivent ; une clé en double refusée, par l'AST de la brique (C-50) |

`JsonFormat` lit le JSON standard et rien d'autre (C-36) : un commentaire, une clé ou une chaîne sans guillemets échouent au décodage comme une
virgule finale ou une clé inconnue, et le store lève `StoreDecodeException` avec la ligne. `NaN` et les infinis restent tolérés, à l'écriture comme
à la lecture, parce qu'un refus ferait échouer chaque sauvegarde du store loin du code qui a produit la valeur, quand un `NaN` écrit se relit ;
`allowStructuredMapKeys` ne tolère aucune syntaxe (une map à clés textuelles écrite en tableau est refusée), il permet une map à clés structurées.
`JsonFormat.lenient()` rend le lecteur tolérant, et le constructeur accepte tout `Json`.

Une clé déclarée deux fois dans le même objet est refusée dans les trois formats (C-50), par `StoreDecodeException` avec la ligne de la seconde
occurrence, sous le même `catch` que les autres fautes du fichier ; sans cela, kotlinx comme la brique json5 gardent la dernière valeur, avant le
code du consommateur, qui ne peut rien voir. En JSON, c'est l'affaire du lecteur strict (un `Json` ni `isLenient` ni `allowComments`) :
`decodeFromPath` lit le texte entier, le décode (un texte mal formé est diagnostiqué par kotlinx, avant tout), puis le passe à
`JsonDuplicateKeys` (`validation\`), qui empile les objets ouverts, garde les clés de chacun dans un ensemble et décode les chaînes de leurs
échappements, si bien que `"ab"` et `"ab"` sont la même clé ; un doublon lève `DuplicateKeyException`, une `SerializationException` comme
celles de kotlinx, que `decodeFile` enveloppe et dont `StoreDecodeException` lit la ligne. Le lecteur tolérant, comme tout `Json` d'un
consommateur qui admet les commentaires ou les clés nues, n'y est pas soumis et garde la dernière valeur ; un format tiers n'a rien à savoir.
Coût mesuré : 1,5 ms par 794 Ko, le chargement de 5 000 joueurs passant de 1,5 à 3,7 ms environ, rien sur une config. En JSON5, le texte est parsé
en document (`parseToDocument`), dont l'AST garde les deux membres, noms décodés et plages source, et parcouru après le contrôle de syntaxe, avant
la conversion en arbre ; en TOML, tomlkt refuse de lui-même, par sa spécification.

`Json5Format` (C-21) suit la conception de sa brique `li.songe:json5` : le texte est du JSON5 de bout en bout, le `Json` de kotlinx ne sert que
de moteur d'arbre (`JsonElement`) sans jamais produire de texte ; l'API de la brique étant entièrement texte, le fichier se lit entier, le
créneau étant la config et non la donnée de masse.

La règle des flottants spéciaux vaut pour les trois formats (C-42) : un `NaN` ou un infini présent en mémoire se sauve et se relit, et la même
valeur écrite à la main dans le fichier se charge. JSON les écrit `NaN`, `Infinity` et `-Infinity`, JSON5 de même (ils sont dans sa grammaire,
`+Infinity` compris à la lecture), TOML `nan`, `inf` et `-inf`. Un `Json` passé par le consommateur au constructeur de `JsonFormat` ou de
`Json5Format` est pris tel quel, son refus éventuel compris.

La sauvegarde de `Json5Format` est préservante (C-26) : le format déclare la capacité optionnelle `PreservingStoreFormat`, que `BaseStore` détecte au save en
fournissant le texte actuel de la cible (lu sous le verrou d'IO, pendant l'encodage vers le temporaire atomique ; le contrat `StoreFormat`
reste intact). L'arbre encodé est différencié contre le document parsé (`parseToDocument`, l'AST aux plages source exactes et aux commentaires
attachés), et seules les retouches s'appliquent (`set`, `putProperty`, `remove`) : les valeurs changées se réécrivent, les clés nouvelles
s'ajoutent, les disparues s'en vont avec leurs commentaires, tout le reste du fichier reste au caractère près, et un save sans changement est
identique à l'octet. Décisions v1 : un tableau modifié se remplace entier (un diff par index apparierait mal commentaires et éléments
déplacés), et un fichier cible absent ou invalide vaut encode à neuf.

`StoreFormats` tient le registre extension vers format (`json`, `toml`, `json5`), interrogé quand aucun format n'est donné ; `registerFormat` y
ajoute un format tiers, résolu par l'extension du chemin comme les formats fournis, et le registre est thread-safe (C-46). Une extension inconnue
est refusée net (`IllegalArgumentException` qui nomme les extensions enregistrées), sans repli sur JSON (C-09). Et l'écrivain atomique garantit
les dossiers parents avant chaque écriture, pour tout format, fourni ou tiers (C-04, C-09).

## 10. Le deep copy par arbre JSON

Les copies profondes (`utils\DeepCopier.kt`, C-29) sont un aller-retour de sérialisation par arbre `JsonElement` : `encodeToJsonElement` puis
`decodeFromJsonElement`, sans texte ni octets, sur un `Json` dérivé de celui du format (`JsonTreeCopier`), avec `encodeDefaults`,
`allowSpecialFloatingPointValues` et `allowStructuredMapKeys` forcés pour qu'une copie n'échoue jamais sur un `NaN` ou une clé de map structurée.
C'est ce qui permet de copier n'importe quelle data class `@Serializable` sans imposer d'interface de clonage, sérialiseurs écrits pour le JSON
compris (un `decoder as JsonDecoder` y trouve son décodeur). Chaque store tient le copieur de son format
(`StoreFormat.deepCopier()`) et le respecte pour toutes ses copies : les captures du pipeline d'update, le secours des transactions, le snapshot
`_lastSavedData` du save et les captures du rechargement ; `useDeepCopy = false` les supprime toutes, et la transaction perd son filet.
Le coût se mesure avec `DeepCopyBenchmark` (dans les tests, CBOR en colonne de comparaison) : du même ordre que CBOR, un peu plus lent sur les
petits objets (0,6 contre 0,4 µs), plus rapide sur les gros (57 contre 74 µs pour 200 records), courbe en fonction de la taille des collections.
Le raccourci immuable du pipeline d'update (chapitre 5) évite ce coût pour les primitives, chaînes et enums, et aucune capture ne se prend sans
public : ni à l'update (C-25), ni au rechargement (C-29), ni au save (C-41). Seul le secours de rollback des transactions se prend toujours.

## 11. La concurrence

- Toutes les lectures et écritures de `_data` passent par un `ReentrantReadWriteLock` : `data` prend le read lock, les updates et le
  remplacement de racine le write lock, la sauvegarde le read lock.
- Les callbacks sont notifiés **hors** de tout lock : un callback peut relire le store sans interblocage ; les valeurs qu'il reçoit sont des
  captures, pas des références sous verrou (sauf `Shallow` sur un mutable, à ses risques).
- L'enregistrement des callbacks est sûr à tout moment (C-08) : les conteneurs sont privés et thread-safe (`CopyOnWriteArrayList`,
  `ConcurrentHashMap`), un callback peut s'enregistrer pendant un dispatch. La map des policies (C-45) et le registre des formats (C-46)
  le sont aussi : `setUpdatePolicy` et `registerFormat` peuvent être appelés pendant que le pipeline d'update ou une factory les lisent.
- Le logging (C-11) : le logger n'appartient pas au contrat `Store`, c'est un champ privé fabriqué une fois par store, nommé `Storify` par
  défaut ou du nom que `StoreConfig.loggerName` lui donne (C-37), celui du mod pour que ses stores paraissent sous son journal ; tous les
  messages portent le préfixe `[Storify]`, les ticks parlent en debug, le cycle de vie en info, les échecs en warn, et l'écrivain atomique
  annonce son repli non atomique sous le logger du store (sous le sien hors store). La lib ne journalise jamais de données utilisateur : des
  chemins et des états seulement (politique posée au C-12, vérifiée sur la flotte des messages). Non garanti à ce jour : le sidecar meta se
  modifie sans verrou propre, et un encodage long sous read lock retarde tous les écrivains (mesuré sur 5 000 joueurs, 794 Ko : 6,7 ms par
  sauvegarde JSON, sur les flux tamponnés de C-48, dont 1 à 2 ms de flush disque).

## 12. Le sidecar meta

Avec `withMeta = true`, le store entretient `<fichier>.meta.json` : `createdAt` (à la création de l'objet), `lastModified` (entretenu à chaque
update par `touch()`, au format `yyyy-MM-dd HH:mm:ss:SSS` local, C-13), `version` (posée à 1, réservée au versionnage de schéma du
chantier C-17) et `custom` (le sac libre du consommateur ; le banc l'affiche en jeu, la lib n'y écrit jamais). Le fichier s'écrit au moment des
sauvegardes, toujours en JSON, quel que soit le format du store, comme son nom le promet (C-09).

## 13. Les dépendances, et pourquoi

| Dépendance | Rôle |
|---|---|
| `kotlinx-serialization-json` | Le format JSON, le pont d'arbre de JSON5 et le véhicule des copies profondes (chapitre 10) |
| `dev.eav.tomlkt:tomlkt` | Le format TOML |
| `li.songe:json5` | Le format JSON5 : le parse et l'écriture du texte ; le mapping passe par un pont `JsonElement` kotlinx |
| `kotlin-reflect` | Le scan des annotations, `createInstance`, `memberProperties` (factories et policies) |
| `kotlinx-datetime` | Les horodatages du sidecar meta |
| `slf4j-api` | Le logging (le binding est laissé au consommateur ; `slf4j-simple` en test) |

---

*Dernière vérification : 2026-10-07, C-54 porté aux chapitres 1 et 3, C-53 au chapitre 8 le 2026-10-05, C-50 aux chapitres 8 et 9 le 2026-10-01, le reste
relu en entier contre `src\main` le 2026-09-30 ; ce qui doit changer est ouvert dans `chantiers.md` (C-17, C-19, C-20, C-55 et C-56 ; C-35 et C-38 en
attente).*
