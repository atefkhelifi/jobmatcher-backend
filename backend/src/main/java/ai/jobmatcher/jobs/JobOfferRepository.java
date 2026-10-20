package ai.jobmatcher.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID>, JpaSpecificationExecutor<JobOffer> {

    Optional<JobOffer> findBySourceKeyAndExternalIdAndOwnerUserId(String sourceKey, String externalId, UUID ownerUserId);

    /** Oldest active, non-duplicate catalog offer with this fingerprint. */
    @Query("""
            select o from JobOffer o
            where o.fingerprint = :fp and o.duplicateOf is null and o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE
              and o.ownerUserId is null
            order by o.firstSeenAt asc
            """)
    List<JobOffer> findCatalogCanonical(@Param("fp") String fingerprint, Pageable page);

    /** Same, but also considering this user's private offers. */
    @Query("""
            select o from JobOffer o
            where o.fingerprint = :fp and o.duplicateOf is null and o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE
              and (o.ownerUserId is null or o.ownerUserId = :owner)
            order by o.firstSeenAt asc
            """)
    List<JobOffer> findCanonicalForOwner(@Param("fp") String fingerprint, @Param("owner") UUID owner, Pageable page);

    @Query("""
            select o from JobOffer o
            where o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE and (o.lastVerifiedAt is null or o.lastVerifiedAt < :before)
            order by o.lastVerifiedAt asc nulls first
            """)
    List<JobOffer> findDueForVerification(@Param("before") Instant before, Pageable page);

    @Query("""
            select o from JobOffer o
            where o.duplicateOf is null and o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE
              and (o.ownerUserId is null or o.ownerUserId = :user)
            order by o.firstSeenAt desc
            """)
    List<JobOffer> findVisibleActive(@Param("user") UUID userId, Pageable page);

    /** Active catalog offers whose company name contains the text (case-insensitive); callers narrow the result further. */
    @Query("""
            select o from JobOffer o
            where o.duplicateOf is null and o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE
              and o.ownerUserId is null and lower(o.company) like lower(concat('%', :name, '%'))
            order by o.firstSeenAt desc
            """)
    List<JobOffer> findCatalogByCompanyContaining(@Param("name") String name, Pageable page);

    /** Catalog offers first seen at or after the given moment, newest first. */
    @Query("""
            select o from JobOffer o
            where o.duplicateOf is null and o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE
              and o.ownerUserId is null and o.firstSeenAt >= :since
            order by o.firstSeenAt desc
            """)
    List<JobOffer> findNewCatalogOffersSince(@Param("since") Instant since, Pageable page);

    @Query("""
            select o.sourceKey as key, count(o) as total from JobOffer o
            where o.status = ai.jobmatcher.jobs.JobOffer.Status.ACTIVE and o.duplicateOf is null and o.ownerUserId is null
            group by o.sourceKey
            """)
    List<SourceCount> countActiveBySource();

    interface SourceCount {
        String getKey();

        long getTotal();
    }
}
