# Contrat Office v1 — façade management

## Périmètre et ownership

François seul ; pas inscription, SSO, équipe, paiement, stockage Office ou migration de données. Backend Office WebFlux façade de Feezit, frontend JavaScript React. Identifiants réels et mise en production hors scope des changements et tests de développement décrits ici ; la livraison initiale distincte est documentée dans `Readme.md`. Tous tests de développement utilisent données synthétiques et backend Feezit isolé.

## Session Office

- GET `/office-api/csrf` public : `{token,headerName}` (Spring session CSRF) ; token uniquement en mémoire client. À renouveler après login/logout.
- POST `/office-api/auth/login` : form URL encoded `{username,password}`, header CSRF obligatoire ; 204 succès, 401 identifiants refusés, 403 CSRF absent. Aucun token management au navigateur.
- GET `/office-api/session` : `{username}` ; 401 sans session.
- POST `/office-api/auth/logout` avec CSRF : 204, session invalidée.
- Session cookie HttpOnly, SameSite Strict, Secure sauf profil dev/test local ; durée limitée, pas remember-me. Credential serveur `OFFICE_OPERATOR_USERNAME` + `OFFICE_OPERATOR_PASSWORD_HASH` (bcrypt). Absence de configuration => pas accès. Aucun compte par défaut ou mot de passe en clair en configuration. Ne pas utiliser identités créateur pour Office.
- Backend écoute loopback par défaut port 8091 ; frontend dev loopback 3011 avec proxy `/office-api` vers 8091. Exposition distante et provisioning réel ultérieurs.

## Façade autorisée

`/office-api/management/...` correspond exactement à `/api/management/...` Feezit pour les routes ci-dessous. GET session nécessaire ; mutations session + CSRF. Forward exclusivement via liste blanche méthode/chemin, headers fixes serveur ; jamais proxy générique ni en-têtes navigateur réutilisés. Configuration `FEEZIT_API_BASE_URL` + `FEEZIT_MANAGEMENT_SECRET` côté serveur. URL HTTP localhost permis dev/test, HTTPS requis autrement, pas credentials dans URL, redirects upstream non suivies, timeout et taille réponse bornés. Rien ne lit les tables Feezit.

Méthodes / suffixes admis :
- GET `brands`, PUT `brands/aliases`
- POST `reservations`
- GET/POST `waitlist`, GET/POST `waitlist/responses` (GET query `store` obligatoire)
- GET `promos/{UUID}`, PUT `promos/{UUID}/content` (aucun query paramètre)
- POST `promos/{UUID}/reports`, POST `promos/{UUID}/fraud-removal`, PUT `promos/{UUID}/resolve`
- GET `accounts/{UUID}/stats` (query from/to dates ISO optionnelles), GET/PUT/DELETE `accounts/{UUID}/moderation`, PUT/DELETE `accounts/{UUID}/ban`, POST `accounts/{UUID}/profile`
- GET `outbox`, PUT `outbox/{UUID}/sent`, GET `email-deliveries`
- POST `jobs/run` (confirmation UI explicite : effets réels échéances)
- GET `accounts`, GET `promos`, GET `incidents` : extension de lecture dédiée décrite ci-dessous.

Payloads réservations/modération/fraude/attente etc. : contrat Feezit `docs/api-contract.md` et DTO/controllers code live autoritatifs. Préserver codes et identifiants exactement ; aucune majuscule automatique. Réponses SQL historiques en MAJUSCULES et stats DTO en camelCase. Propager statuts métier ; pour erreurs, JSON `{status,code,message}` expurgé ; panne upstream 502, timeout 504, sans URL/secret/body brut externe. Pas prétendre succès quand upstream échoue. Cache-Control no-store. Seuls query paramètres propres à chaque route sont permis.

## Contenu d’une offre management

- GET `/office-api/management/promos/{UUID}` → 200, DTO Promo existant Feezit, y compris offres internes `draft` / `masked` et dates nullable ; 404 si absent. Ce n’est pas une lecture du catalogue public.
- PUT `/office-api/management/promos/{UUID}/content` → 200, DTO Promo existant ; body JSON `{articleDescription,articleImage}`, deux chaînes obligatoires, chaînes vides autorisées. Description ≤ 2000 caractères ; image vide ou URL HTTP(S) valide selon les règles Feezit. Feezit reste responsable de ces validations métier : Office conserve sa politique d’enveloppe JSON objet et transmet le contenu exact sans réécriture ni duplication des règles.
- POST `/office-api/management/reservations` accepte en plus `articleDescription` et `articleImage` optionnels, défaut vide côté Feezit. Les autres champs et statuts existants sont inchangés ; Office ne matérialise pas les valeurs par défaut dans le JSON transmis.
- Réserver reste une création de brouillon. Éditer le contenu ne force aucune publication et ne modifie pas les dates ; le DTO/status renvoyé par Feezit est conservé. Aucun champ de publication ni de dates n’est injecté par Office.
- Lecture : session opérateur ; écriture : session + CSRF. UUID canonique, méthode et chemin exacts seuls autorisés ; aucun query paramètre, méthode supplémentaire ou sous-route arbitraire. Secret management exclusivement serveur ; erreurs upstream expurgées selon la politique existante.
- Preuves Office : `docs/promo-content-verification.json`. Le serveur upstream factice vérifie la frontière HTTP, pas les validations métier ni l’intégration du vrai Feezit.

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
