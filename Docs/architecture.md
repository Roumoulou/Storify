# Architecture de Storify

> *Type : doc technique.*
> *Modèle : les règles générales de documents-markdown.md (The Human Readme).*

Ce document décrit comment Storify est faite, mécanisme par mécanisme : le cycle de vie d'un store, les mises à jour typées, les politiques de
capture, la persistance, la validation, les formats, la concurrence et les dépendances. Il décrit l'état réel du code, défauts compris ; ce qui
doit changer est listé dans `chantiers.md`, ce document se contente de le signaler en place.

## 1. Vue d'ensemble

Un store est l'attelage d'une data class sérialisable (kotlinx.serialization), d'un fichier (JSON, TOML ou JSON5) et d'un `BaseStore<DATA>` qui orchestre
tout le reste. Le chemin type :

```
StoreFactory.create*<DATA>(...)
    └─> résolution : paramètres explicites > annotations de DATA > défauts
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
| `fr.moulou.storify.utils` | Le deep copy CBOR, le registre des formats, le formatage des dates |
| `fr.moulou.storify.serializers` | Sérialiseurs d'appoint (`JsonPrimitiveAsStringSerializer`) |

## 2. La data class et ses annotations

La racine d'un store est une data class `@Serializable` dont les propriétés sont des `var` (les `val` se sérialisent mais ne se mettent pas à jour
par l'API typée). Six annotations la complètent, toutes facultatives dès lors que l'appel à la factory fournit l'information :

| Annotation | Porte sur | Rôle |
|---|---|---|
| `@StorePath(path)` | la classe | Le chemin du fichier, pour les variantes de factory sans path explicite |
| `@StoreFileFormat(type)` | la classe | Le format (`JSON`, `TOML` ou `JSON5`) ; sinon, résolution par l'extension du chemin |
| `@StoreConfiguration(...)` | la classe | Les options : `withValidation` (défaut `true`), `withAutoSave` (`true`), `withMeta` (`false`), `useDeepCopy` (`true`), `autoSaveIntervalMs` (300 000), `defaultUpdatePolicy` (`SKIP`), `validateOnUpdate` (`false`) |
| `@StoreValidator(classe)` | la classe | Le `Validator` instancié par réflexion (constructeur sans argument) |
| `@StoreDefaultResource(path)` | la classe | La ressource du classpath copiée au premier lancement (`createFromResource`) |
| `@StoreUpdatePolicy(policy)` | une propriété | La politique de capture de cette propriété, où qu'elle soit dans l'arborescence |

Le défaut de `defaultUpdatePolicy` est `SKIP` : sans policy explicite, les callbacks se taisent, la persistance restant garantie (C-03). Tranché
au chantier C-22 : `SKIP` est assumé (le store type est une config que personne n'observe, et le pipeline construit ses captures même sans
auditeur, voir C-25) ; en garde-fou, l'enregistrement d'un callback d'update voué au silence émet un avertissement au log. Depuis C-24,
`withValidation` vaut `true` des deux côtés (annotation et `StoreConfig()`) : sans validator elle ne coûte rien, poser un validator c'est
vouloir qu'il tourne, et `false` reste l'échappatoire explicite.

## 3. La factory et la résolution

La factory publique est `StoreFactory`. Jusqu'au 2026-09-13, deux factories cohabitaient : la refonte, un temps nommée `StoreFactoryBetter`, a
absorbé l'ancienne au chantier C-07 (dont les variantes à path explicite ignoraient silencieusement les annotations). Toutes les variantes
convergent vers une méthode centrale unique, `createInternal`, qui résout dans l'ordre : paramètre explicite, puis annotation, puis repli
(`StoreFormats.getFormatForStringPath` pour le format, `StoreConfig()` pour la config). Depuis C-09, elle matérialise aussi le sérialiseur de DATA à son
site réifié (`serializer<DATA>()`) et le passe au store, qui appelle le format en polymorphe : la factory ne fabrique plus d'encoders.

Chaque variante ne diffère que par son `DefaultProvider`, la stratégie de données initiales :

| Variante | Données initiales quand le fichier n'existe pas |
|---|---|
| `create` | Le companion object de DATA, qui doit implémenter `Defaultable<DATA>` |
| `createFromConstructor` | `DATA::class.createInstance()` : le constructeur sans argument (tous les champs ont un défaut) |
| `createFromDefaultable<DATA, D>` | Une classe `Defaultable` externe, instanciée par constructeur sans argument |
| `createFromResource` | La ressource du classpath copiée vers le fichier cible, puis décodée |

## 4. Le cycle de vie de BaseStore

Le chemin reçu est d'abord normalisé (`toAbsolutePath().normalize()`, C-12) : logs, erreurs, sidecar et temporaires parlent tous le même chemin
net. L'initialisation enchaîne ensuite six étapes, dans l'ordre du bloc `init` :

1. **initData** : si le fichier existe, il est décodé (`_dataOrigin = FILE`) ; sinon les données par défaut sont fabriquées, sans rien écrire :
   le fichier initial n'arrive qu'après la validation (C-06), des défauts invalides ne touchent jamais le disque. Exception voulue : la copie
   d'une ressource embarquée (`createFromResource`) existe déjà à ce stade et reste sur disque même invalide, éditable, erreurs pointées à la ligne.
2. **initUpdatePolicies** : parcours récursif de `DATA::class` par réflexion (`memberProperties`), avec un ensemble `visited` contre les cycles et
   une garde qui ignore les classes `kotlin.*` et `java.*` ; chaque `@StoreUpdatePolicy` rencontrée entre dans la map `updatePolicies`.
3. **initValidation** : si `withValidation`, le validator tourne sur les données chargées ; en cas d'échec, les erreurs sont enrichies des
   numéros de ligne JSON dès qu'un fichier existe (chargé, ou copié d'une ressource), puis une `ValidationException` est levée. Le store ne se
   construit pas.
4. **persistInitialData** : les données nées par défaut écrivent enfin leur fichier initial, la validation étant passée (C-06).
5. **initAutoSave** : si `withAutoSave`, un scheduler single-thread (`scheduleAtFixedRate`) sauvegarde à chaque tick où le drapeau dirty est
   levé, sauf pause (`pauseAutoSave`). Le drapeau lui-même est posé par le pipeline d'update (`markDirty`, toutes policies confondues, depuis
   C-03). Le thread du scheduler n'est **pas** daemon : c'est `close()` qui l'arrête (C-01) ; un store jamais fermé retient la JVM.
6. **initShutdownHook** : un hook `Runtime.addShutdownHook` (gardé en champ) annule le tick en cours et, si le store est dirty, sauvegarde
   (C-23 : un store resté propre ne réécrit rien à l'extinction) : le filet anti-crash des stores encore ouverts. `close()` le désarme (C-01) : un store fermé a déjà fait sa sauvegarde d'adieu, son hook n'a plus le droit de ressusciter
   des données périmées (c'est ce mécanisme, jadis indésarmable, qui avait réécrit une édition manuelle au banc).

La fin de vie (C-01) : `close()`, idempotent, annule le tick, arrête le planificateur (`awaitTermination` 5 s : un tick en vol se termine avant la
suite), désarme le hook, puis fait la sauvegarde d'adieu si le store est dirty (`SaveTrigger.CLOSE`). Un store fermé reste lisible, refuse toute
écriture (`IllegalStateException`), et ses interrupteurs d'auto-save deviennent inertes. Les stores sont `AutoCloseable` : `use { }` fonctionne.

Jusqu'au chantier C-03, le marquage dirty était lui-même un callback onUpdate : une mise à jour `SKIP` coupait donc aussi la persistance. Depuis,
`markDirty` vit dans le pipeline d'update et toutes les policies persistent ; `SKIP`, toujours le défaut, ne gouverne plus que le silence des
callbacks.

## 5. Les mises à jour typées

L'API publique est un jeu d'extensions sur `BaseStore` :

| Extension | Geste |
|---|---|
| `set(prop, value)` | Remplacer la valeur d'une propriété de la racine |
| `setIn(prop, value) { receiver }` | Pareil, sur un objet imbriqué désigné par une lambda de navigation |
| `mutate(prop) { block }` | Modifier en place un objet mutable de la racine (une map, une liste...) |
| `mutateIn(prop, { receiver }) { block }` | Pareil, en navigation |
| `transaction { block }` | Modifier la racine entière, tout ou rien |

Tout converge vers `runUpdateInternal`, le pipeline central, exécuté sous le write lock :

1. lecture de la policy de la propriété (`updatePolicies`, sinon `config.defaultUpdatePolicy`) ;
2. `SKIP`, ou aucun auditeur d'update (ni global ni ciblé sur la propriété, C-25) : la mutation s'applique, le dirty est posé, et la fonction
   rend `null` : aucune capture, aucun callback ;
3. sinon, raccourci immuable : une propriété primitive, `String` ou enum n'est jamais copiée en profondeur ;
4. `SNAPSHOT` (et `useDeepCopy`) : copie profonde de la valeur **avant** mutation ;
5. la mutation s'applique sur l'objet vivant ;
6. capture de l'après (`DeepCopy` en snapshot, `Shallow` sinon), fabrication de l'`Operation` ;
7. hors du lock, l'appelant dispatche aux callbacks globaux puis aux callbacks ciblés de la propriété.

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
| `SNAPSHOT` | oui (avant et après) | oui |
| `SHALLOW` | non (références) | oui |
| `SKIP` | non | non |

Le drapeau dirty, lui, est posé pour toutes les policies (depuis C-03) : la policy choisit ce qu'on observe, jamais ce qui est persisté. Le choix
pratique, chiffré par `DeepCopyBenchmark` (0,2 µs pour un petit objet, ~70 µs pour 200 records imbriqués, par copie) : `SNAPSHOT` pour observer
avec des captures figées et sûres, `SHALLOW` pour observer sans copies (avant indisponible sur les mutations en place, après vivant), `SKIP` pour
le silence des points chauds. Et depuis C-25, ces coûts ne se paient que devant public : sans aucun callback d'update enregistré, le pipeline
court-circuite captures et opération, quelle que soit la policy (la transaction garde toujours son secours de rollback, lui).

Les callbacks reçoivent les valeurs sous forme de `CapturedValue` : `DeepCopy` (copie fiable, à ne pas muter), `Shallow` (lecture au moment de la
capture, fiable pour les immuables seulement), `Initial` (la toute première donnée, au premier save), `Unavailable` (rien à montrer).

## 7. La persistance

Quatre déclencheurs, portés par `SaveTrigger` : `IMMEDIATE` (`saveImmediate()`), `AUTO_SAVE` (le tick), `SHUTDOWN` (le hook JVM, si le store est
dirty) et `CLOSE` (la sauvegarde d'adieu de `close()`, si le store est dirty). Le drapeau dirty se remet à zéro dans `save()`, après un encodage
réussi. La sauvegarde
s'exécute sous le **read** lock (les lecteurs passent, les écrivains attendent la fin de l'encodage), met à jour le snapshot `_lastSavedData`
(qui nourrit le `old` des callbacks de save), écrit le sidecar meta s'il est actif, puis notifie hors lock.

L'écriture est atomique (C-02) : chaque sauvegarde encode vers un fichier temporaire unique et voisin (`<fichier>.<8 hex>.tmp`), force le flush
disque (`FileChannel.force`), puis bascule par déplacement atomique (`ATOMIC_MOVE`, repli non atomique loggué si le système de fichiers ne sait
pas faire). La cible est donc toujours une version entière. Un verrou d'IO dédié sérialise les sauvegardes d'un même store (la course
`saveImmediate`/tick est morte), les temporaires orphelins d'un crash passé sont balayés à l'ouverture, et le fichier initial comme le sidecar
meta passent par le même chemin. Un format préservant (`PreservingStoreFormat`, C-26) reçoit en plus le texte actuel de la cible au moment
d'encoder vers le temporaire : il ne réécrit que ce qui change. Quant à `reloadFromFile()` : il décode, revalide par défaut (C-05, la mémoire reste intacte en échec), puis
remplace la racine sous write lock et notifie les callbacks de reload.

## 8. La validation

Le contrat est `Validator<T>` : `validate(data, ctx)` accumule des erreurs dans un `ValidationContext` plutôt que de lever à la première.
Le contexte offre `check(condition, field, message, rejectedValue)`, `addError`, `addObjectError`, et la composition : `validateNested` (objet
imbriqué, chemin `parent.champ`) et `validateEach` (collections, chemin `champ[index]`). Les erreurs (`ValidationError`) portent le chemin
complet, la classe, le message, la valeur rejetée et, quand il est connu, le numéro de ligne du fichier.

L'enrichisseur (`ValidationErrorEnricher`) retrouve ce numéro de ligne en naviguant le JSON pretty-printed ligne à ligne, en suivant la profondeur
d'imbrication et les index de tableaux. Ses limites assumées : JSON seulement, une clé par ligne, échec silencieux. Il a fait ses preuves en
conditions réelles : le crash du banc du 2026-09-13 affichait `[HomesData.totalTeleports] ... (was: -1) → line 19`.

La validation joue à trois moments (C-05) : au chargement initial, au `reloadFromFile` (revalidation par défaut : l'objet relu est validé AVANT
de remplacer la mémoire, qui reste intacte en échec ; `validate = false` pour sauter), et à la demande via `validateNow()`, public sur `Store`.
S'y ajoute l'opt-in `validateOnUpdate` (défaut `false`, **non recommandé**) : chaque update copie la racine, mute, valide, et en échec restaure
puis émet `ValidationFailedOperation` vers les callbacks (les transactions rendent `TransactionOperation(success = false)`) ; la valeur invalide
n'entre jamais, au prix d'une copie de racine et d'un validator sous write lock à chaque geste. Il exige `useDeepCopy`, et le bon réflexe reste
les contrôles métier avant de muter.

## 9. Les formats

`StoreFormat` est le vrai point d'extension de la lib (C-09) : le contrat porte `fileExtension()` et l'encode/decode générique à sérialiseur
explicite (`decodeFromPath(deserializer, path)`, `encodeToPath(serializer, data, path)`). Le sérialiseur est matérialisé aux sites réifiés (la
factory pour les stores, un sucre `inline reified` pour les appels directs : `format.decodeFromPath<Homes>(path)`) puis transporté par l'appel
polymorphe : un format tiers implémente l'interface et traverse la factory sans qu'elle le connaisse. La réification ne pouvait pas être le
mécanisme du dispatch (elle exige des méthodes inline, donc non virtuelles) ; elle reste celui de la matérialisation. Les réglages en place :

| Format | Réglages | Particularités |
|---|---|---|
| `JsonFormat` | prettyPrint, isLenient, encodeDefaults, allowStructuredMapKeys, allowSpecialFloatingPointValues, allowComments | Crée les dossiers parents à l'écriture |
| `TomlFormat` | ignoreUnknownKeys | Crée les dossiers parents à l'écriture (depuis C-04) |
| `Json5Format` | sortie indentée quatre espaces, apostrophes simples, clés nues ; pont `Json { encodeDefaults }` | Crée les dossiers parents ; sauvegarde préservante (C-26) : seules les valeurs changées se réécrivent |

`Json5Format` (C-21) suit la conception de sa brique `li.songe:json5` : le texte est du JSON5 de bout en bout, le `Json` de kotlinx ne sert que
de moteur d'arbre (`JsonElement`) sans jamais produire de texte ; l'API de la brique étant entièrement texte, le fichier se lit entier, le
créneau étant la config et non la donnée de masse.

Sa sauvegarde est préservante (C-26) : le format déclare la capacité optionnelle `PreservingStoreFormat`, que `BaseStore` détecte au save en
fournissant le texte actuel de la cible (lu sous le verrou d'IO, pendant l'encodage vers le temporaire atomique ; le contrat `StoreFormat`
reste intact). L'arbre encodé est différencié contre le document parsé (`parseToDocument`, l'AST aux plages source exactes et aux commentaires
attachés), et seules les retouches s'appliquent (`set`, `putProperty`, `remove`) : les valeurs changées se réécrivent, les clés nouvelles
s'ajoutent, les disparues s'en vont avec leurs commentaires, tout le reste du fichier reste au caractère près, et un save sans changement est
identique à l'octet. Décisions v1 : un tableau modifié se remplace entier (un diff par index apparierait mal commentaires et éléments
déplacés), et un fichier cible absent ou invalide vaut encode à neuf.

`StoreFormats` (l'ex-`Utils`, renommé au chantier C-08) tient le registre extension vers format (`json`, `toml`, `json5`), interrogé quand aucun
format n'est donné ; `registerFormat` y ajoute un format tiers, résolu par l'extension du chemin comme les formats fournis. Une extension inconnue est refusée net (`IllegalArgumentException` qui nomme
les extensions enregistrées) : le repli silencieux sur JSON est mort avec le reste du trompe-l'oeil. Et `atomicWrite` garantit les dossiers
parents avant chaque écriture : un format tiers qui oublierait de les créer ne reproduira pas le piège du constat n° 1 (la leçon C-04,
généralisée).

## 10. Le deep copy CBOR

Les copies profondes (`utils\DeepCopy.kt`) sont un aller-retour de sérialisation CBOR : encodage en octets puis décodage, via une instance `Cbor`
dédiée (`encodeDefaults = true`). C'est ce qui permet de copier n'importe quelle data class `@Serializable` sans imposer d'interface de clonage.
Le coût se mesure avec `DeepCopyBenchmark` (dans les tests) : tailles croissantes, comparaison avec `.copy()` et l'affectation directe, courbe en
fonction de la taille des collections. Le raccourci immuable du pipeline d'update (chapitre 5) évite ce coût pour les primitives, chaînes et enums.

## 11. La concurrence

- Toutes les lectures et écritures de `_data` passent par un `ReentrantReadWriteLock` : `data` prend le read lock, les updates et le
  remplacement de racine le write lock, la sauvegarde le read lock.
- Les callbacks sont notifiés **hors** de tout lock : un callback peut relire le store sans interblocage ; les valeurs qu'il reçoit sont des
  captures, pas des références sous verrou (sauf `Shallow` sur un mutable, à ses risques).
- Depuis C-08, l'enregistrement des callbacks est sûr à tout moment : les conteneurs sont privés et thread-safe (`CopyOnWriteArrayList`,
  `ConcurrentHashMap`), un callback peut s'enregistrer pendant un dispatch.
- Le logging (C-11) : le logger n'appartient plus au contrat `Store`, c'est un champ privé fabriqué une fois par store ; tous les messages
  portent le préfixe `[Storify]`, les ticks parlent en debug, le cycle de vie en info, les échecs en warn. Et la lib ne journalise jamais de
  données utilisateur : des chemins et des états seulement (politique posée au C-12, vérifiée sur la flotte des messages). Non garanti à ce jour : le sidecar meta se modifie sans verrou
  propre, et un encodage long sous read lock retarde tous les écrivains.

## 12. Le sidecar meta

Avec `withMeta = true`, le store entretient `<fichier>.meta.json` : `createdAt` (à la création de l'objet), `lastModified` (entretenu à chaque
update par `touch()` depuis C-13, au format `yyyy-MM-dd HH:mm:ss:SSS` local), `version` (posée à 1, réservée au versionnage de schéma du
chantier C-17) et `custom` (le sac libre du consommateur ; le banc l'affiche en jeu, la lib n'y écrit jamais). Le fichier s'écrit au moment des
sauvegardes, toujours en JSON, quel que soit le format du store, comme son nom le promet (C-09).

## 13. Les dépendances, et pourquoi

| Dépendance | Rôle |
|---|---|
| `kotlinx-serialization-json` | Le format JSON, et le parsing du sérialiseur d'appoint |
| `kotlinx-serialization-cbor` | Le véhicule des copies profondes (chapitre 10), pas un format offert à l'utilisateur |
| `dev.eav.tomlkt:tomlkt` | Le format TOML |
| `li.songe:json5` | Le format JSON5 : le parse et l'écriture du texte ; le mapping passe par un pont `JsonElement` kotlinx |
| `kotlin-reflect` | Le scan des annotations, `createInstance`, `memberProperties` (factories et policies) |
| `kotlinx-datetime` | Les horodatages du sidecar meta |
| `slf4j-api` | Le logging (le binding est laissé au consommateur ; `slf4j-simple` en test) |

## 14. Ce que le banc et les tests ont prouvé

Les mécanismes ci-dessus ne sont pas que du code lu : Storibench (le banc, `..\..\Storibench`) et la suite de tests les ont
exercés en vrai. Les faits marquants, sources des chantiers :

- `TomlFormat` a fait échouer le tout premier lancement du banc faute de dossiers parents (constat n° 1 du banc ; corrigé au chantier C-04).
- Le défaut `SKIP` a éteint callbacks et auto-save jusqu'à ce que le banc force `SNAPSHOT` (constat n° 2). Depuis C-03, la persistance est
  garantie pour toutes les policies ; le défaut ne gouverne plus que les callbacks, et des tests verrouillent les deux comportements.
- Le hook d'arrêt d'un store « détaché » a réécrit ses données par-dessus un fichier édité à la main, juste après le crash de validation que cette
  édition avait provoqué (constat n° 3, aggravé, mesuré le 2026-09-13 sur le client du banc ; soldé au chantier C-01 : `close()` désarme le hook).
- `reloadFromFile` accepte des valeurs invalides sans un mot (constat n° 4 ; soldé au chantier C-05 : revalidation par défaut, `validateNow()`
  public, et l'update validable en opt-in).
- La `ValidationException` au chargement est excellente (chemin, valeur, numéro de ligne JSON), mais en solo Minecraft elle se paie d'un crash
  complet du client (« Exception in server tick loop »).
- L'auto-save tient son intervalle (ticks de 30 s observés à la seconde près), le callback ciblé par propriété fonctionne, la persistance et le
  sidecar meta suivent.

---

*Dernière vérification : 2026-09-23, chapitres 1, 2 et 9 relus contre `src\main` ; le reste tenu au fil des chantiers, jusqu'à C-26 (2026-09-15).*
