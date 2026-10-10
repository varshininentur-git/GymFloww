# GymFlow Management

GymFlow contains two independent applications inside this root: a Spring Boot REST backend and a React/Vite frontend. The UI uses the backend through HTTP APIs and cookie-based sessions. Backend package: `com.gym.management`.

## Prerequisites

- macOS Terminal or VS Code terminals (the commands below use `zsh`/POSIX shell syntax).
- Java 21. Maven is supplied by `backend/mvnw`.
- Node.js `^20.19.0` or `>=22.12.0` and npm, matching the Vite version in `frontend/package-lock.json`.
- Ports 8080 (backend) and 5173 (frontend) available.

## Stack and layout

- Backend: Java 21, Spring Boot 4.1.1, Maven, Spring MVC, Spring Data JPA, Bean Validation, Spring Security, H2.
- Frontend: React, Vite, JavaScript, React Router, Fetch API, CSS.

```text
management/
├── backend/
│   ├── pom.xml, mvnw, mvnw.cmd, .mvn/
│   ├── src/main/java/com/gym/management/{config,controller,dto,entity,exception,payment,repository,security,service}/
│   ├── src/main/resources/application.properties
│   ├── src/test/java/com/gym/management/
│   └── data/gymdb.mv.db
├── frontend/
│   ├── package.json, package-lock.json, index.html, vite.config.js, .env.example
│   └── src/{components,context,hooks,layouts,pages,services}/
├── README.md
└── .gitignore
```

The original H2 file was retained at `backend/data/gymdb.mv.db`. Hibernate uses `ddl-auto=update`, so records persist across backend restarts.

## Initialize the first administrator

There are no default usernames or passwords. For local development, initialize an admin by supplying both environment variables when starting the backend. The password must be at least 12 characters. In a VS Code backend terminal:

```sh
cd backend
read -r -p "Administrator username: " GYM_ADMIN_USERNAME
export GYM_ADMIN_USERNAME
read -r -s -p "Administrator password (12+ characters, max 72 UTF-8 bytes): " GYM_ADMIN_PASSWORD
printf '\n'
export GYM_ADMIN_PASSWORD
./mvnw spring-boot:run
unset GYM_ADMIN_USERNAME GYM_ADMIN_PASSWORD
```

The password is not echoed or logged. The initializer hashes it with BCrypt (work factor 12) and creates the account only if that username is not already present. It does not reset an existing account's password. Keep production credentials in a secret manager; do not put them in source code, `.env`, shell history, or this README. If only one bootstrap variable is set, startup fails with a configuration error rather than creating a partial account.

Admins can create additional accounts from **Staff accounts** in the frontend or through `POST /api/admin/users`. Passwords must be at least 12 characters and no more than 72 UTF-8 bytes (the BCrypt input limit). Account responses never include password hashes.

## Member account activation

Gym member records continue to be created through **Members** without a password. To link an existing record to a login, an ADMIN opens **Staff accounts → Member account activation**, selects the already registered member, and chooses a username. The server creates a random, 24-hour, single-use activation token and stores only its SHA-256 hash. The original member row is linked to the new account; activation never creates a second member record.

No email sender is configured in this project. For this local college-demo workflow, the admin must verify the member in person (or through the club's existing process), create the activation link, and send it directly to that member through a private channel. The link is shown once in the admin page and is not written to application logs. The member opens it, sets a password of at least 12 characters (maximum 72 UTF-8 bytes), then signs in with either the chosen username or the member record's current registered email. Do not send activation links in a public chat or commit them to source control.

Existing ADMIN/STAFF accounts and member records without accounts are left intact. The nullable unique `gym_users.member_id` relation and activation table are added using the current Hibernate `ddl-auto=update` behavior. On H2 startup, a narrow migration widens the legacy role enum column to `VARCHAR(20)` so it can store `MEMBER`; existing role values are preserved. No seed users or default passwords are created. Existing H2 data should be backed up before a schema-changing upgrade.

## Member fitness portal

The member workspace now includes **Progress & goals**, **Workouts**, **Achievements**, **Nutrition & water**, and **Notifications**. Measurements are stored as kilograms and centimetres. Weight goals require an existing weight check-in; measurement goals currently track a lower target value. Goal progress compares the saved starting measurement with the most recent matching measurement. General goals require the member to mark them complete. The tracker is informational and is not medical or dietary advice.

Workout plans reference a shared exercise catalogue. Ten starter exercises are inserted idempotently when the backend starts; existing catalogue records are never overwritten. A member can start a session from a plan or as a simple workout, then explicitly finish it with recorded sets/reps before it counts in history or badges. Submissions use unique idempotency keys, and one active session per member is allowed. Attendance streaks count distinct local calendar dates: a current streak continues through today or yesterday; longest streak is calculated across consecutive days. Badges are derived deterministically from persisted attendance/workout records (first visit, 5/10 visit-days, first/10 workouts and the configured current-period attendance goal).

The member dashboard includes **My Attendance Activity**, backed by the member-scoped `GET /api/member/attendance/activity?from=YYYY-MM-DD&to=YYYY-MM-DD` endpoint. It accepts up to 366 inclusive dates and rejects future end dates. Calendar intensity represents actual check-in records per existing local `attendanceDate`: neutral for zero, light for one, medium for two, and darkest for three or more. A star means at least one saved visit. Multiple completed check-ins on a day increase its displayed visit count, but streaks count that date only once. Current streak includes today or yesterday as the latest visit day; longest streak counts uninterrupted distinct dates across the member's recorded history. Dates and timestamps use the backend JVM's configured local timezone, matching existing attendance storage. The response contains only the authenticated member's records and date/count/timestamp details.

Meals and water entries are personal logs. Calorie and macro totals are sums of values the member entered; optional targets are chosen by the member and are not recommendations. Water uses millilitres. Expiry reminders (7 days, 1 day, and expired) and member-targeted announcements are in-app only. They are generated idempotently when the member opens the notification centre; no email, push delivery, or scheduled job is configured. If a membership is renewed before the member opens notifications, obsolete expiry notices for the previous date are removed.

All new records are scoped to the authenticated `GymUser.member` relation; APIs do not accept member ownership IDs. New tables and constraints are additive under the existing H2 `ddl-auto=update` configuration. The existing H2 file is not reset or reseeded with demo member activity.

## Run the applications separately

Start the backend in one terminal (include the admin setup above the first time):

```sh
cd backend
./mvnw spring-boot:run
```

In another terminal:

```sh
cd frontend
[ -f .env ] || cp .env.example .env
npm install
npm run dev
```

Open `http://localhost:5173`; the API listens at `http://localhost:8080`. Vite proxies `/api` to the backend during development, so copy `frontend/.env.example` to `frontend/.env` (its `/api` base works both locally and in production). Restart Vite after changing environment values. Direct cross-origin local calls are also allowed from `http://localhost:5173` and `http://127.0.0.1:5173` by the backend's development CORS configuration.

Configuration variables:

| Variable | Used by | Purpose |
|---|---|---|
| `GYM_ADMIN_USERNAME` | Backend startup | Optional one-time local admin initializer username; must be paired with the password below |
| `GYM_ADMIN_PASSWORD` | Backend startup | Initial admin password; at least 12 characters and at most 72 UTF-8 bytes |
| `SESSION_COOKIE_SECURE` | Backend | `false` for local HTTP; set `true` when deployed over HTTPS |
| `SESSION_COOKIE_SAME_SITE` | Backend | `lax` for same-site HTTPS hosting; use `none` only for cross-site HTTPS and with Secure cookies |
| `GYM_CORS_ALLOWED_ORIGINS` | Backend | Comma-separated exact browser origins; production must contain the frontend origin(s), not `*` |
| `H2_CONSOLE_ENABLED` | Backend | `true` locally; set `false` for hosted environments |
| `VITE_API_BASE_URL` | Frontend build | API base URL ending in `/api`; set `/api` in Vercel and for local Vite development |
| `VITE_PUBLIC_APP_URL` | Frontend build | Public frontend origin used when ADMIN generates member activation links |
| `SPRING_DATASOURCE_URL` | Backend (Spring Boot override) | Optional datasource URL override. Leave unset for the preserved `backend/data/gymdb.mv.db`; use a scratch URL for tests. |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Backend | H2 credentials; supply through the host's secret manager for a hosted H2 file |
| `PORT` | Backend hosting platform | Spring Boot listens on this assigned port, defaulting to 8080 locally |
| `SPRING_PROFILES_ACTIVE` | Backend (Spring Boot override) | Use `prod` on the hosted backend; omit locally. The production profile enables secure cookies and disables H2 console by default. |

Build and test from their respective project folders:

```sh
cd backend && ./mvnw test
cd ../frontend && npm run build
```

## Deployment preparation (not deployed)

The application is prepared for deployment, but it has not been deployed or remotely verified. In Vercel, set Root Directory to `frontend`, Framework Preset to Vite, Build Command to `npm run build`, and Output Directory to `dist`. The first `frontend/vercel.json` rewrite proxies only `/api/:path*` to `https://gymflow-lhvn.onrender.com/api/:path*`; the last rewrite sends unmatched SPA paths to `/index.html`. Vercel checks the filesystem before applying rewrites, so built assets are served normally. The API rewrite preserves the browser-visible `/api` path. Set `VITE_API_BASE_URL=/api`. The browser therefore sends cookies to the Vercel origin and Vercel forwards API requests to Render, avoiding cross-site browser cookie restrictions. Keep Render's `SESSION_COOKIE_SAME_SITE=lax` and `SESSION_COOKIE_SECURE=true`. Vercel documents external rewrites as reverse proxies for API requests and recommends the `index.html` fallback for Vite SPA deep links; preview deployments should still be used to verify cookie behavior with this specific app.

Build artifacts and commands:

```sh
cd backend
./mvnw test
./mvnw clean package
SPRING_PROFILES_ACTIVE=prod java -jar target/management-0.0.1-SNAPSHOT.jar

cd ../frontend
npm ci
VITE_API_BASE_URL=/api VITE_PUBLIC_APP_URL=https://gym.example.com npm run build
```

Vite variables are embedded at build time; set them in the frontend build environment and rebuild after changing them. `VITE_PUBLIC_APP_URL` should be the public frontend origin. It is used to construct ADMIN-issued activation links; it contains no secret. When unset, the UI uses its current browser origin. The frontend always uses credentialed fetch for the existing session authentication. Production builds without `VITE_API_BASE_URL` use relative `/api`, never a localhost fallback. Avoid direct cross-origin browser calls to Render: third-party cookie restrictions can break session authentication even with credentialed CORS.

For Render, configure a Docker Web Service with Root Directory `backend` and Dockerfile path `Dockerfile`; the included Dockerfile builds the Spring Boot jar and starts it with `java -jar app.jar`. Keep one service instance and attach a persistent disk mounted at `/var/data` for the H2 database. Set `SPRING_DATASOURCE_URL=jdbc:h2:file:/var/data/gymdb;DB_CLOSE_ON_EXIT=FALSE`; set `SPRING_PROFILES_ACTIVE=prod`, `SESSION_COOKIE_SECURE=true`, `SESSION_COOKIE_SAME_SITE=lax`, `H2_CONSOLE_ENABLED=false`, and `GYM_CORS_ALLOWED_ORIGINS=https://gymflow-frontend-pied.vercel.app`. Set datasource username/password through Render's secret settings. Render supplies `PORT`; the app reads it automatically. Do not set a bootstrap admin password unless creating the first admin, and remove it after the initial account exists. See `backend/.env.example` for placeholders only. The H2 file and in-memory HTTP sessions require one backend instance; scaling out needs a database and shared session store.

Backend hosting environment:

| Variable | Production value / guidance |
|---|---|
| `PORT` | Use the port assigned by the hosting platform; Spring Boot binds to it automatically. |
| `GYM_CORS_ALLOWED_ORIGINS` | Exact origin, e.g. `https://gymflow-frontend-pied.vercel.app`. Do not include a path or trailing slash. No wildcard is permitted. |
| `SESSION_COOKIE_SECURE` | `true` when HTTPS terminates at the host or trusted reverse proxy. |
| `SESSION_COOKIE_SAME_SITE` | `lax` for same-site deployments. For genuinely cross-site origins, use `none` together with `SESSION_COOKIE_SECURE=true`; some browsers may still block third-party cookies, so prefer a same-site custom domain or proxy. |
| `SPRING_PROFILES_ACTIVE` | `prod` to apply production secure-cookie defaults and disable the H2 console. |
| `H2_CONSOLE_ENABLED` | `false`. The console remains enabled by default only for local development. |
| `SPRING_DATASOURCE_URL` | Point to a mounted persistent volume, e.g. `jdbc:h2:file:/var/data/gymdb;DB_CLOSE_ON_EXIT=FALSE`. Do not use the container's ephemeral filesystem. |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Set through the hosting secret manager. Existing local H2 uses `sa` with a blank password; do not copy that credential setup to a public service. |
| `GYM_ADMIN_USERNAME`, `GYM_ADMIN_PASSWORD` | Only for an intentional first admin bootstrap. Use secret storage; remove the bootstrap password from the service configuration after initialization. Existing admin passwords are not reset by bootstrap. |

The current H2 database is a file at `backend/data/gymdb.mv.db` when launched from `backend/` with the default JDBC URL. Hosted H2 must use durable mounted storage and a single backend instance; back up the database file using an H2-consistent backup procedure before upgrades, and verify restore procedures. Never place database files in a frontend/static asset directory. Do not copy the local database into a deployment image. The repository ignore rules exclude the whole `backend/data/` directory, environment files (while allowing the non-secret `.env.example` templates), Maven targets, and frontend build artifacts. The current workspace has no Git metadata, so ignored status of files in prior Git history cannot be verified here.

No database switch occurs as part of this preparation. Moving to PostgreSQL later requires adding a compatible PostgreSQL JDBC driver, setting PostgreSQL datasource URL/credentials and dialect handling, replacing H2-specific schema adjustments (including the legacy role-column migration), and introducing versioned schema migrations rather than relying on Hibernate `ddl-auto=update`. Export and import all current entities while preserving identifiers and relationships, then validate row counts, sequences, constraints, monetary precision, dates/timezones, and application workflows against a copy before cutover. Keep the H2 file untouched until the PostgreSQL copy is verified.

Deployment checklist:

1. Choose a hosting plan with HTTPS, a persistent disk for H2, backups, and one backend replica; or complete a separately planned PostgreSQL migration before using multiple instances.
2. Configure the exact frontend origin in `GYM_CORS_ALLOWED_ORIGINS`; set the frontend API and public app URL at build time.
3. Set Vercel `VITE_API_BASE_URL=/api` and use the included `frontend/vercel.json` rewrite to proxy the API to Render. Set Render `GYM_CORS_ALLOWED_ORIGINS=https://gymflow-frontend-pied.vercel.app`, `SESSION_COOKIE_SECURE=true`, and `SESSION_COOKIE_SAME_SITE=lax`; preserve CSRF headers and `Set-Cookie` through the proxy. Do not disable CSRF.
4. Disable the H2 console, set datasource credentials in a secret manager, and verify the persistent volume is mounted at the configured database path before startup.
5. Back up existing data before any hosted copy or schema change. This project does not upload, copy, or reset your local database automatically.
6. Bootstrap or provision the first ADMIN securely, remove the bootstrap password secret afterward, and verify ADMIN, STAFF, and MEMBER authorization using HTTPS.
7. Test login, refresh/session restoration, logout, CSRF-protected writes, activation-link host, SPA deep-link refresh, and data survival after a restart before inviting users.

Remaining deployment blockers: no hosting provider or domain has been selected/configured; H2 and in-memory HTTP sessions require one instance plus durable disk (multi-instance use needs PostgreSQL and shared sessions such as Spring Session); schema management still uses Hibernate `ddl-auto=update`; activation links are manually distributed with no email delivery; payments are a mock only. These preparations do not constitute a deployment or production security certification.

## Authentication and permissions

- Login: `POST /api/auth/login` with `usernameOrEmail`/password (`username` remains accepted for existing clients). Current session user: `GET /api/auth/me`. Logout: `POST /api/auth/logout`.
- Member activation: an ADMIN issues a one-time code using `POST /api/admin/member-activations` with `{ "memberId": 123, "username": "chosen-name" }`; the response contains the code once. The member sets a password using `POST /api/auth/activate-member` with `{ "token": "...", "password": "..." }`.
- `MEMBER` accounts can access only their own `/api/member/**` resources. Their member identity comes from the server-side account-to-member link, never a browser-supplied member ID. ADMIN/STAFF application permissions continue to be enforced by Spring Security.
- The frontend protects all application routes and redirects anonymous visitors to the login page. It restores the user from the backend session on refresh and returns to login if the session expires.
- Authentication uses server-side HTTP sessions, BCrypt password hashing, pre-login session invalidation, HttpOnly `JSESSIONID`, and `SameSite=Lax`. CSRF protection remains enabled for state-changing requests; tokens are cleared at login/logout. The API issues an `XSRF-TOKEN` cookie; the frontend sends its value in `X-XSRF-TOKEN`.
- The CSRF bootstrap returns the token in its JSON response as well as a readable cookie. The frontend sends the returned token from in-memory state; this also works when `/api` is reverse-proxied and the API cookie is host-only. For Vercel/Render, use the same-origin Vercel rewrite and `VITE_API_BASE_URL=/api`; direct cross-site requests may fail in Safari because third-party session cookies can be blocked.
- `SESSION_COOKIE_SECURE` defaults to `false` for local HTTP. Set `SESSION_COOKIE_SECURE=true` when serving over HTTPS. Do not expose this development configuration over plain HTTP on a public network.
- `ADMIN` can manage accounts, plans, trainers, member profiles, fee adjustments, and mock payment processing/cancellation. `STAFF` can view members/plans/trainers, create/update member profiles, record attendance, create payment requests, view payment history/receipts/reports, and use the dashboard. Member profile APIs cannot assign or extend a membership; only a successful payment changes membership plans or dates. The backend enforces these rules regardless of frontend navigation visibility.
- `MEMBER` can view a member dashboard, read-only profile, current membership, own attendance/payment history, and eligible receipts; members can also create/edit/delete their own fitness entries, goals, workout plans/sessions, meal logs, water logs and notification read status. Ownership is resolved on the server and nested-resource mutations check ownership. Members cannot edit membership dates/status, payments, attendance, or protected profile/account fields.
- The H2 console is restricted to ADMIN and same-origin frames. APIs require an authenticated session except login and CSRF initialization.

## Frontend pages

ADMIN/STAFF pages: dashboard; member list and add/edit forms; membership plans; trainers; attendance; payment creation and mock outcome selection; transaction search; printable receipts; reports; and administrator-only accounts. ADMIN also manages announcements. MEMBER pages: dashboard, profile, membership, attendance, payments, fitness progress/goals, workout planner/history, achievements, nutrition/hydration and notifications. Member navigation is separate from the staff workspace and API permissions are independently enforced by the backend.

## API overview

All APIs use `/api`:

| Area | Endpoints |
|---|---|
| Authentication | `GET /auth/csrf`, `POST /auth/login`, `GET /auth/me`, `POST /auth/logout` |
| Member activation | `POST /auth/activate-member` (one-time token); ADMIN: `GET /admin/member-activations/available-members`, `POST /admin/member-activations` |
| Accounts (ADMIN) | `GET/POST /admin/users`, `PUT /admin/users/{id}/enabled` |
| Member portal (MEMBER) | `GET /member/dashboard`, `/member/profile`, `/member/membership`, `/member/attendance`, `/member/attendance/activity?from=&to=`, `/member/payments`, `/member/payments/{id}/receipt` |
| Fitness progress/goals (MEMBER) | `GET/POST /member/measurements`, `PUT/DELETE /member/measurements/{id}`, `GET/POST /member/goals`, `PUT/DELETE /member/goals/{id}`, `POST /member/goals/{id}/complete`, `GET /member/fitness/summary` |
| Workouts (MEMBER) | `GET /member/exercises`, `GET/POST /member/workouts/plans`, `PUT/DELETE /member/workouts/plans/{id}`, `GET /member/workouts/sessions`, `GET /member/workouts/sessions/active`, `POST /member/workouts/sessions/start`, `POST /member/workouts/sessions/{id}/complete` |
| Attendance achievements (MEMBER) | `GET/PUT /member/attendance-goal`, `GET /member/achievements` |
| Nutrition and hydration (MEMBER) | `GET /member/nutrition?date=`, `POST/PUT /member/nutrition/meals`, `PUT/DELETE /member/nutrition/meals/{id}`, `GET/PUT /member/nutrition/settings`, `POST /member/nutrition/water`, `DELETE /member/nutrition/water/{id}` |
| Notifications (MEMBER) | `GET /member/notifications`, `POST /member/notifications/{id}/read`, `POST /member/notifications/read-all` |
| Announcements (ADMIN) | `GET/POST /admin/announcements`, `PUT/DELETE /admin/announcements/{id}` |
| Members | `GET/POST /members`, `GET/PUT/DELETE /members/{id}`, `POST /members/{id}/fees` |
| Plans | `GET/POST /plans`, `PUT/DELETE /plans/{id}` (delete deactivates) |
| Trainers | `GET/POST /trainers`, `GET/PUT/DELETE /trainers/{id}` (delete deactivates) |
| Attendance | `POST /attendance/check-in`, `POST /attendance/check-out`, `GET /attendance?memberId=&date=`, `GET /attendance/member/{memberId}` |
| Payments | `POST /payments`, `POST /payments/{id}/process`, `POST /payments/{id}/cancel`, `GET /payments`, `GET /payments/{id}`, `GET /payments/member/{memberId}`, `GET /payments/transaction/{reference}`, `GET /payments/{id}/receipt` |
| Reports | `GET /reports/dashboard`, `GET /reports/payments?from=&to=`, `GET /reports/payments/daily?date=`, `GET /reports/payments/monthly?month=`, `GET /reports/attendance/today` |

The mock payment gateway is a college-project simulation only: it does not contact a bank or move real money. Do not provide card numbers, CVVs, UPI PINs, or banking passwords. Only ADMIN can process or cancel a pending payment; revenue counts SUCCESS records only.

## H2 development database

The H2 console runs locally at `http://localhost:8080/h2-console`, JDBC URL `jdbc:h2:file:./data/gymdb`, username `sa`, blank password. Start Maven from `backend/` so the relative database URL resolves to `backend/data/gymdb.mv.db`. `spring.jpa.hibernate.ddl-auto=update` preserves rows while updating schema; there are no seed scripts. Spring Boot command-line properties and environment variables such as `SPRING_DATASOURCE_URL` can override the datasource. The `prod` profile disables the console unless explicitly overridden. The database files are ignored by Git. Never expose the H2 console or use `ddl-auto=update` in production.

The backend uses the JVM's default local timezone for `LocalDate`/`LocalDateTime` attendance and payment dates. The frontend now builds date filters from the browser's local calendar date (not UTC). For a local demonstration, run both processes on the same machine/timezone; a remotely hosted backend must use a documented consistent timezone or move date-time storage to offset/UTC types.

## Troubleshooting

- **Java/Maven:** check `java -version` for Java 21; run Maven from `backend/`. If needed, `chmod +x backend/mvnw` from the root.
- **Missing administrator:** both `GYM_ADMIN_USERNAME` and `GYM_ADMIN_PASSWORD` must be set for initial creation. If that user already exists, the initializer will not replace its password.
- **401 after login:** sign in again; sessions are server-side and expire. Make sure browser cookies are enabled.
- **403 on POST/PUT/DELETE:** refresh the page so the UI obtains a new CSRF cookie, then retry. Staff will also receive 403 for admin-only operations.
- **CORS or credentials error:** use the exact localhost origins above, confirm API URL in `frontend/.env`, and restart both apps if port/origin settings changed.
- **npm:** run `npm install` and `npm run dev` from `frontend/`.
- **Ports:** backend uses 8080 and Vite uses 5173. Stop conflicting processes or update server, CORS, and API URL together.
- **H2 records appear missing:** run backend from `backend/`; the H2 path is relative to its current working directory.

## Manual end-to-end check

1. Start the backend with the local admin environment variables, start Vite in the other terminal, and open `http://localhost:5173`.
2. Sign in with the initialized administrator. Refresh the browser and confirm the dashboard remains open. Sign out and confirm the login page appears; direct visits to `/reports` should return to login until authenticated.
3. As ADMIN, create a plan, a member profile, and a trainer. Try creating a member with plan and membership dates in the API; profile CRUD must not grant membership. Create a membership payment, simulate a decline, confirm the member remains unassigned and no receipt is offered, then create another request and approve it. Confirm the plan and dates appear only after approval and that a printable receipt is available.
4. Record attendance for the paid member, try a duplicate check-in, check out, and confirm the visit and dashboard/report totals. Attempt a member deletion after payment or attendance history; the backend must reject it.
5. Create a STAFF login, sign in in a separate browser/private window, and confirm profile editing, attendance, and payment-request creation work. Confirm plan/trainer/account administration, member deletion, and mock payment processing are rejected by the backend. A non-configured browser origin must fail CORS.
6. As ADMIN, open **Staff accounts**, select a member created above, choose a username, and issue an activation link. Send it privately to the verified member. Activate with a new password, then sign in using the username and again using the registered email. Refresh and verify the session is restored; sign out and verify it is invalidated.
7. Confirm the member sees only their own dashboard, profile, membership, attendance, payments, and eligible receipt. Try `/reports`, `/accounts`, and a staff API request while signed in as MEMBER; the frontend should redirect and the backend must deny the requests. Create a second member and verify their receipt endpoint cannot read the first member's receipt. Deactivate the member account from **Staff accounts** and verify both username and email login are rejected.
8. Restart the backend from `backend/` and confirm previously stored members, plans, trainers, attendance, payments, and user accounts remain in the H2 file.
9. As MEMBER, add two dated weight measurements and view the trend; create a goal and confirm progress follows the saved values. Build a workout plan, start a session, verify it does not count yet, then complete it and confirm it appears in history and badges. Log a meal and water entry on today’s date, configure personal targets, and confirm the displayed totals match the entered records. As ADMIN, publish a dated announcement; verify it appears once in the member notification centre and can be marked read.

## Limitations

Member activation currently uses an ADMIN-issued local link because email delivery is not configured. There is no password reset, profile editing, MFA, rate limiting, or account recovery. Fitness measurements are private to the member, but goal-based measurement tracking currently assumes lower targets for weight loss/body measurements; weight gain is supported for weight only. Workout logs save one repetitions-per-set value per exercise rather than detailed per-set variation. Notification reminders are generated when the member opens the notification centre; there is no background scheduler, email or push service. Attendance dates follow the backend JVM's local timezone. The database stores current membership state rather than a separate membership-period archive. Use only for local/college demonstrations until production deployment adds email delivery, abuse controls, HTTPS, managed schema migrations, and operational monitoring. Payment processing remains a mock simulation.
