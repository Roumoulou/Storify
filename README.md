# Storify

Une bibliothèque Kotlin de stores de données et de configuration sur fichier, pensée d'abord pour les mods Minecraft server-side et utilisable dans
tout projet JVM. Un store attelle une data class sérialisable à un fichier JSON, TOML ou JSON5, et fournit autour tout ce qu'un mod réclame : un accès
global thread-safe, des mises à jour typées propriété par propriété, des callbacks, une sauvegarde automatique, une validation au chargement avec
rapport d'erreurs détaillé, et un sidecar de métadonnées.

## 1. L'état du projet

- Coordonnées : `fr.moulou:storify`, version `0.9.0`, publiée sur Repsy (`https://repo.repsy.io/roumoulou/maven`) le 2026-10-22 ; c'est elle
  que la recette de la section 4 cite. L'artefact porte deux variantes sous cette seule coordonnée, Java 11 et Java 25, que Gradle choisit
  d'après la cible du consommateur (section 4) ; Storify demande Java 11 au moins. `master` continue en `0.10.0-SNAPSHOT` ; avant `1.0.0`, le
  mineur monte à chaque ajout ou rupture, et un consommateur épingle une release, jamais un snapshot.
- Consommation : depuis Repsy pour un mod ou tout projet JVM (la recette vit en section 4), ou par build composite pour développer la lib.
  Le consommateur de référence est Storibench, le banc d'essai en conditions réelles (un mod Fabric pour Minecraft 26.2), qui vit hors de ce
  dépôt, dans le classeur : `..\Storibench`, et reste en composite par défaut ; sa propriété `storify_source=repsy` le fait consommer l'artefact
  publié, preuve faite le 2026-09-23 sur un serveur sans Gradle.
- Build et tests : verts au 2026-10-22 (371 tests, le bytecode des deux variantes et l'essai de fumée sur Java 11, section 6), sur la stack
  ci-dessous.
- L'API n'est pas encore stabilisée : les chantiers ouverts (C-17, C-19 et C-20 ; C-35, C-38, C-62 et C-67 en attente) vivent dans `Docs\chantiers.md`.
- Dépôt Git : `https://github.com/Roumoulou/Storify`, public ; branche `master`, un chantier par branche fondue par `merge --no-ff`, une
  release par tag `v<version>` (`git-et-versions.md` de The Human Readme) ; `CHANGELOG.md` tient les versions.
- Licence : LGPL-3.0-only (section 9) : le texte de la licence est `LICENSE`, celui de la GPL v3 qu'elle incorpore `LICENSE.GPL`, et chaque source
  porte son en-tête SPDX ; la copie de kotlin-json5 qu'elle embarque est sous Apache-2.0 (`LICENSE.json5`, `NOTICE`).

| Outil | Version |
|---|---|
| Gradle (wrapper) | 9.8.1 |
| Kotlin | 2.4.21 |
| Java (toolchain) | 25, et un JDK 11 pour l'essai de fumée de la variante Java 11 |
| kotlinx.serialization | 1.11.0 |
| tomlkt | 0.6.1 |
| kotlin-json5, copiée dans les sources (`internal.json5`) | 0.8.0 |
| kotlinx-datetime | 0.8.0 |

## 2. Les fonctionnalités

- **Trois formats de fichier fournis** : JSON (`JsonFormat`, le JSON standard, strict à la lecture : un commentaire, une clé sans guillemets ou
  une clé déclarée deux fois dans le même objet font échouer le chargement avec la ligne, comme une clé inconnue ; `JsonFormat.lenient()` les
  tolère ; kotlinx laisse passer, sans réglage pour les refuser, un nombre ou un booléen entre guillemets et une virgule manquante entre deux
  membres d'un objet, la donnée décodée restant juste), TOML (`TomlFormat`, qui refuse aussi une clé en double, par sa spécification) et JSON5
  (`Json5Format`, le JSON des configs éditées à
  la main : commentaires, clés nues, virgules traînantes, et des sauvegardes qui **préservent** les commentaires et le style ; une clé en double
  y est refusée aussi, avec sa ligne), résolus par l'extension du chemin quand on ne les précise pas ; un BOM UTF-8 en tête de fichier (le
  Bloc-notes en pose un) est toléré à la lecture et jamais écrit ; et un vrai point d'extension (C-09) : un format tiers implémente
  `StoreFormat` et s'enregistre par `StoreFormats.registerFormat`.
- **Cinq sources de données initiales**, quand le fichier n'existe pas encore : le constructeur sans argument de la data class, son companion
  `Defaultable`, une classe `Defaultable` externe, une ressource embarquée dans le jar, copiée telle quelle au premier lancement (le fichier
  que l'admin trouve est celui que le mod a livré, commentaires compris), ou le code de l'appelant : `createFromProvider` reçoit le sérialiseur
  et les données initiales en valeurs, sans rien lire sur la classe, pour un fichier dont la forme naît d'une table ou d'un schéma composé à
  l'exécution ; la racine n'a alors pas à être une classe `@Serializable`, une `Map` suffit, et se met à jour par `transaction`.
- **Configuration par annotations ou par code** (`@StorePath`, `@StoreFileFormat`, `@StoreConfiguration`, `@StoreValidator`,
  `@StoreDefaultResource`, `@StoreUpdatePolicy`, `@StoreComment`), avec la préséance explicite > annotation > défaut, objet par objet : une
  `StoreConfig` passée à la factory remplace `@StoreConfiguration` en bloc, jamais champ par champ.
- **Les commentaires depuis le code** (`@StoreComment`, C-64) : une propriété annotée porte, dans le fichier JSON5, ses lignes de commentaire
  au-dessus de sa clé, et la classe racine les siennes en tête du fichier ; ils s'écrivent quand le fichier naît et quand une clé s'ajoute, l'admin
  gardant les siens (`CommentPolicy.FILE_WINS`, le défaut), ou à chaque sauvegarde sur les clés que le code commente (`CODE_WINS`). JSON les
  ignore ; TOML écrit `@TomlComment` de tomlkt.
- **Accès global thread-safe** : `store.data` sous read lock, mises à jour sous write lock, callbacks notifiés hors du lock et enregistrables à
  tout moment, dispatch compris.
- **Mises à jour typées** par référence de propriété : `set` et `setIn` (remplacer une valeur), `mutate` et `mutateIn` (modifier un objet mutable en
  place, avec navigation dans l'arborescence), `transaction` (tout ou rien, avec rollback sur exception).
- **Callbacks** : update global, update ciblé sur une propriété de la racine (`registerOnUpdateOn`, typé) ou sur une **instance imbriquée**
  précise (`registerOnUpdateOnIn`, le miroir de `setIn` : deux joueurs de la même classe s'écoutent séparément), save, reload ; chaque
  notification porte une `Operation` avec les valeurs avant et après (`CapturedValue`).
- **Politiques de capture par propriété** (`UpdatePolicy`) : `SNAPSHOT` (copie profonde avant et après), `SHALLOW` (références seules), `SKIP`
  (silence complet).
- **Persistance** : sauvegarde immédiate (`saveImmediate`), auto-save périodique avec pause et reprise, sauvegarde au hook d'arrêt de la JVM,
  rechargement depuis le fichier (`reloadFromFile`, où le fichier gagne : une modification en mémoire non sauvegardée est écartée et signalée au
  log, et le store ressort propre), et écriture atomique partout (fichier temporaire puis déplacement atomique : jamais de fichier tronqué,
  même en cas de crash en pleine écriture) ; le même écrivain est public pour un fichier écrit hors store, `AtomicFiles.write` et
  `format.encodeToPathAtomically`.
- **Fin de vie propre** : les stores sont `AutoCloseable` ; `close()` annule le tick, arrête le planificateur, désarme le hook d'arrêt et fait une
  sauvegarde d'adieu si nécessaire ; un store fermé reste lisible et refuse les écritures.
- **Mode lecture seule** : `readOnly` refuse toute écriture (`IllegalStateException`), n'arme ni planificateur ni hook, et laisse lire, valider et
  recharger, pour un fichier dont le mod n'est pas propriétaire ; `createIfMissing` décide si le fichier initial s'écrit quand il manque, et
  `withShutdownHook` débraye le hook d'arrêt seul.
- **Validation** : au chargement, au rechargement (`reloadFromFile` revalide par défaut, mémoire intacte en échec), à la demande sur la mémoire
  (`validateNow()`) ou sur le fichier du disque (`validateFile()`), et sans store (`format.validateFile(path, validator)`) ; un `Validator`
  explicite ou résolu par annotation, un `ValidationContext` riche (imbrication, collections, maps, chemins d'erreur), un rapport d'erreurs
  détaillé enrichi des numéros de ligne pour JSON et JSON5, clés de map comprises (une clé faite de chiffres s'écrit entre guillemets dans le
  chemin, `groups["123"]`, pour ne pas se lire comme un index ; une clé omise du fichier reçoit au mieux la ligne de l'objet qui devrait la
  porter) ; des défauts invalides ne créent jamais de fichier sur disque. En option non recommandée, `validateOnUpdate` valide
  chaque update, avec rollback et opération d'échec.
- **Des fautes de fichier lisibles** : un fichier qui ne se lit pas ou ne se décode pas lève `StoreDecodeException` (le chemin, le format, la
  ligne quand elle se lit dans le message du parseur, qu'une clé en double la donne ou que le message nomme le chemin de la valeur, retrouvé
  par le localisateur du format ; le chemin de la valeur exposé en `valuePath`, que la clé en double porte typé en JSON et en JSON5, la clé en
  dernier segment, et qu'`ErrorPath.render` écrit en texte ; une fin de fichier atteinte rend la ligne où le fichier s'arrête ; la cause
  conservée) ; elle et `ValidationException` descendent de `StorifyException`, un seul `catch` pour tout ce qui vient du fichier ; les fautes du
  code (écrire sur un store fermé ou en lecture seule) restent des `IllegalStateException`.
- **Sidecar de métadonnées** optionnel (`<fichier>.meta.json`) : dates de création et de modification, version, données libres.
- **Copies profondes par arbre JSON** : les snapshots des callbacks, le rollback des transactions et les captures du rechargement passent par un
  aller-retour `JsonElement` (jamais de texte), sur le `Json` du format quand il en a un : un sérialiseur écrit pour le JSON survit à la copie ;
  aucune capture ne part sans public ni sans `useDeepCopy` (un store que personne n'écoute ne copie rien, ni à l'ouverture ni au save ; seul
  le secours de rollback des transactions se prend toujours), et le coût est mesuré par `DeepCopyBenchmark`.

## 3. Démarrage rapide

Une config TOML, validée, écrite quand on la modifie :

```kotlin
@Serializable
data class ServerConfig(
    var greeting: String = "Bienvenue !",
    var maxHomesPerPlayer: Int = 3,
)

class ServerConfigValidator : Validator<ServerConfig> {
    override fun validate(data: ServerConfig, ctx: ValidationContext) {
        ctx.check(data.greeting.isNotBlank(), "greeting", "must not be blank", data.greeting)
        ctx.check(data.maxHomesPerPlayer in 1..100, "maxHomesPerPlayer", "must be between 1 and 100", data.maxHomesPerPlayer)
    }
}

val configStore = StoreFactory.createFromConstructor<ServerConfig>(
    stringPath = "config/mymod/config.toml",
    format = TomlFormat(),
    config = StoreConfig(
        withValidation = true,
        withAutoSave = false,                        // une config s'écrit au moment où on la modifie
        defaultUpdatePolicy = UpdatePolicy.SNAPSHOT, // le défaut est SKIP : sans policy, pas de callbacks (la persistance, elle, est garantie)
    ),
    validator = ServerConfigValidator(),
)

configStore.set(ServerConfig::greeting, "Bonjour !")
configStore.saveImmediate()
```

Des données vivantes en JSON, annotées, auto-sauvées, observées :

```kotlin
@Serializable
@StoreValidator(HomesValidator::class)
data class Homes(
    var players: MutableMap<String, MutableList<String>> = mutableMapOf(),

    @StoreUpdatePolicy(UpdatePolicy.SHALLOW)
    var totalTeleports: Long = 0,
)

val homesStore = StoreFactory.createFromConstructor<Homes>(
    stringPath = "world/data/mymod/homes.json", // extension .json : JsonFormat résolu tout seul
    config = StoreConfig(withValidation = true, withAutoSave = true, withMeta = true, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT, autoSaveIntervalMs = 30_000),
)

homesStore.registerOnUpdate { operation -> log.info("update : {}", operation) }
homesStore.registerOnUpdateOn(Homes::totalTeleports) { log.info("le compteur a bougé") }

homesStore.mutate(Homes::players) { players -> players.getOrPut("Steve") { mutableListOf() }.add("base") }
homesStore.set(Homes::totalTeleports, homesStore.data.totalTeleports + 1)

homesStore.reloadFromFile() // relit le fichier, le revalide (mémoire intacte en échec) et notifie onReload
```

## 4. Embarquer Storify dans un mod

À la compilation, Gradle télécharge Storify depuis Repsy ; au runtime, un serveur n'a plus de Gradle : le jar du mod doit donc embarquer la
lib. La recette Fabric tient en deux `include` (le jar-in-jar de Loom, qui wrappe tout seul les jars non-mods) :

```kotlin
repositories {
    maven("https://repo.repsy.io/roumoulou/maven") { name = "Repsy" }
}

dependencies {
    implementation("fr.moulou:storify:0.9.0")  // compiler contre la lib...
    include("fr.moulou:storify:0.9.0")         // ... et l'embarquer dans le jar du mod
    include("dev.eav.tomlkt:tomlkt:0.6.1")     // include n'est pas transitif : chaque jar se déclare
}
```

Et dans `fabric.mod.json`, le plancher qui garantit le runtime Kotlin :

```json
"depends": { "fabric-language-kotlin": ">=1.14.2" }
```

Pourquoi deux jars seulement : fabric-language-kotlin 1.14.2 fournit au runtime la stdlib, kotlin-reflect 2.4.21, kotlinx-serialization
core, json et cbor 1.11.0 et kotlinx-datetime 0.8.0, exactement les versions attendues par Storify ; kotlin-json5 est copiée dans Storify
(section 9) ; et Minecraft fournit slf4j depuis 1.17.1. Un FLK plus vieux est refusé net par le loader (le `depends`) ; un plus récent est
couvert par la rétrocompatibilité binaire de Kotlin ; et le loader déduplique les jars embarqués entre mods (une seule version chargée, la
plus récente compatible). Minecraft 1.16.5 ne fournit pas slf4j : un mod pour cette version embarque `slf4j-api` et sa liaison vers le
log4j 2.8.1 du jeu.

### 4.1 Les deux variantes, Java 11 et Java 25

L'artefact `fr.moulou:storify` porte deux variantes, compilées des mêmes sources (C-65, C-68) : la variante Java 25, `storify-0.9.0.jar`, celle
que le POM décrit, et la variante Java 11, `storify-0.9.0-jdk11.jar`, en bytecode 55, compilée avec `-Xjdk-release=11` contre l'API de Java 11.
Les métadonnées Gradle (`.module`) les publient toutes deux sous la même coordonnée, chacune avec son `org.gradle.jvm.version`, 11 ou 25, et
Gradle choisit d'après la cible du consommateur, celle que `release` (javac) ou `jvmTarget` (Kotlin) demande :

| Le consommateur vise | Il reçoit |
|---|---|
| Java 11 à 24 (un mod pour Minecraft 1.16.5 lancé sur Java 11, ou pour 1.17.1 à 1.21.x) | la variante Java 11 |
| Java 25 (un mod pour Minecraft 26.x) | la variante Java 25 |
| Java 8 à 10 | aucune : Gradle refuse, Storify demande Java 11 |
| Maven, ou tout outil qui ne lit que le POM | la variante Java 25, le jar sans classifier |

Un piège, mesuré sur Loom 1.18.2 : `include` se résout par la configuration `includeInternal`, qui ne demande pas de cible Java, et Gradle
y prend alors la variante du Java le plus récent, la 25, quelle que soit la cible du mod. Un mod qui vise moins que Java 25 lui dit sa cible,
et le jar-in-jar embarque la variante Java 11 :

```kotlin
import org.gradle.api.attributes.java.TargetJvmVersion

configurations.named("includeInternal") {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 11) // la cible du mod, 11 à 21
}
```

Le jar de sources de chaque variante suit (`storify-0.9.0-sources.jar`, `storify-0.9.0-jdk11-sources.jar`) ; les sources sont les mêmes. La
variante Java 11 est éprouvée à chaque build de la lib par un essai de fumée lancé sur un JDK 11 (section 6).

### 4.2 Le shading

Le shading avec relocation est écarté comme voie par défaut : kotlinx et reflect sont la langue commune entre Storify et les data classes du
mod (les `KSerializer` et `KProperty1` traversent l'API dans les deux sens), les relocater couperait la lib de ses consommateurs. Option
avancée, pour un mod qui exige l'isolation totale : shader Storify seule, kotlinx et reflect intouchés ; au prix des métadonnées Kotlin
relocatées (mensongères pour kotlin-reflect) et à condition qu'aucun type Storify ne franchisse la frontière du mod.

## 5. Les notions, en un tableau

| Notion | Rôle |
|---|---|
| `BaseStore<DATA>` | Le store : chargement, verrous, updates, callbacks, persistance ; implémente l'interface `Store<DATA>` |
| `StoreConfig` | Les options d'une instance : validation, auto-save et son intervalle, meta, deep copy, policy par défaut, lecture seule, hook d'arrêt, fichier initial, nom du logger, politique des commentaires |
| `StoreFactory` | La factory : `create` (companion `Defaultable`), `createFromConstructor`, `createFromDefaultable`, `createFromResource`, et `createFromProvider` (le sérialiseur et les données initiales donnés en valeurs, sans classe) |
| `UpdatePolicy` | Ce qu'un update capture et notifie : `SNAPSHOT`, `SHALLOW` ou `SKIP` |
| `StoreComment` / `CommentPolicy` | Le commentaire du code au-dessus d'une clé (JSON5), et qui gagne entre lui et celui du fichier : `FILE_WINS` ou `CODE_WINS` |
| `Operation` / `CapturedValue` | Ce que reçoivent les callbacks : le type d'opération, et les valeurs avant et après (copie profonde, lecture directe, ou indisponible) |
| `StoreFormat` | Le contrat d'un format : extension, encode/decode à sérialiseur explicite ; `JsonFormat`, `TomlFormat` et `Json5Format` fournis, formats tiers via `StoreFormats.registerFormat` |
| `AtomicFiles` | L'écrivain atomique des stores, public : `write(target) { temp -> }`, le balayage des temporaires orphelins ; `encodeToPathAtomically` sur tout format |
| `Validator` / `ValidationContext` | La validation : conditions, erreurs à chemin complet, imbrication (`validateNested`, `validateEach` sur listes et maps) ; `validateFile` valide un fichier, avec ou sans store |
| `StorifyException` | L'ancêtre des fautes du fichier : `StoreDecodeException` (lecture ou décodage, chemin, ligne et chemin de la valeur au mieux) et `ValidationException` |
| `StoreMeta` | Le sidecar `<fichier>.meta.json` : createdAt, lastModified, version, données libres |
| `Defaultable` | Le fournisseur de données par défaut |

## 6. Construire et tester

```powershell
.\gradlew build
```

Le build exige un JDK 25 (la toolchain) et un JDK 11, déclaré à Gradle (`org.gradle.java.installations.paths`, ou l'auto-détection), pour
l'essai de fumée de la variante Java 11 ; les tests tournent sous JUnit (plateforme unifiée JUnit 6) sur la variante Java 25 et écrivent leurs
fichiers dans `build\tmp\storify-tests`. `check` enchaîne trois preuves (C-65) :

- `test`, les 371 tests, sur la variante Java 25 ;
- `checkBytecode`, qui lit chaque classe des deux jars : bytecode 69 dans `storify-<version>.jar`, 55 dans `storify-<version>-jdk11.jar` ;
- `smokeJdk11Run`, l'essai de fumée de `src\smokeJdk11` : compilé en cible 11 contre le jar `jdk11`, comme un consommateur, et lancé sur la
  toolchain 11 avec les dépendances de la lib aux versions du catalogue, celles que fabric-language-kotlin 1.14.2 fournit ; il ouvre, modifie,
  sauve et relit un store JSON, un TOML et un JSON5, vérifie la sauvegarde préservante et la clé en double, et échoue si un résultat diffère.

La variante Java 11 compile une copie de `src\main\kotlin` (la tâche `syncJdk11Sources`, vers `build\jdk11\kotlin`) en `jvmTarget` 11 avec
`-Xjdk-release=11`, qui refuse à la compilation toute API de Java 12 et plus : le code est commun aux deux variantes, et le jour où un fichier
devra s'écrire autrement pour l'une, il ira dans `src\jdk11\kotlin`, au même chemin relatif, un fichier par différence.

La visite guidée commentée de l'API vit dans `src\test\kotlin\fr\moulou\storify\demos\HomesModDemo.kt` : cinq démos
exécutables sur un domaine réel de mod (homes, téléportation, délai, cooldown), chacune repartant d'un dossier vierge. Le même dossier porte
les démos des chantiers, un fichier `<Mécanisme>Demo.kt` par mécanisme (copie profonde, lecture seule, BOM, lignes de validation, erreurs de
décodage, écriture atomique, JSON strict, logger nommé, ressource embarquée, snapshot du save, flottants spéciaux, pipeline d'update,
ouverture d'un store, rechargement d'un store modifié, sauvegarde sous verrou, clés en double, fabrique au sérialiseur donné, ligne d'une
faute sans offset) : des tests ordonnés par étape, qui impriment ce que la lib fait avant et après. Le benchmark des copies profondes
s'exécute avec les tests. L'essai en conditions réelles se fait depuis le banc :
`.\gradlew runServer` dans `..\Storibench`, dont le README décrit les scénarios et les commandes en jeu.

La publication : `.\gradlew publishToMavenLocal` répète le circuit sans secret (dépôt Maven local) ; `.\gradlew publish` pousse sur Repsy, le
jeton arrivant par la chaîne de secrets (`dev-secrets.ps1 -Apply REPSY_MAVEN_TOKEN`) dans le terminal qui publie, jamais autrement. Une
version publiée est une release : un tag `v<version>` sur `master`, une release GitHub qui porte les quatre jars et le changelog
(`CHANGELOG.md`), puis `master` qui repart en `-SNAPSHOT` ; les gestes sont ceux de `git-et-versions.md` (The Human Readme), chapitre 3.

## 7. Les limites connues

En toute franchise, mesurées au banc et par les tests ; le détail et les remèdes vivent dans `Docs\chantiers.md` :

- la validation à l'update est un opt-in (`validateOnUpdate`) volontairement non recommandé : chaque geste copie la racine entière et valide sous
  verrou ; préférez des contrôles métier avant de muter, `validateNow()` et la revalidation du reload couvrent le reste ;
- le défaut de `defaultUpdatePolicy` est `SKIP` : les callbacks se taisent tant qu'une policy ne les allume pas (par annotation ou par config) ;
  la persistance, elle, est garantie quelle que soit la policy, et l'enregistrement d'un callback voué au silence le signale au log (C-22) ;
- en JSON5, les commentaires et le style d'un fichier édité à la main survivent aux sauvegardes (C-26 : la réconciliation ne réécrit que les
  valeurs changées ; un tableau modifié se remplace entier, ses commentaires intérieurs avec). En JSON et TOML, la sauvegarde réécrit toujours
  le fichier entier.

## 8. La documentation

- `Docs\architecture.md` : comment la lib est faite, mécanisme par mécanisme.
- `Docs\chantiers.md` : le bilan (forces et faiblesses) et la liste priorisée de tout ce qui est à revoir, refaire ou construire.
- `Docs\info.md` et `Docs\TODO` : les documents historiques (l'ancien brief d'analyse et les premières notes), absorbés par les deux précédents.
- `CHANGELOG.md` : les versions publiées, de la plus récente à la plus ancienne, et ce que chacune apporte.

## 9. La licence

Storify est distribuée sous la GNU Lesser General Public License, version 3 seulement (SPDX `LGPL-3.0-only`) ; copyright (c) 2025-2026 Roumoulou. Le
texte fait foi : `LICENSE`, et `LICENSE.GPL` pour la GPL v3 qu'il incorpore. En résumé, pour un consommateur :

- un mod ou une application qui embarque Storify sans la modifier (jar-in-jar, dépendance Maven) garde sa propre licence, quelle qu'elle soit, à
  condition de conserver la licence et les mentions de Storify et de laisser l'utilisateur remplacer la lib par une autre version, ce que le jar-in-jar
  et la dépendance externe permettent ;
- une version modifiée de Storify se redistribue sous la même licence, sources comprises.

Storify contient une copie modifiée de kotlin-json5 0.8.0 (`li.songe:json5`, `https://github.com/lisonge/kotlin-json5`, copyright lisonge), sous
la licence Apache, version 2.0 (C-65) : ses sources vivent sous `fr.moulou.storify.internal.json5`, paquet relocalisé et déclarations de premier
niveau passées `internal` sauf `Json5EncoderConfig` et `Json5QuoteStrategy`, chaque fichier disant en tête ce qui a changé ; le texte de la licence
est `LICENSE.json5`, et `NOTICE` nomme la copie. Les quatre textes entrent dans chaque jar, suffixés du nom de l'artefact (`LICENSE_storify`,
`LICENSE.GPL_storify`, `LICENSE.json5_storify`, `NOTICE_storify`) : un mod qui embarque Storify les redistribue avec elle.
