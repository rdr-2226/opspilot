# Build OpsPilot today: step-by-step guide

Total time: about 6–8 hours. Do the steps in order and tick them off.

---

## Step 1: Install the tools (45 min)

| Tool | Get it | Check it works |
|---|---|---|
| JDK 21 | https://adoptium.net (Temurin 21) | `java -version` shows 21 |
| Maven | https://maven.apache.org/download.cgi (or `choco install maven` / `brew install maven`) | `mvn -version` |
| Docker Desktop | https://www.docker.com/products/docker-desktop | `docker --version` |
| IntelliJ IDEA Community | https://www.jetbrains.com/idea/download | opens |
| Git | https://git-scm.com | `git --version` |

On Windows, set `JAVA_HOME` to the JDK folder and add `%JAVA_HOME%\bin` and Maven's `bin` to PATH.
Close and reopen the terminal afterwards.

## Step 2: Get a Gemini API key (5 min)
1. Open https://aistudio.google.com/apikey and click **Create API key**.
2. Keep it private. Never paste it into code or commit it to GitHub.

## Step 3: Open the project and run the tests (30 min)
1. Unzip `opspilot.zip` and open the folder in IntelliJ (File → Open → select the folder with `pom.xml`).
2. Wait for IntelliJ to download dependencies (bottom-right progress bar).
3. In a terminal inside the project folder, run:
   ```bash
   mvn test
   ```
   You should see `BUILD SUCCESS` and **15 tests** passing. These tests need no API key or database.

   If it fails, copy the **first** error message and send it to Claude.

## Step 4: Start the database and the app (20 min)
```bash
docker compose up -d postgres
```
Then set your key and start the app.

macOS/Linux:
```bash
export GOOGLE_API_KEY=your-key
mvn spring-boot:run
```
Windows PowerShell:
```powershell
$env:GOOGLE_API_KEY="your-key"
mvn spring-boot:run
```
Wait for `Started OpsPilotApplication`, then open http://localhost:8080/actuator/health. It should say `UP`.

## Step 5: See the agents work (30 min)
Open `requests.http` in IntelliJ and click the green ▶ next to each request, in order. Or use curl:

```bash
curl -X POST localhost:8080/api/incidents -H "Content-Type: application/json" \
  -d '{"title":"Checkout payments failing","serviceName":"payment-service","description":"500 errors since 10:00","severity":"CRITICAL"}'

curl -X POST localhost:8080/api/incidents/1/triage
```

Watch the app console. You'll see each agent finish in turn:
```
[incident 1] LogAnalyst finished: ...
[incident 1] RunbookAgent finished: ...
[incident 1] FixPlanner finished: {"rootCause": ...}
```
The triage response should show `PENDING_APPROVAL`, a root cause about the connection pool and slow
query, fix steps, and `runbookUsed: db-connection-pool-exhaustion.md`.

Then approve and resolve it (requests 3 and 4). Also try `order-service` and `auth-service`.

## Step 6: Understand the code (2–3 hours, the most important step)
Read the files in this order. For each one, make sure you can explain it out loud.

1. `incident/Incident.java`: JPA entity. *Why enums stored as STRING? What do @PrePersist/@PreUpdate do?*
2. `incident/dto/*`: Java **records** and validation. *Why not return the entity directly from the API?*
3. `incident/IncidentService.java`: business rules and the status machine. *Why is `triage()` not @Transactional?*
4. `incident/IncidentController.java`: REST endpoints. *Why does create return 201 with a Location header?*
5. `common/GlobalExceptionHandler.java`: turns exceptions into 400/404/409/502 JSON errors.
6. `agent/LogTools.java` and `agent/RunbookTools.java`: **ADK tools**. The `@Schema` descriptions are what
   the model reads to decide when and how to call a tool. *Why the regex on serviceName?*
7. `agent/AdkTriageEngine.java`: **the heart of ADK**. Three `LlmAgent`s, a `SequentialAgent`,
   `outputKey` → `{placeholder}` state passing, `outputSchema`, `InMemoryRunner`, sessions, events.
8. `agent/TriagePlanParser.java`: never trust LLM output; validate it.
9. `src/test/...`: how the AI is mocked (`@MockitoBean`) so tests run offline.

**Small experiments that teach you ADK:**
- Change the LogAnalyst instruction to also report the time the first error happened, then re-run.
- Add a 4th service: create `logs/cache-service.log` with Redis errors and a runbook for it.
- Remove `.outputSchema(...)` and see how the output gets messier. That's why structured output matters.

## Step 7: Put it on GitHub (20 min)
1. Create an **empty public** repo on GitHub called `opspilot` (no README).
2. In the project folder:
   ```bash
   git init
   git add .
   git commit -m "OpsPilot: multi-agent incident triage with Spring Boot and Google ADK"
   git branch -M main
   git remote add origin https://github.com/<your-username>/opspilot.git
   git push -u origin main
   ```
3. Open the **Actions** tab and check that CI turns green.
4. Before pushing, run `git status` and make sure no `.env` file or API key is included.

## Step 8: Record a demo (20 min)
Record a 60–90 second screen video: create an incident → triage (show the console with the agents) →
the JSON result → approve. Upload it to the README or LinkedIn. Recruiters watch demos, and few people read code.

## Step 9: Update your resume and LinkedIn
Add this under a **Projects** section (only after it runs on your machine):

> **OpsPilot: Multi-Agent Incident Triage** (Java 21, Spring Boot, Google ADK, PostgreSQL, Docker), github.com/&lt;you&gt;/opspilot
> - Built a 3-agent pipeline with Google ADK (SequentialAgent) that analyses service logs, retrieves the matching runbook and returns a structured root cause and fix plan.
> - Designed a Spring Boot REST API with PostgreSQL/JPA, validation, an enforced incident status machine and a human-in-the-loop approval step.
> - Made the AI layer testable behind an interface, with API tests on H2 and mocked agents; CI on GitHub Actions and Docker packaging.

Also add **Java, Spring Boot, JPA/Hibernate, Google ADK** to your skills, since you can now honestly say you've used them.

---

## Troubleshooting
| Problem | Fix |
|---|---|
| `mvn` not recognized | Maven not on PATH; reopen the terminal after installing |
| `release version 21 not supported` | `JAVA_HOME` points to an older JDK |
| `Connection refused` to 5432 | Postgres not running: `docker compose up -d postgres` |
| Port 5432 already in use | You have a local Postgres; stop it, or change the port in docker-compose.yml and `DB_URL` |
| 502 from `/triage` with an API key message | `GOOGLE_API_KEY` isn't set in the same terminal that runs the app |
| 429 / quota errors | Free tier limit; wait a minute, or set `OPSPILOT_MODEL=gemini-2.5-flash-lite` |
| Model not found | Set `OPSPILOT_MODEL` to a model listed in AI Studio |

## Interview questions to prepare
- Walk me through what happens when `/triage` is called.
- How do the agents share information? (session state, `outputKey`, placeholders)
- How do you stop the LLM from doing something harmful? (human approval, validated tools, schema, parsing)
- How do you test code that calls an LLM?
- What would you change for production? (see the README roadmap: async jobs, auth, pgvector, evaluation, observability)
- Core Java: records vs classes, `Optional`, streams, `volatile` + double-checked locking in `RunbookTools`.

## After today: the next weekends
1. Spring Security + JWT (Java interviews ask about this a lot)
2. pgvector semantic search for runbooks
3. ADK evaluation to measure root-cause accuracy (a number for your resume)
4. Deploy to Cloud Run and put the live link in the README
