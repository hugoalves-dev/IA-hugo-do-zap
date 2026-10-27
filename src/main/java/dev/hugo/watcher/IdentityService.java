package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public IdentityService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public CanonicalIdentity observe(IdentityObservation observation) {
        Objects.requireNonNull(observation, "observation");
        String observedJid = requireJid(observation.observedJid());

        Set<String> identities = new LinkedHashSet<>();
        identities.add(observedJid);
        if (observation.evidence() == IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR) {
            for (String alias : observation.aliases()) identities.add(requireJid(alias));
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            // Locking the small alias table makes explicit pair merges atomic, including first observations.
            jdbc.execute("LOCK TABLE identity_aliases IN EXCLUSIVE MODE");
            List<String> ordered = new ArrayList<>(new TreeSet<>(identities));
            String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
            List<String> canonicalRows = jdbc.query(
                    "select canonical_identity from identity_aliases where alias in (" + placeholders + ") order by alias",
                    (rs, rowNum) -> rs.getString(1), ordered.toArray());

            String canonical = lookupCanonical(observedJid);
            if (canonical == null && !canonicalRows.isEmpty()) canonical = canonicalRows.getFirst();
            if (canonical == null) canonical = observedJid;

            if (identities.size() > 1) {
                for (String previous : canonicalRows) {
                    if (!canonical.equals(previous)) {
                        jdbc.update("update identity_aliases set canonical_identity = ? where canonical_identity = ?",
                                canonical, previous);
                    }
                }
            }

            for (String alias : identities) {
                jdbc.update("""
                        insert into identity_aliases(alias, canonical_identity) values (?, ?)
                        on conflict (alias) do update set canonical_identity = excluded.canonical_identity
                        """, alias, canonical);
            }
            List<String> allAliases = jdbc.query("select alias from identity_aliases where canonical_identity = ? order by alias",
                    (rs, rowNum) -> rs.getString(1), canonical);
            return new CanonicalIdentity(canonical, Set.copyOf(allAliases));
        }), "Identity transaction returned no result");
    }

    private String lookupCanonical(String alias) {
        List<String> values = jdbc.query("select canonical_identity from identity_aliases where alias = ?",
                (rs, rowNum) -> rs.getString(1), alias);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String requireJid(String jid) {
        if (jid == null || jid.isBlank() || jid.length() > 255) {
            throw new IllegalArgumentException("JID must be non-blank and at most 255 characters");
        }
        return jid;
    }
}
