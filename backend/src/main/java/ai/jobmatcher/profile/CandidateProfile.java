package ai.jobmatcher.profile;

import java.util.UUID;

import ai.jobmatcher.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Job-search preferences. List fields are JSON arrays stored as text and exposed as lists by {@link ProfileService}. */
@Entity
@Table(name = "candidate_profile")
public class CandidateProfile extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "desired_titles", nullable = false)
    private String desiredTitles = "[]";

    @Column(name = "desired_technologies", nullable = false)
    private String desiredTechnologies = "[]";

    private String seniority;

    @Column(name = "salary_min")
    private Integer salaryMin;

    @Column(name = "salary_max")
    private Integer salaryMax;

    @Column(name = "salary_currency")
    private String salaryCurrency;

    @Column(name = "contract_types", nullable = false)
    private String contractTypes = "[]";

    @Column(name = "work_modes", nullable = false)
    private String workModes = "[]";

    @Column(name = "preferred_countries", nullable = false)
    private String preferredCountries = "[]";

    @Column(name = "preferred_locations", nullable = false)
    private String preferredLocations = "[]";

    @Column(name = "willing_to_relocate", nullable = false)
    private boolean willingToRelocate;

    @Column(name = "needs_visa_sponsorship")
    private Boolean needsVisaSponsorship;

    @Column(name = "work_authorization", nullable = false)
    private String workAuthorization = "[]";

    @Column(name = "notify_new_matches", nullable = false)
    private boolean notifyNewMatches = true;

    @Column(name = "notify_reminders", nullable = false)
    private boolean notifyReminders = true;

    protected CandidateProfile() {
    }

    public CandidateProfile(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getDesiredTitles() { return desiredTitles; }
    public void setDesiredTitles(String v) { this.desiredTitles = v; }
    public String getDesiredTechnologies() { return desiredTechnologies; }
    public void setDesiredTechnologies(String v) { this.desiredTechnologies = v; }
    public String getSeniority() { return seniority; }
    public void setSeniority(String v) { this.seniority = v; }
    public Integer getSalaryMin() { return salaryMin; }
    public void setSalaryMin(Integer v) { this.salaryMin = v; }
    public Integer getSalaryMax() { return salaryMax; }
    public void setSalaryMax(Integer v) { this.salaryMax = v; }
    public String getSalaryCurrency() { return salaryCurrency; }
    public void setSalaryCurrency(String v) { this.salaryCurrency = v; }
    public String getContractTypes() { return contractTypes; }
    public void setContractTypes(String v) { this.contractTypes = v; }
    public String getWorkModes() { return workModes; }
    public void setWorkModes(String v) { this.workModes = v; }
    public String getPreferredCountries() { return preferredCountries; }
    public void setPreferredCountries(String v) { this.preferredCountries = v; }
    public String getPreferredLocations() { return preferredLocations; }
    public void setPreferredLocations(String v) { this.preferredLocations = v; }
    public boolean isWillingToRelocate() { return willingToRelocate; }
    public void setWillingToRelocate(boolean v) { this.willingToRelocate = v; }
    public Boolean getNeedsVisaSponsorship() { return needsVisaSponsorship; }
    public void setNeedsVisaSponsorship(Boolean v) { this.needsVisaSponsorship = v; }
    public String getWorkAuthorization() { return workAuthorization; }
    public void setWorkAuthorization(String v) { this.workAuthorization = v; }
    public boolean isNotifyNewMatches() { return notifyNewMatches; }
    public void setNotifyNewMatches(boolean v) { this.notifyNewMatches = v; }
    public boolean isNotifyReminders() { return notifyReminders; }
    public void setNotifyReminders(boolean v) { this.notifyReminders = v; }
}
