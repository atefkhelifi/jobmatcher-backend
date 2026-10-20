package ai.jobmatcher.profile;

import java.util.List;
import java.util.UUID;

import ai.jobmatcher.common.InvalidInputException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProfileService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    /** Preferences as the API sees them. Every field is optional: unknown stays unknown. */
    public record Preferences(
            @Size(max = 20) List<@Size(max = 80) String> desiredTitles,
            @Size(max = 40) List<@Size(max = 60) String> desiredTechnologies,
            @Pattern(regexp = "junior|mid|senior|lead", message = "must be junior, mid, senior or lead") String seniority,
            @Min(0) @Max(10_000_000) Integer salaryMin,
            @Min(0) @Max(10_000_000) Integer salaryMax,
            @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as EUR") String salaryCurrency,
            @Size(max = 10) List<@Pattern(regexp = "permanent|fixed_term|contract|freelance|internship|apprenticeship") String> contractTypes,
            @Size(max = 3) List<@Pattern(regexp = "remote|hybrid|onsite") String> workModes,
            @Size(max = 30) List<@Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166 alpha-2 code") String> preferredCountries,
            @Size(max = 30) List<@Size(max = 80) String> preferredLocations,
            boolean willingToRelocate,
            Boolean needsVisaSponsorship,
            @Size(max = 30) List<@Pattern(regexp = "[A-Z]{2}") String> workAuthorization,
            boolean notifyNewMatches,
            boolean notifyReminders) {
    }

    private final CandidateProfileRepository repo;
    private final JsonMapper json;

    ProfileService(CandidateProfileRepository repo, JsonMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Transactional
    public void createEmpty(UUID userId) {
        repo.save(new CandidateProfile(userId));
    }

    @Transactional(readOnly = true)
    public Preferences get(UUID userId) {
        return toView(load(userId));
    }

    @Transactional
    public Preferences update(UUID userId, Preferences p) {
        if (p.salaryMin() != null && p.salaryMax() != null && p.salaryMin() > p.salaryMax()) {
            throw new InvalidInputException("salaryMin must not exceed salaryMax.");
        }
        CandidateProfile e = load(userId);
        e.setDesiredTitles(write(p.desiredTitles()));
        e.setDesiredTechnologies(write(p.desiredTechnologies()));
        e.setSeniority(p.seniority());
        e.setSalaryMin(p.salaryMin());
        e.setSalaryMax(p.salaryMax());
        e.setSalaryCurrency(p.salaryCurrency());
        e.setContractTypes(write(p.contractTypes()));
        e.setWorkModes(write(p.workModes()));
        e.setPreferredCountries(write(p.preferredCountries()));
        e.setPreferredLocations(write(p.preferredLocations()));
        e.setWillingToRelocate(p.willingToRelocate());
        e.setNeedsVisaSponsorship(p.needsVisaSponsorship());
        e.setWorkAuthorization(write(p.workAuthorization()));
        e.setNotifyNewMatches(p.notifyNewMatches());
        e.setNotifyReminders(p.notifyReminders());
        return toView(repo.save(e));
    }

    private CandidateProfile load(UUID userId) {
        return repo.findByUserId(userId).orElseGet(() -> repo.save(new CandidateProfile(userId)));
    }

    private Preferences toView(CandidateProfile e) {
        return new Preferences(read(e.getDesiredTitles()), read(e.getDesiredTechnologies()), e.getSeniority(),
                e.getSalaryMin(), e.getSalaryMax(), e.getSalaryCurrency(), read(e.getContractTypes()),
                read(e.getWorkModes()), read(e.getPreferredCountries()), read(e.getPreferredLocations()),
                e.isWillingToRelocate(), e.getNeedsVisaSponsorship(), read(e.getWorkAuthorization()),
                e.isNotifyNewMatches(), e.isNotifyReminders());
    }

    private String write(List<String> v) {
        return json.writeValueAsString(v == null ? List.of() : v);
    }

    private List<String> read(String s) {
        return json.readValue(s, STRINGS);
    }
}
