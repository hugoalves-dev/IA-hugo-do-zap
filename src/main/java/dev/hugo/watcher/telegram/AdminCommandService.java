package dev.hugo.watcher.telegram;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.notification.ReportService;
import dev.hugo.watcher.policy.AvailabilityState;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository.Status;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository.Budget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminCommandService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ADMIN_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private static final Duration REPLY_TTL = Duration.ofMinutes(10);
    private final TelegramAdminPersistenceRepository persistence;
    private final ShadowDecisionQuery decisions;
    private final TransactionTemplate transactions;
    private final CallbackTokenService tokens;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;
    private final ReportService reports;
    private dev.hugo.watcher.OperationalState operational;
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    /** Production uses managed persistence and read-only decision-query collaborators. */
    public AdminCommandService(TelegramAdminPersistenceRepository persistence, ShadowDecisionQuery decisions,
                               PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock, ReportService reports) {
        this.persistence = persistence;
        this.decisions = decisions;
        this.transactions = new TransactionTemplate(manager);
        this.tokens = tokens;
        this.outbox = outbox;
        this.clock = clock;
        this.reports = reports;
    }

    /** Existing constructor preserved for standalone tests during #24. */
    public AdminCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock, ReportService reports) {
        this(new TelegramAdminPersistenceRepository(jdbc), new ShadowDecisionQuery(jdbc, clock),
                manager, tokens, outbox, clock, reports);
    }

    public AdminCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock) {
        this(jdbc, manager, tokens, outbox, clock, null);
    }

    public AdminResult execute(AdminCommand command) {
        if (command == null) return AdminResult.rejected("Comando inválido.");
        // Navigation tokens do not authorize mutations; a second, explicit token confirms them.
        AdminCommand effective = command.name().startsWith("nav_")
                ? new AdminCommand(command.name().substring(4), command.arguments(),
                        command.userId(), command.chatId(), false, command.sourceMessageId())
                : command;
        if (isMutation(effective.name(), effective.arguments()) && !effective.confirmed() && !effective.name().equals("emergencia"))
            return requestConfirmation(effective);
        return transactions.execute(status -> {
            try {
                String message = apply(effective);
                enqueueReply(message, effective);
                return AdminResult.accepted(message);
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
                String message = safeError(effective.name(), ex);
                if (isMutation(effective.name(), effective.arguments())) audit(effective, "REJECTED");
                enqueueReply(message, effective);
                return AdminResult.rejected(message);
            }
        });
    }

    private AdminResult requestConfirmation(AdminCommand command) {
        return transactions.execute(status -> {
            String token = tokens.issue(command.userId(), command.chatId(),
                    new CallbackAction(command.name(), command.arguments()), clock.instant().plus(CONFIRMATION_TTL));
            boolean inline = inlineCallbacksEnabled();
            String message = inline
                    ? "Confirme a operação /" + command.name() + " em até 5 minutos."
                        + "\nFallback: /confirm " + token
                    : "Confirme em até 5 minutos com /confirm " + token;
            if (inline) {
                String cancel = tokens.issue(command.userId(), command.chatId(),
                        new CallbackAction("cancel", List.of(token)), clock.instant().plus(CONFIRMATION_TTL));
                enqueueReply(message, command, List.of(List.of(
                        Map.of("text", "✅ Confirmar", "data", token),
                        Map.of("text", "❌ Cancelar", "data", cancel))));
            } else {
                enqueueReply(message, command, List.of());
            }
            return AdminResult.confirmation(message);
        });
    }

    private String apply(AdminCommand command) {
        String name = command.name();
        List<String> args = command.arguments();
        String message = switch (name) {
            case "menu", "start" -> "⚙️ Administração Hugo do Zap\nEscolha uma opção:";
            case "availability" -> "Disponibilidade: escolha uma duração ou use /ocupado e /disponivel.";
            case "shadow" -> "Shadow Mode: escolha ligar/desligar a simulação. Ações reais seguem bloqueadas.";
            case "emergency_menu" -> "Emergência: ativação imediata, recuperação mediante confirmação.";
            case "cancel" -> cancel(command);
            case "status" -> status();
            case "ocupado" -> setAvailability(AvailabilityState.UNAVAILABLE, minutes(args));
            case "disponivel" -> setAvailability(AvailabilityState.AVAILABLE, minutes(args));
            case "ferias" -> setHolidayOverride(args);
            case "simulacao" -> setSimulation(args);
            case "relatorio" -> report();
            case "ultimas" -> decisions.recent(args);
            case "decisao" -> decisions.detail(args);
            case "emergencia" -> emergency();
            case "recuperar" -> recoverEmergency(args);
            case "perfis" -> profiles();
            case "perfil" -> setProfile(args);
            case "templates" -> templates();
            case "template" -> setTemplate(args);
            case "feriados" -> holidays();
            case "feriado" -> addHoliday(args);
            case "orcamento" -> budget(args);
            default -> help();
        };
        if (isMutation(name, args)) audit(command, "APPLIED");
        return message;
    }

    private String cancel(AdminCommand command) {
        if (command.arguments().size() != 1) return "Cancelamento inválido.";
        return tokens.invalidate(command.arguments().getFirst(), command.userId(), command.chatId())
                ? "Operação cancelada." : "Operação já concluída, expirada ou cancelada.";
    }

    private String status() {
        if (operational == null) return "Configuração operacional indisponível.";
        List<Status> rows = persistence.availability();
        Status state = rows.isEmpty() ? new Status(null, null) : rows.getFirst();
        String override = state.overrideState() == null || state.overrideExpiresAt() == null
                || !state.overrideExpiresAt().isAfter(clock.instant()) ? "sem override ativo"
                : state.overrideState().toLowerCase() + " até " + state.overrideExpiresAt();
        return "Estado: IA " + on(operational.classification() && operational.classificationConfigured())
                + (operational.classification() && !operational.classificationConfigured() ? " (configuration_invalid)" : "")
                + "; kill switch " + on(operational.killSwitch())
                + "; emergência " + on(operational.emergency()) + "; alertas reais " + on(operational.realModeEnabled() && operational.alerts())
                + "; respostas reais " + on(operational.realModeEnabled() && operational.replies()) + "; simulação " + on(operational.simulation())
                + "; pausa de simulação " + on(operational.simulationPaused()) + "; " + override + ".";
    }

    private String setAvailability(AvailabilityState state, int minutes) {
        Instant now = clock.instant();
        persistence.setAvailability(state.name(), now, now.plusSeconds(minutes * 60L));
        return "Disponibilidade " + state.name().toLowerCase() + " por " + minutes + " minutos.";
    }

    private String setHolidayOverride(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /ferias AAAA-MM-DD AAAA-MM-DD.");
        LocalDate first = LocalDate.parse(args.get(0));
        LocalDate last = LocalDate.parse(args.get(1));
        if (first.isBefore(LocalDate.now(clock.withZone(ADMIN_ZONE))) || last.isBefore(first)
                || last.isAfter(first.plusDays(89))) throw new IllegalArgumentException("Período de férias deve começar hoje ou depois e durar no máximo 90 dias.");
        Instant starts = first.atStartOfDay(ADMIN_ZONE).toInstant();
        Instant expires = last.plusDays(1).atStartOfDay(ADMIN_ZONE).toInstant();
        persistence.setHolidayOverride(starts, expires, clock.instant());
        return "Férias registradas de " + first + " a " + last + ".";
    }

    private String setSimulation(List<String> args) {
        if (args.size() != 1) throw new IllegalArgumentException("Use /simulacao on ou /simulacao off.");
        boolean enabled = switch (args.getFirst().toLowerCase()) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /simulacao on ou /simulacao off.");
        };
        persistence.setSimulationPaused(!enabled, clock.instant());
        return enabled ? "Pausa de simulação removida; o ambiente continua determinando se ela pode funcionar." : "Simulação pausada.";
    }

    private String report() {
        if (reports != null) {
            reports.scheduleOnDemand(LocalDate.now(clock.withZone(ADMIN_ZONE)));
            return "Resumo diário de simulação enfileirado para entrega no Telegram.";
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(24));
        Integer decisions = persistence.decisionsSince(cutoff);
        Integer alerts = persistence.alertsSince(cutoff);
        Long reserved = persistence.reservedSince(cutoff);
        return "Últimas 24h: " + decisions + " decisões simuladas, " + alerts + " alertas previstos; orçamento reservado: "
                + (reserved == null ? 0 : reserved) + " micros. Sem conteúdo de conversas.";
    }

    private String emergency() {
        persistence.setEmergency(true, clock.instant());
        return "Emergência ativada: IA, alertas e respostas bloqueados até recuperação explícita.";
    }

    private String recoverEmergency(List<String> args) {
        if (!args.isEmpty()) throw new IllegalArgumentException("Use /recuperar e confirme a operação.");
        persistence.setEmergency(false, clock.instant());
        return "Emergência desativada; os controles do ambiente continuam em vigor.";
    }

    private String profiles() {
        List<String> names = persistence.profileNames();
        return "Perfis: " + String.join(", ", names) + ". Controle: /perfil <chat> <perfil>; alteração exige confirmação.";
    }

    private String setProfile(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /perfil <chat> <perfil>.");
        String chat = args.get(0);
        if (chat.length() > 255 || !chat.matches("[\\p{L}\\p{N}_.:@+-]{3,255}"))
            throw new IllegalArgumentException("Identificador de conversa inválido.");
        Integer knownChat = persistence.knownChat(chat);
        Integer knownProfile = persistence.knownProfile(args.get(1));
        if (knownChat == null || knownChat == 0) throw new IllegalArgumentException("Conversa não encontrada.");
        if (knownProfile == null || knownProfile == 0) throw new IllegalArgumentException("Perfil não encontrado.");
        persistence.setChatProfile(chat, args.get(1), clock.instant());
        return "Perfil da conversa atualizado.";
    }

    private String templates() {
        List<String> entries = persistence.templateNames();
        return "Templates: " + String.join(", ", entries) + ". Controle: /template <id> on|off; alteração exige confirmação. Respostas reais continuam desligadas pelo kill switch.";
    }

    private String setTemplate(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /template <id> on ou /template <id> off.");
        boolean enabled = switch (args.get(1).toLowerCase()) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /template <id> on ou /template <id> off.");
        };
        int updated = persistence.setTemplateEnabled(enabled, args.get(0));
        if (updated != 1) throw new IllegalArgumentException("Template não encontrado.");
        return "Template atualizado; envio real segue protegido pelo kill switch.";
    }

    private String holidays() {
        LocalDate today = LocalDate.now(clock.withZone(ADMIN_ZONE));
        List<String> entries = persistence.holidaysBetween(today, today.plusDays(30));
        return "Próximos feriados: " + (entries.isEmpty() ? "nenhum cadastrado" : String.join("; ", entries))
                + ". Controle: /feriado AAAA-MM-DD BR|SP|LINS https://fonte nome; alteração exige confirmação.";
    }

    private String addHoliday(List<String> args) {
        if (args.size() < 4) throw new IllegalArgumentException("Use /feriado AAAA-MM-DD BR|SP|LINS https://fonte nome.");
        LocalDate date = LocalDate.parse(args.get(0));
        String jurisdiction = args.get(1).toUpperCase();
        if (!List.of("BR", "SP", "LINS").contains(jurisdiction)) throw new IllegalArgumentException("Jurisdição inválida.");
        if (!args.get(2).matches("https://[^\\s]{1,500}")) throw new IllegalArgumentException("Fonte deve ser URL HTTPS.");
        String name = String.join(" ", args.subList(3, args.size())).strip();
        if (name.isBlank() || name.length() > 200) throw new IllegalArgumentException("Nome do feriado inválido.");
        persistence.upsertHoliday(date, jurisdiction, name, args.get(2), clock.instant());
        return "Feriado registrado com atestação manual.";
    }

    private String budget(List<String> args) {
        if (args.isEmpty()) {
            List<Budget> values = persistence.budgetLimits();
            if (values.isEmpty()) return "Limites de orçamento ausentes; chamadas pagas bloqueadas. Controle: /orcamento <diário-micros> <mensal-micros>.";
            Budget value = values.getFirst();
            return "Limite diário: " + value.daily() + " micros; mensal: " + value.monthly() + " micros. Controle: /orcamento <diário-micros> <mensal-micros>; alteração exige confirmação.";
        }
        if (args.size() != 2) throw new IllegalArgumentException("Use /orcamento <diário-micros> <mensal-micros>.");
        long daily = Long.parseLong(args.get(0));
        long monthly = Long.parseLong(args.get(1));
        if (daily < 1 || monthly < 1 || daily > 1_000_000_000_000L || monthly > 1_000_000_000_000L)
            throw new IllegalArgumentException("Limites devem estar entre 1 e 1000000000000 micros.");
        persistence.upsertBudget(daily, monthly, clock.instant());
        return "Limites de orçamento atualizados em micros; nenhuma chamada paga foi iniciada.";
    }

    private void audit(AdminCommand command, String outcome) {
        persistence.audit(command.name(), outcome, command.userId(), command.chatId(), clock.instant());
    }

    private void enqueueReply(String message, AdminCommand command) {
        enqueueReply(message, command, keyboardFor(command));
    }

    private void enqueueReply(String message, AdminCommand command, List<List<Map<String, String>>> keyboard) {
        Instant now = clock.instant();
        String event = command.sourceMessageId() == null ? "telegram_admin_reply" : "telegram_admin_edit";
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("eventType", event);
            payload.put("text", message);
            if (command.sourceMessageId() != null) payload.put("messageId", command.sourceMessageId());
            if (!keyboard.isEmpty()) payload.put("keyboard", keyboard);
            outbox.enqueue(event + ":" + UUID.randomUUID(), event,
                    JSON.writeValueAsString(payload), "[]", now, now.plus(REPLY_TTL));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("could not queue Telegram admin response", ex);
        }
    }

    private boolean inlineCallbacksEnabled() {
        return telegramProperties != null
                && (telegramProperties.isPollingEnabled() || telegramProperties.isWebhookEnabled());
    }

    private List<List<Map<String, String>>> keyboardFor(AdminCommand command) {
        if (!inlineCallbacksEnabled()) return List.of();
        long user = command.userId(), chat = command.chatId();
        Instant expiry = clock.instant().plus(CONFIRMATION_TTL);
        String name = command.name();
        List<List<Map<String, String>>> rows = new java.util.ArrayList<>();
        switch (name) {
            case "menu", "start" -> {
                rows.add(List.of(button("📊 Status", "status", List.of(), user, chat, expiry),
                        button("🕐 Disponibilidade", "availability", List.of(), user, chat, expiry)));
                rows.add(List.of(button("🧪 Shadow Mode", "shadow", List.of(), user, chat, expiry),
                        button("👥 Perfis / conversas", "perfis", List.of(), user, chat, expiry)));
                rows.add(List.of(button("📝 Templates", "templates", List.of(), user, chat, expiry),
                        button("💰 Orçamento", "orcamento", List.of(), user, chat, expiry)));
                rows.add(List.of(button("📈 Relatórios", "relatorio", List.of(), user, chat, expiry),
                        button("🚨 Emergência", "emergency_menu", List.of(), user, chat, expiry)));
                if (telegramProperties != null && telegramProperties.getMiniAppUrl() != null
                        && !telegramProperties.getMiniAppUrl().isBlank())
                    rows.add(List.of(Map.of("text", "🌐 Abrir Mini App",
                            "webAppUrl", telegramProperties.getMiniAppUrl())));
            }
            case "availability" -> rows.add(List.of(
                    button("Ocupado 60 min", "ocupado", List.of("60"), user, chat, expiry),
                    button("Disponível 60 min", "disponivel", List.of("60"), user, chat, expiry)));
            case "shadow" -> rows.add(List.of(
                    button("Simulação ON", "simulacao", List.of("on"), user, chat, expiry),
                    button("Simulação OFF", "simulacao", List.of("off"), user, chat, expiry)));
            case "emergency_menu" -> rows.add(List.of(
                    button("🚨 Ativar emergência", "emergencia", List.of(), user, chat, expiry),
                    button("Recuperar", "recuperar", List.of(), user, chat, expiry)));
            default -> { }
        }
        if (!List.of("menu", "start").contains(name))
            rows.add(List.of(button("⬅️ Menu principal", "menu", List.of(), user, chat, expiry)));
        return rows;
    }

    private Map<String, String> button(String text, String name, List<String> args,
                                       long user, long chat, Instant expiry) {
        String token = tokens.issue(user, chat, new CallbackAction("nav_" + name, args), expiry);
        if (token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64)
            throw new IllegalStateException("Telegram callback_data exceeds 64 bytes");
        return Map.of("text", text, "data", token);
    }

    private TelegramAdminProperties telegramProperties;
    @Autowired(required = false)
    public void configureTelegramMenu(TelegramAdminProperties properties) {
        this.telegramProperties = properties;
    }

    private static boolean isMutation(String name, List<String> args) {
        return List.of("ocupado", "disponivel", "ferias", "simulacao", "emergencia", "recuperar", "perfil", "template", "feriado").contains(name)
                || (name.equals("orcamento") && !args.isEmpty());
    }

    private static int minutes(List<String> args) {
        if (args.size() > 1) throw new IllegalArgumentException("Informe duração em minutos.");
        int minutes = args.isEmpty() ? 60 : Integer.parseInt(args.getFirst());
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Duração deve ser de 1 a 1440 minutos.");
        return minutes;
    }

    private static String safeError(String command, RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return "Não foi possível aplicar /" + command + ".";
        if (ex instanceof IllegalArgumentException && !message.contains("token")) return message;
        return "Não foi possível aplicar /" + command + ".";
    }

    private static String on(boolean value) { return value ? "ligado" : "desligado"; }
    private static String help() {
        return "/menu, /status, /ocupado [minutos], /disponivel [minutos], /ferias <início> <fim>, /simulacao on|off, /relatorio, /ultimas [1-10], /decisao <id>, /emergencia, /recuperar, /perfis, /perfil <chat> <perfil>, /templates, /template <id> on|off, /feriados, /feriado <data> <jurisdição> <fonte-https> <nome>, /orcamento [diário mensal], /confirm <token>.";
    }

}
