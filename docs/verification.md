# Vérification backend Office — 10 octobre 2026

## Résultats initiaux du sous-agent — avant recette parent

- JDK 25.0.2, Maven 3.9.16, Boot 4.1.1, Security 7.1.1 : versions lues dans les outils/dépendances, pas présumées.
- `scripts/mvn.sh clean verify` : 75 tests/gates, 0 failure, 0 error, 0 skipped ; compilation Java 25 et jar Boot repackage.
- `python3 scripts/smoke-http.py --log-dir /home/stellabot/.hermes/cache/scratch/office-jar-smoke` : 14 assertions HTTP sur le vrai jar, upstream désactivé. Aucun transport email ni vrai Feezit exercé par ce run.
- XML Surefire : `target/surefire-reports/TEST-*.xml`. Synthèse persistée : `verification-results.json`.

## Preuves TDD RED → GREEN

Racine des logs : `/home/stellabot/.hermes/cache/scratch/` (scratch susceptible de purge).

| Tranche verticale | Rouge constaté | Vert |
|---|---|---|
| CSRF public/session cookie | `office-red-session.log` : attendu 200, obtenu 401 | `office-green-csrf.log` |
| Login/logout mono-opérateur | `office-red-login.log` : login attendu 204, obtenu 401 | `office-green-login.log` |
| Lecture brands/secret serveur | `office-red-proxy.log` : attendu 200, obtenu 403 | `office-green-proxy.log` |
| Ensemble des capacités et query whitelist | `office-red-capabilities.log` : routes refusées, query inconnue acceptée | `office-green-capabilities.log` |
| Erreurs, JSON hostile, limite réponse, réseau/timeout/redirect | `office-red-upstream-safety.log` : 7 assertions en échec | `office-green-upstream-safety.log` |
| Config URL/cookie/absence upstream fail-closed | `office-red-config-safety.log` : 10 assertions en échec | `office-green-config-safety.log` |
| Payload/query en entrée | `office-red-input-safety.log` : JSON/text acceptés, échappement query incorrect | `office-green-input-safety.log` |
| Budget login | `office-red-login-limit.log` : attendu 429, obtenu 401 | `office-green-login-limit.log` |
| Rotation CSRF post-login | `office-red-csrf-rotation.log` : token ancien permettait logout 204 au lieu de 403 | `office-green-csrf-rotation.log` |
| Empreintes BCrypt invalides | `office-red-hash-safety.log` : coût 03/99 créait un User | `office-verify.log` |

Log final build : `office-verify.log`. Versions : `office-toolchain.log`, `office-security-versions.log`. Smoke : `office-smoke-result.log`, `office-jar-smoke/office-jar.log`, `office-jar-smoke/checks.json`.

Des erreurs techniques initiales ont été corrigées sans les présenter comme RED fonctionnel : test placé hors du package d'une méthode package-private ; module Python bcrypt absent de l'interpréteur outil (fixture générée avec le Python hôte disponible) ; casse de l'attribut HttpOnly dans CookieJar. Le budget login avait d'abord été inséré avant AUTHENTICATION, **après** FORM_LOGIN : l'assertion HTTP restait rouge ; correction avant FORM_LOGIN, vert observé. Ces échecs ne sont pas masqués par des sorties simulées.

Les assertions ajoutées sur comportements déjà livrés (formes JSON de succès 201/202/204, body exact, login erroné, rejeu ancien cookie, routes refusées) sont des régressions, pas de nouveaux cycles RED revendiqués. Aucun mock security ou bypass CSRF n'est utilisé dans les parcours HTTP.

## Périmètre et limites de preuve

`WebTestClient.bindToServer` contacte les vrais ports Netty des contextes Spring. Le serveur upstream `com.sun.net.httpserver.HttpServer` est une fixture exclusivement test, pas Feezit. Il permet d'observer méthode/path/query/body/headers, de répondre 409 ou hostile, interrompre un échange, dépasser le délai et proposer une redirection. Il ne prouve aucune réservation réelle, effet d'un job, email envoyé, invariant DB, statistique réelle ni integration frontend.

Le parent doit exercer les parcours métier via le vrai Feezit isolé. Le script smoke peut vérifier les lectures et des commandes fournies explicitement ; aucun test contre production, credential réel, commit/push, écriture Obsidian, frontend ou Feezit n'a été effectué par ce sous-agent.

Avertissements JDK/Mockito : le listener Spring Boot test charge dynamiquement l'agent Mockito bien que les tests n'utilisent pas de mocks. La JVM signale ce chargement ainsi que le partage de classes modifié ; avertissements présents, pas considérés comme tests échoués ni occultés. La suite passe réellement. Pas d'audit CVE/SCA exhaustif ou de certification de sécurité revendiqué.

## Validation finale exécutée par le parent

- `scripts/mvn.sh verify` après régression 202 vide : **80 tests/gates**, 0 échec/erreur/ignoré. Le total initial de 75 ci-dessus est historique.
- RED ciblé : `ManagementHttpTest#preservesAcceptedEmptyReportWithoutContentType`, attendu202 obtenu502 ; GREEN puis vraie recette.
- `python3 scripts/integration-isolated.py` : **61 contrôles HTTP et 1 scénario Chromium réussis**, vrais jars Feezit/Office et frontend Vite, aucune interception HTTP. Comptes/données synthétiques, H2 jetable, loopback, transport email désactivé. Cleanup processus puis H2 effectué.
- `./mvnw verify` propriétaire Feezit : 132 tests, 1 ignoré H2, 0 échec/erreur. Suite sur PostgreSQL **jetable** : 132 tests, aucun ignoré/échec/erreur ; migrations/store management effectivement exercés. Smoke jar Feezit : 67 requêtes/102 assertions.
- Frontend : 33 tests, lint/build réussis ; deux parcours boundary Chromium desktop/mobile réussis, distincts de la recette réelle.
- Confirmation manuelle outbox : le garde Feezit actuel refuse409 même fournisseur désactivé. Office ne le contourne pas et ne propose plus l’action en UI. Aucun email envoyé et aucune émission artificiellement attestée ; attente conservée après refus et état relu.
- Preuves persistées : `parent-verification.json`, `evidence/real-integration.json`, `evidence/postgresql.json`, `evidence/postgresql-cleanup.txt`. Logs détaillés scratch susceptibles de purge ; capture synthétique et log frontend dans le dépôt voisin.
- Revue initiale rouge sur le 202 vide ; correction parent exécutée et recontrôle indépendant **approuvé**, sans défaut bloquant ni problème de sécurité concret. Suggestions couvertes par trois régressions HTTP supplémentaires et vérification Chromium notice/absence Attester, toutes vertes ; aucune modification du code de production après cette approbation. Les 77 tests lus par le reviewer précédaient ces trois ajouts ; résultat final 80. Aucun déploiement, provisioning réel, commit ou push.

## Référence technique

Spring Security 7.1.1, documentation réactive CSRF : https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html . Repository `WebSessionServerCsrfTokenRepository`, `Mono<CsrfToken>` dans l'exchange, handler XOR/BREACH par défaut conservé. Le test HTTP a montré que la rotation d'ID de session ne supprimait pas à elle seule le token CSRF pré-login dans notre configuration : le success handler efface donc le token via le repository, sans crypto maison ni souscription manuelle. Le logout Spring invalide réellement la session ; l'ancien cookie donne 401 dans les tests et dans le smoke jar.
