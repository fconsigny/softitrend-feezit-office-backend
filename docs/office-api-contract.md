# Contrat Office v1 + JR-03 — façade management

## Périmètre et ownership

François seul ; pas inscription, SSO, équipe, paiement, stockage Office ou migration de données. Backend Office WebFlux façade de Feezit, frontend JavaScript React. Identifiants réels et mise en production hors scope des changements et tests de développement décrits ici ; la livraison initiale distincte est documentée dans `Readme.md`. Les tests de frontière utilisent des données synthétiques et un serveur HTTP upstream de test ; ils ne sont pas l'intégration avec le vrai Feezit isolé, réservée à la recette distincte du parent.

Les extensions JR-03 suivent `../../jury-release-20261013/release-contract.md` (décision du 13 octobre 2026). Ce document décrit le contrat de transport du candidat local, pas sa livraison en production. Feezit possède les DTO, validations métier et projections ; Office ne les recopie pas dans une deuxième couche métier.

## Session Office

- GET `/office-api/csrf` public : `{token,headerName}` (Spring session CSRF) ; token uniquement en mémoire client. À renouveler après login/logout.
- POST `/office-api/auth/login` : form URL encoded `{username,password}`, header CSRF obligatoire ; 204 succès, 401 identifiants refusés, 403 CSRF absent. Aucun token management au navigateur.
- GET `/office-api/session` : `{username}` ; 401 sans session.
- POST `/office-api/auth/logout` avec CSRF : 204, session invalidée.
- Session cookie HttpOnly, SameSite Strict, Secure sauf profil dev/test local ; durée limitée, pas remember-me. Credential serveur `OFFICE_OPERATOR_USERNAME` + `OFFICE_OPERATOR_PASSWORD_HASH` (bcrypt). Absence de configuration => pas accès. Aucun compte par défaut ou mot de passe en clair en configuration. Ne pas utiliser identités créateur pour Office.
- Backend écoute loopback par défaut port 8091 ; frontend dev loopback 3011 avec proxy `/office-api` vers 8091. L'exposition distante et le provisioning relèvent de l'exploitation distincte, pas de ce contrat de développement.

## Façade autorisée

`/office-api/management/...` correspond exactement à `/api/management/...` Feezit pour les routes ci-dessous. GET session nécessaire ; mutations session + CSRF. Forward exclusivement via liste blanche méthode/chemin, headers fixes serveur ; jamais proxy générique ni en-têtes navigateur réutilisés. Configuration `FEEZIT_API_BASE_URL` + `FEEZIT_MANAGEMENT_SECRET` côté serveur. URL HTTP localhost permis dev/test, HTTPS requis autrement, pas credentials dans URL, redirects upstream non suivies, timeout et taille réponse bornés. Rien ne lit les tables Feezit.

Méthodes / suffixes admis :
- GET `brands`, PUT `brands/aliases`
- POST `reservations`
- GET/POST `waitlist`, GET/POST `waitlist/responses` (GET query `store` obligatoire)
- GET `promos/{UUID}`, PUT `promos/{UUID}/content` (aucun query paramètre)
- GET/PUT `promos/{UUID}/banner` (aucun query paramètre ; limites dédiées ci-dessous)
- PUT `promos/{UUID}/description` (aucun query paramètre ; limite habituelle 256 KiB)
- PUT `promos/{UUID}/destination`, GET `promos/{UUID}/participation` (aucun query paramètre)
- POST `promos/{UUID}/reports`, POST `promos/{UUID}/fraud-removal`, PUT `promos/{UUID}/resolve`
- GET `accounts/{UUID}/stats` (query from/to dates ISO optionnelles), GET/PUT/DELETE `accounts/{UUID}/moderation`, PUT/DELETE `accounts/{UUID}/ban`, POST `accounts/{UUID}/profile`
- GET `outbox`, PUT `outbox/{UUID}/sent`, GET `email-deliveries`
- GET `outbox-page` (query page/size seulement), GET `operations-status` (aucun query paramètre)
- POST `jobs/run` (confirmation UI explicite : effets réels échéances)
- GET `accounts`, GET `promos`, GET `incidents` : extension de lecture dédiée décrite ci-dessous.

Payloads réservations/modération/fraude/attente etc. : contrat Feezit `docs/api-contract.md` et DTO/controllers code live autoritatifs. Préserver codes et identifiants exactement ; aucune majuscule automatique. Réponses SQL historiques en MAJUSCULES et stats DTO en camelCase. Propager statuts métier ; pour erreurs, JSON `{status,code,message}` expurgé ; panne upstream 502, timeout 504, sans URL/secret/body brut externe. Pas prétendre succès quand upstream échoue. Cache-Control no-store. Seuls query paramètres propres à chaque route sont permis.

Les queries sont contrôlées **avant tout appel upstream**, après décodage et sans trim ni troncature : `q` optionnel sur accounts/promos ≤ 200 caractères ; `store` obligatoire non blanc sur GET waitlist et waitlist/responses ≤ 100. Pages 0..10000, tailles 1..100 ; dates from/to des stats et autres bornes existantes conservées. Clé inconnue, dupliquée (même encodée), caractère de contrôle ou borne dépassée → 400 VALIDATION. Pas de query pour les mutations ; pas de filtre status ajouté à outbox-page. Les valeurs sont réencodées comme valeurs, jamais interprétées comme une autre clé/URL. Les chemins bruts restent stricts : UUID 8-4-4-4-12 hexadécimal, pas de décodage/normalisation avant la liste blanche, de wildcard ni de sous-route libre.

## Extensions additives JR-03

### Destination d'une offre

- PUT `/office-api/management/promos/{UUID}/destination`, aucun query : body métier exact `{ "destinationUrl": "https://merchant.example/offer" }`.
- Validation **Feezit** : chaîne explicitement présente, non nulle/non vide, maximum 2048, URL HTTP(S) absolue sans credentials selon Rules. Aucun autre champ mutable. Office contrôle seulement l'enveloppe objet JSON et la taille de transport ; il relaie l'objet exact, même invalide métier, puis assainit le refus propriétaire. Il n'injecte ni défauts, ni champs, ni changement de casse.
- 200 → DTO Promo existant `{id,ownerId,articleName,articleImage,articleDescription,category,conditions,code,startDate,endDate,store,destinationUrl,createdAt,ownerName,ownerUsername,ownerAvatar,status,publicationStatus}` ; valeurs nullable et dates ISO inchangées. 400 invalide ; 404 offre absente.
- Seule destination modifiée sous verrou de participation côté Feezit. Publication, dates, procédure/outbox, code/marque et tous les autres champs inchangés. Une destination désormais valide ne republie pas : le créateur doit encore enregistrer l'offre. L'Office n'exécute aucune transition de publication.

### Participation enregistrée

- GET `/office-api/management/promos/{UUID}/participation`, aucun query : 200 `{promoId,ownerId,agreementReceivedAt,agreementReference,documentVersion,reservationExpiresAt}` ; 404 absent.
- Les instants UTC ISO et les références proviennent des colonnes Feezit ; `reservationExpiresAt` = accord reçu + 7 jours. Aucune substitution par createdAt, calcul de date Office, interprétation de références textuelles ou ajout au DTO public. Email original et pièces justificatives hors projection.

### Outbox paginée

- GET `/office-api/management/outbox-page?page=0&size=20` : 200 Page `{items,total,page,size}`. Défauts 0/20 appliqués par Feezit quand omis ; Office n'ajoute pas de query par défaut. Page 0..10000, size 1..100 ; query invalide → 400 avant upstream.
- Items camelCase exacts `{id,ownerId,resourceId,kind,recipient,deliveryStatus,createdAt,sentAt,deliveryReference}` ; nulls réels conservés. Tri propriétaire `created_at DESC,id` et compteur cohérent, même pour une page vide. Office n'effectue ni tri, ni comptage, ni pagination en mémoire.
- Legacy GET `/outbox` reste un tableau `{ID,OWNER_ID,RESOURCE_ID,KIND,RECIPIENT,DELIVERY_STATUS,CREATED_AT,SENT_AT,DELIVERY_REFERENCE}[]`. La projection explicite est la responsabilité Feezit : aucun `claim_token`, `provider_request_id`, `failure_code`, `claimed_at` ou `dedupe_key` dans ces deux lectures. Aucun stockage/projection SQL ajouté à Office.
- PUT legacy `/outbox/{UUID}/sent` reste relayé pour compatibilité ; le garde Feezit **409 même sans fournisseur actif** est conservé. Aucun mécanisme d'attestation ou contournement Office. `email-deliveries` reste limité aux **100 dernières notifications**, pas un total historique.

### Configuration opérationnelle

- GET `/office-api/management/operations-status`, aucun query : 200 `{emailTransportEnabled:boolean,participationJobsEnabled:boolean,observedAt:ISO Instant UTC}`.
- Valeurs de configuration effective et horloge **Feezit**, pas celles du processus Office. Pas de test de disponibilité fournisseur, dernier passage de job, preuve d'envoi/réception ou secret/URL/identifiant fournisseur. Aucun fournisseur ni émission ajoutés par cette lecture.

### Garanties communes et niveau de preuve

Session opérateur obligatoire pour les quatre routes ; PUT destination exige CSRF courant. Bearer créateur et secret navigateur n'autorisent rien. `X-Management-Secret` est injecté exclusivement par le vrai WebClient serveur ; cookies/Bearer/CSRF/headers arbitraires navigateur non transmis, Set-Cookie/Location/cache public upstream non recopiés.

Timeout global/connexion/réponse et limite 256 KiB inchangés ; pas de redirection/retry. 4xx propriétaire conservés avec messages reconstruits (`400 VALIDATION`, `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 NOT_FOUND`, `409 CONFLICT`, `429 RATE_LIMITED`, autre `UPSTREAM_REJECTED`) ; 5xx/réseau/redirection/JSON impropre/secret reflété → 502 UPSTREAM_UNAVAILABLE, délai → 504 UPSTREAM_TIMEOUT, upstream non configuré → 503 UPSTREAM_DISABLED. JSON de succès relayé sans réécriture après les gardes existants. Réponses privées no-store. L'exception préexistante POST reports 202 vide et les réponses 204 sont conservées, pas généralisées à un 200 vide.

`JuryManagementHttpTest` exerce la vraie sécurité Office, Netty et WebClient vers un serveur HTTP loopback à port éphémère, arrêté après tests. Les fixtures vérifient le transport exact des DTO ; **elles ne prouvent ni validation/persistance/projection propriétaire, ni garde DB/outbox, ni configuration effective du vrai Feezit, ni intégration frontend**. RED/GREEN, matrice des refus et résultats : `../../jury-release-20261013/implementation-office-backend.md` et `.json`. L'intégration réelle quatre applications/PostgreSQL reste le gate du parent.

## Contenu d’une offre management

- GET `/office-api/management/promos/{UUID}` → 200, DTO Promo existant Feezit, y compris offres internes `draft` / `masked` et dates nullable ; 404 si absent. Ce n’est pas une lecture du catalogue public.
- PUT `/office-api/management/promos/{UUID}/content` → 200, DTO Promo existant ; body JSON `{articleDescription,articleImage}`, deux chaînes obligatoires, chaînes vides autorisées. Description ≤ 2000 caractères ; image vide ou URL HTTP(S) valide selon les règles Feezit. Feezit reste responsable de ces validations métier : Office conserve sa politique d’enveloppe JSON objet et transmet le contenu exact sans réécriture ni duplication des règles.
- POST `/office-api/management/reservations` accepte en plus `articleDescription` et `articleImage` optionnels, défaut vide côté Feezit. Les autres champs et statuts existants sont inchangés ; Office ne matérialise pas les valeurs par défaut dans le JSON transmis.
- Réserver reste une création de brouillon. Éditer le contenu ne force aucune publication et ne modifie pas les dates ; le DTO/status renvoyé par Feezit est conservé. Aucun champ de publication ni de dates n’est injecté par Office.
- Lecture : session opérateur ; écriture : session + CSRF. UUID canonique, méthode et chemin exacts seuls autorisés ; aucun query paramètre, méthode supplémentaire ou sous-route arbitraire. Secret management exclusivement serveur ; erreurs upstream expurgées selon la politique existante.
- Preuves Office : `docs/promo-content-verification.json`. Le serveur upstream factice vérifie la frontière HTTP, pas les validations métier ni l’intégration du vrai Feezit.

## Bannière promotionnelle — 15 octobre 2026

Contrat commun : `../../continuous-banner-20261015/banner-contract.md`. GET/PUT exacts
`/office-api/management/promos/{UUID}/banner` relaient uniquement
`/api/management/promos/{UUID}/banner`. Session opérateur obligatoire ; PUT exige le
CSRF courant. Aucun query, autre méthode, chemin encodé ou sous-chemin libre.

- PUT JSON objet, sans réécriture : `{"url":"https://images.example.test/banner.png"}`,
  `{"url":""}` pour effacer, ou `{"contentType":"image/png","base64":"..."}` (`image/jpeg`
  également). Feezit valide les formes exclusives, clés, URL, octets et image ; Office
  ne décode pas le Base64, ne télécharge aucune URL et ne stocke aucune image.
  200 retourne le DTO Promo propriétaire inchangé, sans transition métier Office.
- GET retourne le JSON privé `{url,contentType,base64}` : type et Base64 sont null
  pour une URL externe ou une image absente. La fixture de transport ne garantit pas
  les règles de publication ou la persistance Feezit.
- **Ces deux routes seulement** agrègent request et response à **1 500 000 octets**
  maximum, inclusivement, via `DataBufferUtils.join` avec libération des buffers.
  Un corps entrant trop grand retourne 413 `PAYLOAD_TOO_LARGE` avant upstream ; une
  réponse de succès trop grande retourne 502 `UPSTREAM_UNAVAILABLE` expurgé.
  Les réponses privées restent `no-store` ; secret et headers upstream non exposés.
- `spring.codec.max-in-memory-size=256KB` et le codec du vrai WebClient gateway
  restent à 256 KiB pour toutes les autres routes. Timeouts, absence de retry et
  refus des redirections inchangés. Aucun accès DB Office ni nouvelle configuration.

`BannerManagementHttpTest` exerce HTTP Office/Netty/WebClient contre un serveur
loopback synthétique : URL, PNG/JPEG à taille fichier 1 MiB, effacement, lecture privée,
session/CSRF, refus de routage/query, tailles exactes et erreurs expurgées. Les octets
synthétiques ne sont pas une preuve de validation ImageIO ou d'intégration Feezit.
Sorties et manifeste : `../../continuous-banner-20261015/feature/office-backend/`.

Précision additive du contrat parent : PUT `promos/{UUID}/description` relaie seulement
le JSON `{"articleDescription":"..."}` vers le même suffixe Feezit, réponse Promo.
Session + CSRF obligatoires, aucun query ni autre méthode ; limites request/response
habituelles **256 KiB**. Feezit valide la chaîne explicite ≤ 2000, vide autorisé, et
écrit seulement la description ; Office n'injecte aucune image depuis un ancien
brouillon. Le PUT `content` historique avec ses deux champs reste inchangé.

## Extension minimale lecture management Feezit

Ajout uniquement au backend Feezit propriétaire, sans changer les parcours existants :
- GET `/api/management/accounts?page=0&size=20&q=` => `{items,total,page,size}`, items `{id,username,name,banned,visibility}`. `visibility` : public | masked | absent. Aucun email, password hash, session/token, IP ou preuve d’âge retourné. Compte sans profil et compte banni restent listables. Tri ID stable ; q recherche pseudo/nom/ID.
- GET `/api/management/promos?page=0&size=20&q=` => Page des DTO Promo existants y compris draft/masked, q code/marque/article. Tri createdAt DESC puis ID.
- GET `/api/management/incidents?page=0&size=20` => Page items `{id,promoId,ownerId,kind,openedAt,firstSentAt}` d'après schema réel. Ne pas inventer de colonne ; si aucun champ id propre, utiliser clé promoId. `firstSentAt` nullable. Aucun email signalant retourné.
- Page 0..10000, size 1..100. Toutes lectures secret management requis, Bearer créateur insuffisant. Transactions/autorisation existantes préservées. Contrat incidents pourra être ajusté uniquement selon schéma réel et communiqué aux deux agents.

## Écarts live vérifiés le 10 octobre 2026

- Le garde `ManagedEmailConfirmationGuard` de Feezit refuse actuellement **tous** les PUT outbox/{UUID}/sent par 409, même fournisseur désactivé. Le contrôleur legacy existe mais est inaccessible. Office préserve ce refus ; l’écran n’expose plus le formulaire et indique cette limitation. Aucun contournement ni modification du garde.
- POST promos/{UUID}/reports réussit par **202 sans corps**, pas par JSON. La façade conserve ce succès vide spécifique ; les autres réponses nécessitant des données restent validées.
- Incidents : `kind:null`, `openedAt:null`, `firstSentAt` reprend le champ persistant nullable ; aucune date ou cause inventée.

## Frontend

Navigation tableau opérationnel (pas KPI global inventé), comptes, offres/réservations, marques, attente/réponses, signalements/modération, stats quotidiennes, notifications. Formulaires explicites, confirmations des conséquences sensibles, états vide/loading/erreur, succès uniquement après serveur, annulation des réponses obsolètes et nettoyage des données à logout/401. Aucun stockage browser de credential/session/management secret. Pas revenus, ventes ou uniques déduits. Pending distinct de sent ; les émissions attestées sont relues, mais aucune attestation manuelle n’est proposée tant que le garde Feezit la refuse. Compte absent de profil n’est pas supprimé. Lever un ban ne restaure pas contenu. Pas attribution automatique ni rang ancienneté d’attente.
