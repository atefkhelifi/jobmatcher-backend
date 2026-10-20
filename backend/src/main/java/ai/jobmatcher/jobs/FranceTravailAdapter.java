package ai.jobmatcher.jobs;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.jobmatcher.fetch.SafeFetcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * France Travail "Offres d'emploi v2" (https://francetravail.io/data/api/offres-emploi), the public employment
 * agency's official API. It needs a free client id and secret from francetravail.io; without them it stays disabled.
 * The response mapping is covered by a fixture test; it has not been run against the live service yet.
 */
@Component
public class FranceTravailAdapter implements JobSourceAdapter {

    private final SafeFetcher fetcher;
    private final JsonMapper json;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;
    private final String apiBase;

    public FranceTravailAdapter(SafeFetcher fetcher, JsonMapper json,
            @Value("${app.jobs.francetravail.client-id:}") String clientId,
            @Value("${app.jobs.francetravail.client-secret:}") String clientSecret,
            @Value("${app.jobs.francetravail.token-url:https://entreprise.francetravail.fr/connexion/oauth2/access_token?realm=%2Fpartenaire}") String tokenUrl,
            @Value("${app.jobs.francetravail.api-base:https://api.francetravail.io/partenaire/offresdemploi/v2}") String apiBase) {
        this.fetcher = fetcher;
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenUrl = tokenUrl;
        this.apiBase = apiBase;
    }

    @Override
    public String key() {
        return "francetravail";
    }

    @Override
    public String displayName() {
        return "France Travail";
    }

    @Override
    public String termsUrl() {
        return "https://francetravail.io/data/api/offres-emploi";
    }

    @Override
    public boolean enabled() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Override
    public String disabledReason() {
        return "Create a free application on francetravail.io, subscribe it to the Offres d'emploi v2 API, then set "
                + "APP_JOBS_FRANCETRAVAIL_CLIENT_ID and APP_JOBS_FRANCETRAVAIL_CLIENT_SECRET.";
    }

    @Override
    public List<RawJob> search(SearchQuery q) {
        String token = token();
        int max = Math.min(q.limit(), 150);
        String url = apiBase + "/offres/search?range=0-" + (max - 1)
                + (q.keywords().isBlank() ? "" : "&motsCles=" + URLEncoder.encode(q.keywords(), StandardCharsets.UTF_8));
        SafeFetcher.Response res = fetcher.get(url, "application/json", false, Map.of("Authorization", "Bearer " + token));
        if (res.status() == 204) {
            return List.of(); // the API answers 204 when nothing matches
        }
        if (res.status() != 200 && res.status() != 206) {
            throw new SafeFetcher.FetchException("France Travail answered with HTTP " + res.status() + ".");
        }
        List<RawJob> out = new ArrayList<>();
        for (JsonNode j : json.readTree(res.body()).path("resultats")) {
            RawJob job = map(j);
            if (job != null && q.matchesLocation(job.location()) && (!q.remoteOnly() || job.remote() == RemoteMode.REMOTE)) {
                out.add(job);
            }
        }
        return out;
    }

    private String token() {
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("api_offresdemploiv2 o2dsoffre", StandardCharsets.UTF_8);
        SafeFetcher.Response res = fetcher.postForm(tokenUrl, form, Map.of());
        if (res.status() != 200) {
            throw new SafeFetcher.FetchException("France Travail rejected the credentials (HTTP " + res.status() + ").");
        }
        String token = json.readTree(res.body()).path("access_token").asString("");
        if (token.isBlank()) {
            throw new SafeFetcher.FetchException("France Travail returned no access token.");
        }
        return token;
    }

    RawJob map(JsonNode j) {
        String id = j.path("id").asString("");
        String title = j.path("intitule").asString("");
        if (id.isBlank() || title.isBlank()) {
            return null;
        }
        String origin = j.path("origineOffre").path("urlOrigine").asString(null);
        String url = origin != null && !origin.isBlank() ? origin : "https://candidat.francetravail.fr/offres/recherche/detail/" + id;
        String apply = ArbeitnowAdapter.blankToNull(j.path("contact").path("urlPostulation").asString(null));
        return new RawJob(id, title, ArbeitnowAdapter.blankToNull(j.path("entreprise").path("nom").asString(null)),
                ArbeitnowAdapter.blankToNull(j.path("lieuTravail").path("libelle").asString(null)), "FR", RemoteMode.UNKNOWN,
                j.path("description").asString(""), url, apply != null ? apply : url,
                ArbeitnowAdapter.blankToNull(j.path("typeContrat").asString(null)), null, null, null,
                instant(j.path("dateCreation").asString(null)), null);
    }

    private static Instant instant(String iso) {
        try {
            return iso == null ? null : OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
