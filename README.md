# Storify

Une bibliothèque Kotlin de stores de données et de configuration sur fichier, pensée d'abord pour les mods Minecraft server-side et utilisable dans
tout projet JVM. Un store attelle une data class sérialisable à un fichier JSON ou TOML, et fournit autour tout ce qu'un mod réclame : un accès
global thread-safe, des mises à jour typées propriété par propriété, des callbacks, une sauvegarde automatique, une validation au chargement avec
rapport d'erreurs détaillé, et un sidecar de métadonnées.

## 1. L'état du projet

- Coordonnées : `fr.moulou:storify`, version `0.1.0-SNAPSHOT`, publiée sur Repsy (`https://repo.repsy.io/roumoulou/maven`) ; le circuit de
  publication est en place depuis C-18. Aucune release figée encore : l'API bouge, le snapshot se republie à volonté.
- Consommation : depuis Repsy pour un mod ou tout projet JVM (la recette vit en section 4), ou par build composite pour développer la lib.
  Le consommateur de référence est Storibench, le banc d'essai en conditions réelles (un mod Fabric pour Minecraft 26.2), qui vit hors de ce
  dépôt, dans le classeur : `..\Storibench`, et reste volontairement en composite.
- Build et tests : verts au 2026-09-16, sur la stack ci-dessous.
- L'API n'est pas encore stabilisée : des chantiers d'API restent ouverts (le typage du callback ciblé, le logging, le sidecar meta). La liste
  complète vit dans `Docs\chantiers.md`.
- Dépôt Git : en place depuis le 2026-09-13 (branche `master`, un commit par chantier), poussé sur GitHub le jour même (`Roumoulou/Storify`, privé).
- Licence : propriétaire pour l'instant (`LICENSE.txt`, tous droits réservés) ; le choix d'une licence réelle reste à trancher.

| Outil | Version |
|---|---|
| Gradle (wrapper) | 9.7.1 |
| Kotlin | 2.4.20 |
| Java (toolchain) | 25 |
| kotlinx.serialization | 1.11.0 |
| tomlkt | 0.6.1 |
| li.songe:json5 | 0.8.0 |
| kotlinx-datetime | 0.8.0 |

## 2. Les fonctionnalités

- **Trois formats de fichier fournis** : JSON (`JsonFormat`), TOML (`TomlFormat`) et JSON5 (`Json5Format`, le JSON des configs éditées à la
  main : commentaires, clés nues, virgules traînantes, et des sauvegardes qui **préservent** les commentaires et le style), résolus par
  l'extension du chemin quand on ne les précise pas ; et un vrai point d'extension (C-09) : un format tiers implémente `StoreFormat` et
  s'enregistre par `StoreFormats.registerFormat`.
- **Quatre sources de données initiales**, quand le fichier n'existe pas encore : le constructeur sans argument de la data class, son companion
  `Defaultable`, une classe `Defaultable` externe, ou une ressource embarquée dans le jar copiée au premier lancement.
- **Configuration par annotations ou par code**, avec la préséance explicite > annotation > défaut : `@StorePath`, `@StoreFileFormat`,
  `@StoreConfiguration`, `@StoreValidator`, `@StoreDefaultResource`, `@StoreUpdatePolicy`.
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
  rechargement depuis le fichier (`reloadFromFile`), et écriture atomique partout (fichier temporaire puis déplacement atomique : jamais de
  fichier tronqué, même en cas de crash en pleine écriture).
- **Fin de vie propre** : les stores sont `AutoCloseable` ; `close()` annule le tick, arrête le planificateur, désarme le hook d'arrêt et fait une
  sauvegarde d'adieu si nécessaire ; un store fermé reste lisible et refuse les écritures.
- **Validation** : au chargement, au rechargement (`reloadFromFile` revalide par défaut, mémoire intacte en échec) et à la demande
  (`validateNow()`) ; un `Validator` explicite ou résolu par annotation, un `ValidationContext` riche (imbrication, collections, chemins
  d'erreur), un rapport d'erreurs détaillé enrichi des numéros de ligne pour les fichiers JSON ; des défauts invalides ne créent jamais de
  fichier sur disque. En option non recommandée, `validateOnUpdate` valide chaque update, avec rollback et opération d'échec.
- **Sidecar de métadonnées** optionnel (`<fichier>.meta.json`) : dates de création et de modification, version, données libres.
- **Copies profondes par CBOR** : les snapshots des callbacks et le rollback des transactions passent par un aller-retour de sérialisation, mesuré
  par un benchmark dédié (`DeepCopyBenchmark`).

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

homesStore.reloadFromFile() // relit le fichier, notifie onReload ; sans revalidation, voir les limites
```

## 4. Embarquer Storify dans un mod

À la compilation, Gradle télécharge Storify depuis Repsy ; au runtime, un serveur n'a plus de Gradle : le jar du mod doit donc embarquer la
lib. La recette Fabric tient en trois `include` (le jar-in-jar de Loom, qui wrappe tout seul les jars non-mods) :

```kotlin
repositories {
    maven("https://repo.repsy.io/roumoulou/maven") { name = "Repsy" }
}

dependencies {
    implementation("fr.moulou:storify:0.1.0-SNAPSHOT")  // compiler contre la lib...
    include("fr.moulou:storify:0.1.0-SNAPSHOT")         // ... et l'embarquer dans le jar du mod
    include("dev.eav.tomlkt:tomlkt:0.6.1")              // include n'est pas transitif :
    include("li.songe:json5:0.8.0")                     // chaque jar se déclare
}
```

Et dans `fabric.mod.json`, le plancher qui garantit le runtime Kotlin :

```json
"depends": { "fabric-language-kotlin": ">=1.14.1" }
```

Pourquoi trois jars seulement : fabric-language-kotlin 1.14.1 fournit au runtime la stdlib, kotlin-reflect 2.4.20, kotlinx-serialization
core, json et cbor 1.11.0 et kotlinx-datetime 0.8.0, exactement les versions attendues par Storify, et Minecraft fournit slf4j. Un FLK plus
vieux est refusé net par le loader (le `depends`) ; un plus récent est couvert par la rétrocompatibilité binaire de Kotlin ; et le loader
déduplique les jars embarqués entre mods (une seule version chargée, la plus récente compatible).

Le shading avec relocation est écarté comme voie par défaut : kotlinx et reflect sont la langue commune entre Storify et les data classes du
mod (les `KSerializer` et `KProperty1` traversent l'API dans les deux sens), les relocater couperait la lib de ses consommateurs. Option
avancée, pour un mod qui exige l'isolation totale : shader Storify seule, kotlinx et reflect intouchés ; au prix des métadonnées Kotlin
relocatées (mensongères pour kotlin-reflect) et à condition qu'aucun type Storify ne franchisse la frontière du mod.

## 5. Les notions, en un tableau

| Notion | Rôle |
|---|---|
| `BaseStore<DATA>` | Le store : chargement, verrous, updates, callbacks, persistance ; implémente l'interface `Store<DATA>` |
| `StoreConfig` | Les options d'une instance : validation, auto-save et son intervalle, meta, deep copy, policy par défaut |
| `StoreFactory` | La factory : `create` (companion `Defaultable`), `createFromConstructor`, `createFromDefaultable`, `createFromResource` |
| `UpdatePolicy` | Ce qu'un update capture et notifie : `SNAPSHOT`, `SHALLOW` ou `SKIP` |
| `Operation` / `CapturedValue` | Ce que reçoivent les callbacks : le type d'opération, et les valeurs avant et après (copie profonde, lecture directe, ou indisponible) |
| `StoreFormat` | Le contrat d'un format : extension, encode/decode à sérialiseur explicite ; `JsonFormat`, `TomlFormat` et `Json5Format` fournis, formats tiers via `StoreFormats.registerFormat` |
| `Validator` / `ValidationContext` | La validation : conditions, erreurs à chemin complet, imbrication (`validateNested`, `validateEach`) |
| `StoreMeta` | Le sidecar `<fichier>.meta.json` : createdAt, lastModified, version, données libres |
| `Defaultable` | Le fournisseur de données par défaut |

## 6. Construire et tester

```bash
.\gradlew build
```

Le build exige un JDK 25 (toolchain) ; les tests tournent sous JUnit (plateforme unifiée JUnit 6) et écrivent leurs fichiers dans
`build\tmp\storify-tests`. La visite guidée commentée de l'API vit dans `src\test\kotlin\fr\moulou\storify\demos\HomesModDemo.kt` : cinq démos
exécutables sur un domaine réel de mod (homes, téléportation, délai, cooldown), chacune repartant d'un dossier vierge. Le benchmark des copies profondes s'exécute avec les tests. L'essai en conditions réelles se fait depuis le banc :
`.\gradlew runServer` dans `..\Storibench`, dont le README décrit les scénarios et les commandes en jeu.

La publication : `.\gradlew publishToMavenLocal` répète le circuit sans secret (dépôt Maven local) ; `.\gradlew publish` pousse sur Repsy, le
jeton arrivant par la chaîne de secrets (`dev-secrets.ps1 -Apply REPSY_MAVEN_TOKEN`) dans le terminal qui publie, jamais autrement.

## 7. Les limites connues

En toute franchise, mesurées au banc et par les tests ; le détail et les remèdes vivent dans `Docs\chantiers.md` :

- la validation à l'update est un opt-in (`validateOnUpdate`) volontairement non recommandé : chaque geste copie la racine entière et valide sous
  verrou ; préférez des contrôles métier avant de muter, `validateNow()` et la revalidation du reload couvrent le reste ;
- le défaut de `defaultUpdatePolicy` est `SKIP` : les callbacks se taisent tant qu'une policy ne les allume pas (par annotation ou par config) ;
  la persistance, elle, est garantie quelle que soit la policy, et depuis C-22 l'enregistrement d'un callback voué au silence le signale au log ;
- en JSON5, les commentaires et le style d'un fichier édité à la main survivent aux sauvegardes (C-26 : la réconciliation ne réécrit que les
  valeurs changées ; un tableau modifié se remplace entier, ses commentaires intérieurs avec). En JSON et TOML, la sauvegarde réécrit toujours
  le fichier entier.

## 8. La documentation

- `Docs\architecture.md` : comment la lib est faite, mécanisme par mécanisme.
- `Docs\chantiers.md` : le bilan (forces et faiblesses) et la liste priorisée de tout ce qui est à revoir, refaire ou construire.
- `Docs\info.md` et `Docs\TODO` : les documents historiques (l'ancien brief d'analyse et les premières notes), absorbés par les deux précédents.
