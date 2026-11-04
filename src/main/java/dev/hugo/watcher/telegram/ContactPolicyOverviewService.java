package dev.hugo.watcher.telegram;

import dev.hugo.watcher.IdentityService;
import dev.hugo.watcher.OperationalState;
import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class ContactPolicyOverviewService {
    private final ContactPolicyOverviewRepository repository;
    private final PersistedPolicyRepository policies;
    private final AvailabilityService availability;
    private final WatcherProperties watcher;
    private final IdentityService identities;
    private final OperationalState operational;
    private final Clock clock;

    public ContactPolicyOverviewService(ContactPolicyOverviewRepository repository, PersistedPolicyRepository policies,
                                        AvailabilityService availability, WatcherProperties watcher,
                                        IdentityService identities, OperationalState operational, Clock clock) {
        this.repository = repository;
        this.policies = policies;
        this.availability = availability;
        this.watcher = watcher;
        this.identities = identities;
        this.operational = operational;
        this.clock = clock;
    }

    public String describe(String observedChat) {
        String chat = identities.canonicalJidFor(observedChat);
        if (!repository.known(chat)) return "Conversa não encontrada.";
        EffectivePolicy policy = policies.forChat(chat, repository.fallbackProfile(chat, watcher.getAllowedNameRegex()));
        Availability current = availability.at(clock.instant(), ZoneId.of(watcher.getTimezone()), policy);
        List<String> lines = new ArrayList<>();
        List<String> policyBlockers = new ArrayList<>();
        String name = identities.resolveFriendlyDisplayName(chat);
        lines.add("Contato: " + (name == null ? "nome não disponível" : name));
        for (PolicyField field : PolicyField.values()) {
            Object value = policy.value(field);
            lines.add(field.name() + ": " + (value == null ? "indisponível" : value)
                    + " · origem " + (policy.origin(field) == null ? "indisponível" : policy.origin(field)));
            if (value == null || policy.origin(field) == null) policyBlockers.add(field.name());
        }
        lines.add("Disponibilidade agora: " + current.state() + " · " + current.source() + " (" + current.reason() + ")");
        lines.add("Campos de política incompletos: " + (policyBlockers.isEmpty() ? "nenhum" : String.join(", ", policyBlockers)));
        List<String> authorizations = repository.authorizations(chat);
        lines.add("Ações autorizadas por situação: " + (authorizations.isEmpty() ? "nenhuma" : String.join(", ", authorizations)));
        List<String> consents = repository.consents(chat, clock.instant());
        lines.add("Consentimentos de IA vigentes: " + (consents.isEmpty() ? "nenhum" : String.join(", ", consents)));
        lines.add("Classificação configurada: " + (operational.classificationConfigured() ? "sim" : "não")
                + " · gate de classificação agora: " + (operational.classification() ? "aberto" : "bloqueado"));
        lines.add("Gates globais: Shadow " + (operational.simulation() ? "ativo" : "inativo/pausado")
                + "; emergência " + (operational.emergency() ? "ativa" : "inativa")
                + "; kill switch " + (operational.killSwitch() ? "ativo" : "inativo")
                + "; elegibilidade de ação real exige reavaliação de todos os gates na decisão.");
        return String.join("\n", lines);
    }
}
