package ai.jobmatcher.contacts;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactSearchRepository extends JpaRepository<ContactSearch, ContactSearch.Key> {
}
