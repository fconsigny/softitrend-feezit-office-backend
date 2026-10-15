# Feezit Office — Backend

Façade HTTP mono-opérateur Java 25 / Spring Boot 4.1.1 WebFlux. L'accès est réservé à François via **un seul compte configuré côté serveur**. Aucun compte par défaut, inscription publique, base Office ou accès aux tables Feezit. Les réservations, modérations, statistiques et échéances restent sous la responsabilité du backend Feezit.

## Build et vérification disponibles

```sh
scripts/mvn.sh -version
scripts/mvn.sh verify
python3 scripts/smoke-http.py
```

Le script Maven sélectionne `/home/stellabot/.local/jdk-25.0.2` et `/home/stellabot/.local/apache-maven-3.9.16`. Overrides : `FEEZIT_JAVA_HOME`, `OFFICE_MAVEN_HOME`. Maven Enforcer refuse une JVM hors Java 25 ; compilation release 25. Aucun wrapper Maven fictif n'est annoncé.

Jar produit : `target/feezit-office-backend-0.1.0-SNAPSHOT.jar`. Le smoke démarre **ce vrai jar**, sur un port loopback libre, avec credentials synthétiques ; il l'arrête dans un `finally`. Par défaut, aucun upstream n'est appelé : il vérifie session/CSRF/login/logout et le refus 503 d'une façade non configurée.

Preuves historiques : build initial **75 tests/gates**, vérification parent initiale **80** (`docs/parent-verification.json`), lot contenu **101** (`docs/promo-content-verification.json`). Elles ne constituent pas les résultats du candidat jury. Les tests `ManagementHttpTest` et `JuryManagementHttpTest` utilisent un serveur HTTP fixture exclusivement dans `src/test`, avec les vrais Netty/WebClient et la vraie sécurité Office : ils prouvent la frontière et le protocole, **pas les règles ni l'intégration métier du vrai Feezit**. Voir `docs/verification.md` pour l'historique et `../jury-release-20261013/implementation-office-backend.md` / `.json` pour les commandes, résultats et limites JR-03.

## Candidat jury — JR-03 (13 octobre 2026)

Extensions strictement additives, même préfixe `/office-api/management` :

| Méthode / chemin | Query admise | Réponse Feezit relayée |
|---|---|---|
| PUT `/promos/{UUID}/destination` | aucune | 200 Promo existant ; body `{destinationUrl}`, validations métier 400 / absent 404 dans Feezit |
| GET `/promos/{UUID}/participation` | aucune | 200 `{promoId,ownerId,agreementReceivedAt,agreementReference,documentVersion,reservationExpiresAt}` ; absent 404 |
| GET `/outbox-page` | page 0..10000, size 1..100 seulement | 200 `{items,total,page,size}` ; projection camelCase, nulls conservés |
| GET `/operations-status` | aucune | 200 `{emailTransportEnabled,participationJobsEnabled,observedAt}` |

`q` comptes/promos est limité à 200 caractères, `store` GET attente/réponses à 100, avant upstream et sans troncature. Les règles d'objet JSON/tailles, UUID, méthodes et queries, session/CSRF, secret serveur, erreurs privées, timeout et refus des redirects/retries sont conservées. Les nouveaux GET ne deviennent ni wildcard ni relais d'en-têtes navigateur.

Office ne valide pas à nouveau les règles métier de destination, ne calcule pas les dates d'accord, ne projette pas les tables et ne déduit pas de totaux d'une page. La source reste Feezit. `operations-status` décrit une **configuration**, pas la santé fournisseur, un dernier job ni un email envoyé. Le garde 409 d'attestation manuelle legacy reste ; `email-deliveries` ne représente que les 100 dernières notifications. Aucun fournisseur, stockage/migration ou faux transport ajouté.

Le contrat parent `../jury-release-20261013/release-contract.md` décide le produit 0.3.0 ; la version technique Maven/jar reste **0.1.0-SNAPSHOT**. Aucune livraison JR-03, intégration quatre applications, relecture production ou émission d'email n'est revendiquée par cette tranche Office. Pendant le travail parallèle, lancer les gates lourds et le package sous le verrou partagé :

```sh
flock ../jury-release-20261013/build.lock scripts/mvn.sh clean verify
flock ../jury-release-20261013/build.lock scripts/mvn.sh -DskipTests package
```

## Configuration serveur

| Variable | Effet |
|---|---|
| `OFFICE_OPERATOR_USERNAME` | Identité unique et exacte, sans compte de repli |
| `OFFICE_OPERATOR_PASSWORD_HASH` | Empreinte BCrypt `$2a$`, `$2b$` ou `$2y$`, coût 4..16 ; absence/invalidité désactive la connexion |
| `FEEZIT_API_BASE_URL` | Origine Feezit, sans identifiants, query, fragment ni préfixe de chemin |
| `FEEZIT_MANAGEMENT_SECRET` | Secret injecté uniquement dans l'appel serveur `X-Management-Secret` |
| `OFFICE_UPSTREAM_TIMEOUT_MS` | Délai global de l'échange HTTP, défaut 3000 ms, bornes 100..30000 |

Le coût 4 des fixtures n'est **pas** une recommandation de production. La phase de développement ne créait aucun mot de passe réel ; le provisioning de livraison distinct utilise une empreinte coût 12, sans secret dans les sources. Les variables doivent être injectées par l'exploitation ; ne pas les écrire dans Git ou dans les arguments CLI.

Écoute par défaut : `127.0.0.1:8091`. En dehors des profils `dev`/`test`, HTTPS est obligatoire vers Feezit et le cookie `SESSION` est Secure. Les profils locaux n'autorisent HTTP que vers `127.0.0.1`, `localhost` ou `::1`, et refusent une écoute non loopback. L'exposition distante et le reverse proxy TLS, hors de la phase initiale, sont désormais configurés séparément pour la livraison autorisée ; voir la section production. Le frontend doit rester same-origin (proxy de développement `/office-api` vers 8091) ; aucun CORS ouvert.

Après injection de la configuration, le démarrage hors smoke se fait avec le même jar :

```sh
/home/stellabot/.local/jdk-25.0.2/bin/java --enable-native-access=ALL-UNNAMED -jar target/feezit-office-backend-0.1.0-SNAPSHOT.jar
```

Sans configuration, le service peut démarrer pour ses contrôles locaux, mais aucune session opérateur n'est accordée ; sans origine/secret Feezit complet, la façade retourne 503 `UPSTREAM_DISABLED` sans tentative de réseau.

## Contrat et sécurité

Le contrat transport partagé est `docs/office-api-contract.md`, aligné pour JR-03 sur le contrat de release parent. `docs/requirements-matrix.json` conserve l'inventaire initial ; l'audit jury courant est `../jury-release-20261013/requirements-office.md`. La façade ne remplace pas les DTO et validations propriétaires Feezit.

- `GET /office-api/csrf` : `{token,headerName}` ; token Spring session-backed avec handler XOR/BREACH conservé, résolution réactive sans souscription manuelle.
- `POST /office-api/auth/login` : form-urlencoded `username/password` + header CSRF ; succès 204, refus 401, CSRF absent 403. ID de session changé à la connexion et token CSRF pré-login supprimé.
- `GET /office-api/session` : uniquement `{username}`, sinon 401.
- `POST /office-api/auth/logout` + CSRF : 204, session invalidée. Relire CSRF après connexion/déconnexion ; l'ancien cookie ne redonne pas accès.
- Cookie HttpOnly, SameSite Strict, Secure sauf profils locaux ; expiration d'inactivité serveur 30 minutes, sessions en mémoire perdues au redémarrage, pas remember-me.
- Budget global de connexion : 10 tentatives/minute, compteur unique à mémoire constante, aucune clé basée sur un header IP fourni par le client. Propriétés internes `office.login.max-attempts` (1..100) et `office.login.window-ms` (1000..3600000). Cela limite aussi les connexions correctes ; ce n'est pas une protection anti-DDoS.
- Façade exclusivement selon la liste blanche méthode/chemin/UUID. Aucune route `/catalog`, aucun proxy catch-all. Chemins encodés, sous-routes inconnues, méthodes non admises, queries supplémentaires/dupliquées et pagination/dates invalides sont refusés avant Feezit.
- Enveloppes de mutation JSON validées ; champs métier transmis sans changement de casse. La validation détaillée des DTO et les invariants métier restent dans Feezit, sans duplication ici.
- Corps entrants et réponses agrégées limités à 256 KiB, sauf GET/PUT exacts `promos/{UUID}/banner` : 1 500 000 octets dans chaque sens, sans agrandir les codecs globaux. Cette façade JSON transmet URL/effacement ou import Base64 PNG/JPEG à Feezit, sans DB, décodage d'image ni téléchargement d'URL Office ; voir le contrat API. Timeout global, connexion et réponse bornés ; pas de redirects ni retries automatiques. Aucun header navigateur, Bearer, cookie, Location ou Set-Cookie upstream n'est relayé.
- Succès JSON avec statut métier conservé ; exceptions existantes 204 vide et POST reports 202 vide, sans autoriser un 200 vide. Erreurs 4xx reconstruites `{status,code,message}` sans recopier le texte externe ; 5xx/redirection/réseau/réponse impropre => 502, délai => 504. Un secret management reflété dans une réponse de succès est refusé, y compris échappé en JSON. Messages volontairement génériques.
- Réponses privées no-store ; headers de sécurité Spring conservés. Pas de stockage local du secret ou des identifiants côté navigateur.

## Smoke contre Feezit réellement isolé

Le parent peut relancer, **sur une instance de test jetable uniquement**, après avoir injecté un secret synthétique identique dans les deux processus :

```sh
python3 scripts/smoke-http.py --feezit-url http://127.0.0.1:18080 --isolated-feezit
```

Le secret synthétique attendu par défaut est celui de la fixture smoke ; override dédié `OFFICE_SMOKE_MANAGEMENT_SECRET`. Le script refuse le port de production local 8087 et exige l'acknowledgement `--isolated-feezit`. Il exerce les lectures management réelles. `--commands chemin.json` permet des scénarios de mutations supplémentaires : tableau d'objets `{method,path,body,expectedStatus}`, chemins `/office-api/management/...` uniquement. Préparer les propriétaires/promos synthétiques dans Feezit isolé ; ce script ne touche pas sa DB directement. `--log-dir` conserve `office-jar.log` et `checks.json` sans mot de passe, token ni cookie dans le résultat.

## Architecture et limites

- `domain/ManagementRoute` : capacités autorisées et paramètres purs, sans Spring/Reactor.
- `application/ManagementGateway` : port de façade et réponse HTTP minimale.
- `adapters/http` : DTO session/CSRF, contrôleurs explicites, enveloppes/errors et budget de connexion.
- `adapters/upstream/FeezitHttpGateway` : WebClient non bloquant, assainissement des réponses.
- `infrastructure` : composition Spring Security, configuration de connexion et cookies.
- Gate ArchUnit : domaine indépendant et application sans dépendance vers adapters/infrastructure.

Pas de DB/migration Office, audit durable FO-07, SSO/MFA, rôles d'équipe, transport email ou revenus/KPI inventés. Le déploiement initial historique est distinct de ce candidat ; aucun commit/push ni déploiement par la tranche JR-03. Les sessions/rate limits ne sont pas distribués. Les erreurs génériques réduisent les détails de validation présentés dans l'UI. L'intégration métier avec Feezit réel et le frontend relève de la recette isolée du parent ; elle n'est pas revendiquée par le serveur fixture de test.

## Historique : livraison initiale — 12 octobre 2026

Le récit ci-dessous est une preuve historique, pas une relecture du runtime ni du candidat jury. L'évolution contenu et ses preuves ultérieures sont référencées séparément dans `docs/evidence/promo-content-real-integration.json` ; l'état de production courant reste à relire par le parent.

`https://office.feezit.softitrend.com`, même serveur que Feezit, backend Docker non-root lié à **127.0.0.1:8089**, projet Compose Office distinct. Compte réel `francois` provisionné hors Git ; secret management uniquement serveur. Reverse proxy HTTPS et certificat dédié actifs, renouvellement avec reload Apache éprouvé. **23 contrôles HTTP publics** réussis : session/CSRF/login/logout et lectures management, sans écriture métier ni émission d’email. **2 vues Chromium publiques** réussies, distinctes de l’authentification HTTP. PostgreSQL Feezit reste V13 ; voisins inchangés. Documentation, sources de livraison, identifiants privés, preuves et limites : `../office-production/README.md` dans le workspace Feezit et note Obsidian `Feezit/Office/feezit-office-deploiement-2026-10-12.md`.
