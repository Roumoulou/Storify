package fr.moulou.storify.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/*
 * Les aides communes de la suite de tests (C-14).
 *
 * Les règles de la maison :
 *  - chaque test travaille dans son propre dossier jetable sous build\tmp\storify-tests
 *    (gradlew clean l'emporte, et aucun test ne touche jamais un chemin hors du build) ;
 *  - les fixtures à @StorePath fixe se réinitialisent par [resetAnnotatedFile] avant usage ;
 *  - aucune attente par sleep nu : [awaitTrue] borne toujours l'attente.
 */

/** Un dossier neuf et unique pour un test. */
fun newStoreDir(): Path = Paths.get("build", "tmp", "storify-tests", UUID.randomUUID().toString()).also { Files.createDirectories(it) }

/** Un chemin de fichier de store dans un dossier neuf et unique. */
fun newStorePath(fileName: String): Path = newStoreDir().resolve(fileName)

/** Supprime le fichier d'une fixture à @StorePath fixe (et son sidecar), pour repartir d'un état connu. */
fun resetAnnotatedFile(annotatedPath: String) {
    val path = Paths.get(annotatedPath)
    Files.deleteIfExists(path)
    Files.deleteIfExists(path.resolveSibling("${path.fileName}.meta.json"))
}

/** Attente bornée : rend vrai dès que [condition] passe, faux après [timeoutMs]. */
fun awaitTrue(timeoutMs: Long = 5_000, stepMs: Long = 25, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(stepMs)
    }
    return condition()
}
