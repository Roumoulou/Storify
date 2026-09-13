# Storify

Une bibliothèque Kotlin de stores de données et de configuration sur fichier, pensée d'abord pour les mods Minecraft server-side et utilisable dans
tout projet JVM. Un store attelle une data class sérialisable à un fichier JSON ou TOML, et fournit autour tout ce qu'un mod réclame : un accès
global thread-safe, des mises à jour typées propriété par propriété, des callbacks, une sauvegarde automatique, une validation au chargement avec
rapport d'erreurs détaillé, et un sidecar de métadonnées.

## 1. L'état du projet

- Coordonnées : `fr.moulou:storify`, version `0.0.1-SNAPSHOT-02`. Aucune release publiée : le circuit de publication (Repsy) est en veille et son
  câblage est cassé (voir `Docs\chantiers.md`).
- Consommation actuelle : par build composite, sans publication. Le consommateur de référence est Storibench, le banc d'essai en conditions réelles
  (un mod Fabric pour Minecraft 26.2), qui vit hors de ce dépôt, dans le classeur : `..\08-related-projects\storibench`.
- Build et tests : verts au 2026-09-13, sur la stack ci-dessous.
- L'API n'est pas encore stabilisée : des chantiers d'API restent ouverts (encapsulation, point d'extension des formats). La liste complète vit
  dans `Docs\chantiers.md`.
- Pas encore de dépôt Git : le `git init` est un chantier planifié, préalable aux refactors.
- Licence : propriétaire pour l'instant (`LICENSE.txt`, tous droits réservés) ; le choix d'une licence réelle reste à trancher.

| Outil | Version |
|---|---|
| Gradle (wrapper) | 9.7.1 |
| Kotlin | 2.4.20 |
| Java (toolchain) | 25 |
| kotlinx.serialization | 1.11.0 |
| tomlkt | 0.6.1 |
| kotlinx-datetime | 0.8.0 |

## 2. Les fonctionnalités

- **Deux formats de fichier** : JSON (`JsonFormat`) et TOML (`TomlFormat`), résolus par l'extension du chemin quand on ne les précise pas.
- **Quatre sources de données initiales**, quand le fichier n'existe pas encore : le constructeur sans argument de la data class, son companion
  `Defaultable`, une classe `Defaultable` externe, ou une ressource embarquée dans le jar copiée au premier lancement.
- **Configuration par annotations ou par code**, avec la préséance explicite > annotation > défaut : `@StorePath`, `@StoreFileFormat`,
  `@StoreConfiguration`, `@StoreValidator`, `@StoreDefaultResource`, `@StoreUpdatePolicy`.
- **Accès global thread-safe** : `store.data` sous read lock, mises à jour sous write lock, callbacks notifiés hors du lock.
- **Mises à jour typées** par référence de propriété : `set` et `setIn` (remplacer une valeur), `mutate` et `mutateIn` (modifier un objet mutable en
  place, avec navigation dans l'arborescence), `transaction` (tout ou rien, avec rollback sur exception).
- **Callbacks** : update global, update ciblé sur une propriété (`registerOnUpdateOn`), save, reload ; chaque notification porte une `Operation`
  avec les valeurs avant et après (`CapturedValue`).
- **Politiques de capture par propriété** (`UpdatePolicy`) : `SNAPSHOT` (copie profonde avant et après), `SHALLOW` (références seules), `SKIP`
  (silence complet).
- **Persistance** : sauvegarde immédiate (`saveImmediate`), auto-save périodique avec pause et reprise, sauvegarde au hook d'arrêt de la JVM,
  rechargement depuis le fichier (`reloadFromFile`), et écriture atomique partout (fichier temporaire puis déplacement atomique : jamais de
  fichier tronqué, même en cas de crash en pleine écriture).
- **Fin de vie propre** : les stores sont `AutoCloseable` ; `close()` annule le tick, arrête le planificateur, désarme le hook d'arrêt et fait une
  sauvegarde d'adieu si nécessaire ; un store fermé reste lisible et refuse les écritures.
- **Validation au chargement** : un `Validator` explicite ou résolu par annotation, un `ValidationContext` riche (imbrication, collections, chemins
  d'erreur), et un rapport d'erreurs détaillé, enrichi des numéros de ligne pour les fichiers JSON.
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

## 4. Les notions, en un tableau

| Notion | Rôle |
|---|---|
| `BaseStore<DATA>` | Le store : chargement, verrous, updates, callbacks, persistance ; implémente l'interface `Store<DATA>` |
| `StoreConfig` | Les options d'une instance : validation, auto-save et son intervalle, meta, deep copy, policy par défaut |
| `StoreFactory` | La factory : `create` (companion `Defaultable`), `createFromConstructor`, `createFromDefaultable`, `createFromResource` |
| `UpdatePolicy` | Ce qu'un update capture et notifie : `SNAPSHOT`, `SHALLOW` ou `SKIP` |
| `Operation` / `CapturedValue` | Ce que reçoivent les callbacks : le type d'opération, et les valeurs avant et après (copie profonde, lecture directe, ou indisponible) |
| `StoreFormat` | Le format de fichier ; `JsonFormat` et `TomlFormat` fournis |
| `Validator` / `ValidationContext` | La validation : conditions, erreurs à chemin complet, imbrication (`validateNested`, `validateEach`) |
| `StoreMeta` | Le sidecar `<fichier>.meta.json` : createdAt, lastModified, version, données libres |
| `Defaultable` | Le fournisseur de données par défaut |

## 5. Construire et tester

```bash
.\gradlew build
```

Le build exige un JDK 25 (toolchain) ; les tests tournent sous JUnit (plateforme unifiée JUnit 6) et écrivent leurs fichiers dans
`build\tmp\storify-tests`. Le benchmark des copies profondes s'exécute avec les tests. L'essai en conditions réelles se fait depuis le banc :
`.\gradlew runServer` dans `..\08-related-projects\storibench\main-project\Storibench`, dont le README décrit les scénarios et les commandes en jeu.

## 6. Les limites connues

En toute franchise, mesurées au banc et par les tests ; le détail et les remèdes vivent dans `Docs\chantiers.md` :

- la validation ne joue qu'au chargement initial : ni à l'update (le mécanisme a disparu du code), ni au `reloadFromFile`, et rien ne l'expose
  publiquement pour la déclencher à la demande ;
- le défaut de `defaultUpdatePolicy` est `SKIP` : les callbacks se taisent tant qu'une policy ne les allume pas (par annotation ou par config) ;
  la persistance, elle, est garantie quelle que soit la policy ;
- un format custom enregistré via `Utils.registerFormat` n'est pas accepté par la factory.

## 7. La documentation

- `Docs\architecture.md` : comment la lib est faite, mécanisme par mécanisme.
- `Docs\chantiers.md` : le bilan (forces et faiblesses) et la liste priorisée de tout ce qui est à revoir, refaire ou construire.
- `Docs\info.md` et `Docs\TODO` : les documents historiques (l'ancien brief d'analyse et les premières notes), absorbés par les deux précédents.
