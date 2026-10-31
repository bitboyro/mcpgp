# European API inventory

Research date: 2026-10-31. Initial inventory, not an exhaustive census of Europe.
Companion: [delivery plan](european-api-catalogue-plan.md).

## What verification means

- **D — documented:** provider documentation retrieved and inspected; the API's existence and
  described purpose are confirmed. This is not a live service or integration test.
- **S — specification retrieved:** machine-readable specification downloaded successfully.
- **L — live read:** a bounded anonymous data request returned HTTP 200 and parseable JSON
  with the expected envelope. This establishes only that request, not every endpoint.
- **Build/runtime:** no entries have yet been compiled or exercised through MCP GP.

Fit is an engineering assessment: **A** prioritize a narrow REST/JSON scope; **B** needs more
schema, query or output work; **H** hold outside the initial definition-only collection.
Official OpenAPI does not guarantee a successful build. Unless explicitly noted, schema version,
license permissions for specification redistribution, quotas and production authentication still
require integration-level review. Open access does not mean unrestricted reuse.

Each linked API name is a primary evidence source. Access notes apply only to the proposed scope.
The inventory includes EU institutions, European countries outside the EU, and global services
with useful European coverage. It does not establish comparative MCP popularity.

## Companies, tax and procurement

| ID | API / evidence | Coverage and proposed tools | Access / specification evidence | Check | Fit / next verification |
|---|---|---|---|---|---|
| EU01 | [Recherche d'entreprises](https://annuaire-entreprises.data.gouv.fr/donnees/api-entreprises) | France: business search and identity | Open access; provider links OpenAPI. Different from restricted API Entreprise | D, L | A: select search fields; retain SIREN/SIRET and source links |
| EU02 | [Brønnøysund Enhetsregisteret](https://data.brreg.no/enhetsregisteret/api/dokumentasjon/no/index.html) | Norway: organizations and subunits | Public REST scope, OpenAPI download; NLOD linked. Authorized personal-identifier endpoints excluded | D, L | A: public entity search/lookup only |
| EU03 | [Companies House](https://developer.company-information.service.gov.uk/overview/) | UK: companies, officers, filing history | Official OpenAPI; [API key through Basic auth](https://developer.company-information.service.gov.uk/authentication) for public reads | D | A: credentials needed for live test; exclude filing and streaming |
| EU04 | [ANAF synchronous public services](https://static.anaf.ro/static/10/Anaf/Informatii_R/Servicii_web/servicii_sincron.html) | Romania: company/VAT status, public annual financial information | Official endpoint list and separate instructions; no OpenAPI verified. Public lookup scope only | D | B: author contract; validate batch bodies, limits and per-record errors; exclude SPV and invoice submission |
| EU05 | [TED Public API](https://api.ted.europa.eu/swagger-ui/index.html) | EU: procurement notice search | OpenAPI 3.1 downloaded; auth differs across operations | D, S | B: confirm anonymous search in practice; expert-query guidance; exclude submission/publication |

## Legal and parliamentary research

| ID | API / evidence | Coverage and proposed tools | Access / specification evidence | Check | Fit / next verification |
|---|---|---|---|---|---|
| EU06 | [BOE open-data API](https://www.boe.es/datosabiertos/api/api.php) | Spain: consolidated legislation, BOE/BORME summaries | Official API reference with reuse conditions and XSD links | D | B: verify JSON versus XML per operation; preserve document version/date and original text links |
| EU07 | [Judilibre](https://www.data.gouv.fr/dataservices/api-judilibre) | France: judicial decisions and metadata | Free after PISTE registration; Swagger referenced; Licence Ouverte 2.0 plus specific reuse conditions | D | B: credentials, production spec and judicial coverage verification |
| EU08 | [Légifrance](https://www.legifrance.gouv.fr/contenu/pied-de-page/open-data-et-api) | France: legislation and legal text retrieval | Free after PISTE registration; Swagger referenced; stable API replaces closed beta | D | B: obtain current spec and validate token acquisition; distinguish current/historical texts |
| EU09 | [ELI and Sejm API](https://api.sejm.gov.pl/) | Poland: legal acts and parliamentary material | Official API documentation separates legal acts and parliamentary operations | D | A: select legal act metadata/read endpoints; contract and response formats still to inspect |
| EU10 | [Riksdag open data](https://www.riksdagen.se/sv/dokument-och-lagar/riksdagens-oppna-data/) | Sweden: parliamentary documents, members and votes | Official API and dataset documentation linked | D | B: verify JSON paths and pagination; parliamentary records are not a complete consolidated-law service |
| EU11 | [UK Parliament procedural API](https://api.parliament.uk/) | UK: parliamentary procedure | OData, SPARQL and parameterized query routes; Open Parliament Licence linked | D | B: choose bounded query routes; do not promise general UK legislation coverage |
| EU12 | [EUR-Lex search webservice](https://eur-lex.europa.eu/content/help/data-reuse/webservice.html?locale=en) | EU: legal search | SOAP/XML, registration and approval, WSDL; documents retrieved separately through CELLAR | D | H: no direct SOAP-to-MCP generation claim. Assess CELLAR retrieval separately before offering EU legal bundle |

## Statistics and finance

| ID | API / evidence | Coverage and proposed tools | Access / specification evidence | Check | Fit / next verification |
|---|---|---|---|---|---|
| EU13 | [Eurostat Statistics API](https://ec.europa.eu/eurostat/en/web/user-guides/data-browser/api-data-access/api-getting-started/api) | EU and partner-country statistics | Public REST, JSON-stat, dataset-specific filters | D | B: begin with named datasets; preserve dimensions, units and observation periods |
| EU14 | [ECB data service](https://data.ecb.europa.eu/help/getting-data-web-services-sdmx-0) | Euro area: statistical series and metadata | Official SDMX 2.1 REST service | D | B: curate series and verify response formats; broad discovery is separate work |
| EU15 | [NBP Web API](https://api.nbp.pl/en.html) | Poland: official exchange-rate tables and gold prices | Public HTTP GET, JSON/XML; documented 93-day maximum per request | D, L | A: small useful first finance server; official quotation dates retained |
| EU16 | [Statistics Denmark API console](https://api.statbank.dk/console#help) | Denmark: statistical tables | Provider API console verified; full operation contracts and access conditions not yet inspected | D | B: fetch table metadata/data documentation before implementation |
| EU17 | [Statistics Norway](https://www.ssb.no/en/api) | Norway: statistics and classifications | Open without registration; PxWebApi 2 and Klass; CC BY 4.0 | D | B: select current API; dynamic dimensions and table changes |
| EU18 | [Statistics Sweden](https://www.scb.se/en/services/open-data-api/) | Sweden: statistical tables | Provider documents API access and response limits; CC0 for its data, other-source attribution caveat | D | B: inspect current endpoint version; bound selected cells |
| EU19 | [CBS StatLine](https://www.cbs.nl/nl-nl/onze-diensten/open-data/statline-als-open-data) | Netherlands: statistics and regional indicators | OData services documented | D | B: [provider migration notice](https://www.cbs.nl/nl-nl/onze-diensten/open-data/nieuwsbrief-open-data/nieuwsbrief-vernieuwing-statline-open-data) discusses SDMX replacing OData; confirm service lifecycle first |
| EU20 | [Frankfurter](https://frankfurter.dev/) | Global currencies, including European currencies and providers | No API key; provider links OpenAPI and an existing MCP server | D | A: useful rates integration, not a novel MCP niche; no conversion endpoint |

## Environment, energy, geography and transport

| ID | API / evidence | Coverage and proposed tools | Access / specification evidence | Check | Fit / next verification |
|---|---|---|---|---|---|
| EU21 | [MET Norway](https://api.met.no/) | Global forecasts; selected Nordic marine/weather products | Free data under linked Creative Commons terms; identifying User-Agent required | D | A: start with Locationforecast JSON; verify caching and header configuration |
| EU22 | [SMHI warnings](https://opendata.smhi.se/warnings/resources/api) | Sweden: weather warnings and metadata | Documented JSON routes and CAP alternative; [terms linked here](https://opendata.smhi.se/warnings/introduction) | D | A: JSON warning scope only; retain publication/validity times |
| EU23 | [Environment Agency flood monitoring](https://environment.data.gov.uk/flood-monitoring/doc/reference) | England: flood alerts, stations and readings | No registration; Open Government Licence and required attribution; beta service | D | A: bounded station/reading queries; official guidance excludes safety-critical reliance |
| EU24 | [OpenAQ](https://docs.openaq.org/using-the-api/api-key) | Global air quality including European stations | X-API-Key; published [OpenAPI 3.1](https://api.openaq.org/docs) | D | A: locations/sensors/measurements; credentials and data-provider licenses to verify |
| EU25 | [GBIF](https://techdocs.gbif.org/en/openapi/) | Global biodiversity including European observations | REST/OpenAPI documentation; public searches, credentials for downloads | D | A: species/occurrence searches; exclude asynchronous bulk downloads initially; dataset-specific licenses |
| EU26 | [Energy-Charts](https://api.energy-charts.info/openapi.json) | European electricity data; coverage varies by endpoint | OpenAPI 3.1 downloaded; CC BY 4.0 unless otherwise stated; endpoint-specific rate limits | D, S | A: bounded queries; default documented limit is low, caching/retry requirements need assessment |
| EU27 | [Transport for London](https://api-portal.tfl.gov.uk/) | London: arrivals, disruptions and journeys | Official specification link; registration recommended, app_key query credential | D | A: verify selected operations with registered key; identifiers and nested itineraries |
| EU28 | [French administrative geography](https://geo.api.gouv.fr/) | France: communes, departments and regions | Public geographic API; commune lookup tested without credentials | D, L | A: geography lookup; address/geocoding service lifecycle reviewed separately |
| EU29 | [data.europa.eu](https://dataeuropa.gitlab.io/data-provider-manual/) | European dataset catalogue discovery | Official API/search/SPARQL documentation | D | B: select search interface and inspect contract. Catalogue records are not the underlying datasets |

## Direct request evidence

Read-only checks performed on 2026-10-31; no credentials or personal records retained in the repo.
The downloads used for inspection are temporary research files, not pinned integration fixtures.

| ID | Exact request | Result inspected |
|---|---|---|
| EU01 | GET https://recherche-entreprises.api.gouv.fr/search?q=La%20Poste&per_page=1 | HTTP 200; JSON results array with one item and pagination metadata |
| EU02 | GET https://data.brreg.no/enhetsregisteret/api/enheter?size=1 | HTTP 200; JSON _embedded.enheter array with one item, links and page metadata |
| EU15 | GET https://api.nbp.pl/api/exchangerates/tables/A/?format=json | HTTP 200; JSON array with a rate table |
| EU28 | GET https://geo.api.gouv.fr/communes?code=75056&fields=nom,code | HTTP 200; JSON array containing Paris / 75056 |
| EU26 | GET https://api.energy-charts.info/openapi.json | HTTP 200; parsed OpenAPI 3.1.0; 37 paths. No data operation tested |
| EU05 | GET https://api.ted.europa.eu/api-v3.yaml | HTTP 200; YAML header declares OpenAPI 3.1.0. No search operation tested |

Web retrieval failures are not evidence that an API is offline. No build, MCP initialization,
authentication flow, full license audit, or production reliability claim is made by this table.

## Discovery backlog / unresolved

- Romania data.gov.ro: portal retrieval timed out; catalogue API not verified in this pass.
- Poland BDL: endpoint could not be retrieved by research tooling; obtain official docs and retry.
- SMHI observation APIs: initial documentation URL returned 404; warnings API is separately verified.
- Entur: [old official portal](https://developer.entur.org/) points to a new portal; inspect current
  REST versus GraphQL offerings before promising a definition-only journey planner.
- IGN geocoding, PSE market data and Bolagsverket: insufficient readable evidence in this pass.
- Remaining European countries and national procurement services: continue discovery country by
  country. No claim of comprehensive European, legal or procurement coverage.
- Existing MCP alternatives: preserve earlier findings, but conduct a per-integration repository
  review before making differentiation claims; absence in search results is not proof of absence.
