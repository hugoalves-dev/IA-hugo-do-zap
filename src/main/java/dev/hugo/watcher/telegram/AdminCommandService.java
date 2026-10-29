package dev.hugo.watcher.telegram;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.notification.ReportService;
import dev.hugo.watcher.policy.AvailabilityState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CallbackTokenService tokens;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;
    private final ReportService reports;
    private dev.hugo.watcher.OperationalState operational;
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    @Autowired
    public AdminCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock, ReportService reports) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.tokens = tokens;
        this.outbox = outbox;
        this.clock = clock;
        this.reports = reports;
    }

    public AdminCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock) {
        this(jdbc, manager, tokens, outbox, clock, null);
    }

    public AdminResult execute(AdminCommand command) {
        if (command == null) return AdminResult.rejected("Comando inválido.");
        if (isMutation(command.name(), command.arguments()) && !command.confirmed() && !command.name().equals("emergencia"))
            return requestConfirmation(command);
        return transactions.execute(status -> {
            try {
                String message = apply(command);
                enqueueReply(message);
                return AdminResult.accepted(message);
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
                String message = safeError(command.name(), ex);
                if (isMutation(command.name(), command.arguments())) audit(command, "REJECTED");
                enqueueReply(message);
                return AdminResult.rejected(message);
            }
        });
    }

    private AdminResult requestConfirmation(AdminCommand command) {
        return transactions.execute(status -> {
            String token = tokens.issue(command.userId(), command.chatId(),
                    new CallbackAction(command.name(), command.arguments()), clock.instant().plus(CONFIRMATION_TTL));
            String message = "Confirme em até 5 minutos com /confirm " + token;
            enqueueReply(message);
            return AdminResult.confirmation(message);
        });
    }

    private String apply(AdminCommand command) {
        String name = command.name();
        List<String> args = command.arguments();
        String message = switch (name) {
            case "status" -> status();
            case "ocupado" -> setAvailability(AvailabilityState.UNAVAILABLE, minutes(args));
            case "disponivel" -> setAvailability(AvailabilityState.AVAILABLE, minutes(args));
            case "ferias" -> setHolidayOverride(args);
            case "simulacao" -> setSimulation(args);
            case "relatorio" -> report();
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

    private String status() {
        if (operational == null) return "Configuração operacional indisponível.";
        List<Status> rows = jdbc.query("select state,expires_at from availability_overrides where singleton_id=1",
                (rs, row) -> new Status(rs.getString(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()));
        Status state = rows.isEmpty() ? new Status(null, null) : rows.getFirst();
        String override = state.overrideState() == null || state.overrideExpiresAt() == null
                || !state.overrideExpiresAt().isAfter(clock.instant()) ? "sem override ativo"
                : state.overrideState().toLowerCase() + " até " + state.overrideExpiresAt();
        return "Estado: IA " + on(operational.classification()) + "; kill switch " + on(operational.killSwitch())
                + "; emergência " + on(operational.emergency()) + "; alertas reais " + on(operational.alerts())
                + "; respostas reais " + on(operational.replies()) + "; simulação " + on(operational.simulation())
                + "; pausa de simulação " + on(operational.simulationPaused()) + "; " + override + ".";
    }

    private String setAvailability(AvailabilityState state, int minutes) {
        Instant now = clock.instant();
        jdbc.update("""
                insert into availability_overrides(singleton_id,state,starts_at,expires_at,updated_at)
                values (1,?,?,?,?) on conflict(singleton_id) do update
                    set state=excluded.state,starts_at=excluded.starts_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at
                """, state.name(), Timestamp.from(now), Timestamp.from(now.plusSeconds(minutes * 60L)), Timestamp.from(now));
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
        jdbc.update("""
                insert into availability_overrides(singleton_id,state,starts_at,expires_at,updated_at)
                values (1,'UNAVAILABLE',?,?,?) on conflict(singleton_id) do update
                    set state='UNAVAILABLE',starts_at=excluded.starts_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at
                """, Timestamp.from(starts), Timestamp.from(expires), Timestamp.from(clock.instant()));
        return "Férias registradas de " + first + " a " + last + ".";
    }

    private String setSimulation(List<String> args) {
        if (args.size() != 1) throw new IllegalArgumentException("Use /simulacao on ou /simulacao off.");
        boolean enabled = switch (args.getFirst().toLowerCase()) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /simulacao on ou /simulacao off.");
        };
        jdbc.update("update operational_restrictions set simulation_paused=?,updated_at=? where singleton_id=1", !enabled, Timestamp.from(clock.instant()));
        return enabled ? "Pausa de simulação removida; o ambiente continua determinando se ela pode funcionar." : "Simulação pausada.";
    }

    private String report() {
        if (reports != null) {
            reports.schedule(LocalDate.now(clock.withZone(ADMIN_ZONE)));
            return "Resumo diário de simulação enfileirado para entrega no Telegram.";
        }
        Integer decisions = jdbc.queryForObject("select count(*) from simulation_decisions where decided_at >= ?",
                Integer.class, Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        Integer alerts = jdbc.queryForObject("select count(*) from simulation_decisions where decided_at >= ? and would_alert=true",
                Integer.class, Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        Long reserved = jdbc.queryForObject("select coalesce(sum(reserved_micros),0) from ai_budget_reservations where created_at >= ? and status <> 'RELEASED'",
                Long.class, Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        return "Últimas 24h: " + decisions + " decisões simuladas, " + alerts + " alertas previstos; orçamento reservado: "
                + (reserved == null ? 0 : reserved) + " micros. Sem conteúdo de conversas.";
    }

    private String emergency() {
        jdbc.update("update operational_restrictions set emergency_latched=true,updated_at=? where singleton_id=1", Timestamp.from(clock.instant()));
        return "Emergência ativada: IA, alertas e respostas bloqueados até recuperação explícita.";
    }

    private String recoverEmergency(List<String> args) {
        if (!args.isEmpty()) throw new IllegalArgumentException("Use /recuperar e confirme a operação.");
        jdbc.update("update operational_restrictions set emergency_latched=false,updated_at=? where singleton_id=1", Timestamp.from(clock.instant()));
        return "Emergência desativada; os controles do ambiente continuam em vigor.";
    }

    private String profiles() {
        List<String> names = jdbc.query("select profile_name from policy_profiles order by profile_name", (rs, row) -> rs.getString(1));
        return "Perfis: " + String.join(", ", names) + ". Controle: /perfil <chat> <perfil>; alteração exige confirmação.";
    }

    private String setProfile(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /perfil <chat> <perfil>.");
        String chat = args.get(0);
        if (chat.length() > 255 || !chat.matches("[\\p{L}\\p{N}_.:@+-]{3,255}"))
            throw new IllegalArgumentException("Identificador de conversa inválido.");
        Integer knownChat = jdbc.queryForObject("select count(*) from chat_snapshot where chat_jid=?", Integer.class, chat);
        Integer knownProfile = jdbc.queryForObject("select count(*) from policy_profiles where profile_name=?", Integer.class, args.get(1));
        if (knownChat == null || knownChat == 0) throw new IllegalArgumentException("Conversa não encontrada.");
        if (knownProfile == null || knownProfile == 0) throw new IllegalArgumentException("Perfil não encontrado.");
        jdbc.update("""
                insert into chat_policy_overrides(chat_jid,profile_name,fields,updated_at) values (?,?,'{}'::jsonb,?)
                on conflict(chat_jid) do update set profile_name=excluded.profile_name,updated_at=excluded.updated_at
                """, chat, args.get(1), Timestamp.from(clock.instant()));
        return "Perfil da conversa atualizado.";
    }

    private String templates() {
        List<String> entries = jdbc.query("select template_id || ' v' || version || case when enabled then ' (ativo)' else ' (inativo)' end from response_templates order by template_id,version",
                (rs, row) -> rs.getString(1));
        return "Templates: " + String.join(", ", entries) + ". Controle: /template <id> on|off; alteração exige confirmação. Respostas reais continuam desligadas pelo kill switch.";
    }

    private String setTemplate(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /template <id> on ou /template <id> off.");
        boolean enabled = switch (args.get(1).toLowerCase()) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /template <id> on ou /template <id> off.");
        };
        int updated = jdbc.update("update response_templates set enabled=? where template_id=? and version=(select max(version) from response_templates where template_id=?)",
                enabled, args.get(0), args.get(0));
        if (updated != 1) throw new IllegalArgumentException("Template não encontrado.");
        return "Template atualizado; envio real segue protegido pelo kill switch.";
    }

    private String holidays() {
        LocalDate today = LocalDate.now(clock.withZone(ADMIN_ZONE));
        List<String> entries = jdbc.query("select local_date || ' ' || jurisdiction || ' ' || name || ' [' || validation_status || ']' from holiday_calendar where local_date between ? and ? order by local_date,jurisdiction limit 20",
                (rs, row) -> rs.getString(1), today, today.plusDays(30));
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
        jdbc.update("""
                insert into holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status,optional,updated_at,
                    validated_by,validated_at)
                values (?,?,?,?,?,'VALIDATED',false,?,'telegram-admin',?)
                on conflict(local_date,jurisdiction,name) do update set source=excluded.source,
                    calendar_year=excluded.calendar_year,validation_status='VALIDATED',optional=false,
                    updated_at=excluded.updated_at,validated_by=excluded.validated_by,validated_at=excluded.validated_at
                """, date, jurisdiction, name, args.get(2), date.getYear(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        return "Feriado registrado com atestação manual.";
    }

    private String budget(List<String> args) {
        if (args.isEmpty()) {
            List<Budget> values = jdbc.query("select daily_limit_micros,monthly_limit_micros from ai_budget_limits where singleton_id=1",
                    (rs, row) -> new Budget((Long) rs.getObject(1), (Long) rs.getObject(2)));
            if (values.isEmpty()) return "Limites de orçamento ausentes; chamadas pagas bloqueadas. Controle: /orcamento <diário-micros> <mensal-micros>.";
            Budget value = values.getFirst();
            return "Limite diário: " + value.daily() + " micros; mensal: " + value.monthly() + " micros. Controle: /orcamento <diário-micros> <mensal-micros>; alteração exige confirmação.";
        }
        if (args.size() != 2) throw new IllegalArgumentException("Use /orcamento <diário-micros> <mensal-micros>.");
        long daily = Long.parseLong(args.get(0));
        long monthly = Long.parseLong(args.get(1));
        if (daily < 1 || monthly < 1 || daily > 1_000_000_000_000L || monthly > 1_000_000_000_000L)
            throw new IllegalArgumentException("Limites devem estar entre 1 e 1000000000000 micros.");
        jdbc.update("""
                insert into ai_budget_limits(singleton_id,daily_limit_micros,monthly_limit_micros,updated_at)
                values (1,?,?,?) on conflict(singleton_id) do update set daily_limit_micros=excluded.daily_limit_micros,
                    monthly_limit_micros=excluded.monthly_limit_micros,updated_at=excluded.updated_at
                """, daily, monthly, Timestamp.from(clock.instant()));
        return "Limites de orçamento atualizados em micros; nenhuma chamada paga foi iniciada.";
    }

    private void audit(AdminCommand command, String outcome) {
        jdbc.update("insert into telegram_admin_audit(action,outcome,actor_user_id,actor_chat_id,created_at) values (?,?,?,?,?)",
                command.name(), outcome, command.userId(), command.chatId(), Timestamp.from(clock.instant()));
    }

    private void enqueueReply(String message) {
        Instant now = clock.instant();
        try {
            String payload = JSON.writeValueAsString(Map.of("eventType", "telegram_admin_reply", "text", message));
            outbox.enqueue("telegram_admin_reply:" + UUID.randomUUID(), "telegram_admin_reply", payload, "[]", now, now.plus(REPLY_TTL));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("could not queue Telegram admin response", ex);
        }
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
        return "/status, /ocupado [minutos], /disponivel [minutos], /ferias <início> <fim>, /simulacao on|off, /relatorio, /emergencia, /recuperar, /perfis, /perfil <chat> <perfil>, /templates, /template <id> on|off, /feriados, /feriado <data> <jurisdição> <fonte-https> <nome>, /orcamento [diário mensal], /confirm <token>.";
    }

    private record Status(String overrideState, Instant overrideExpiresAt) { }
    private record Budget(Long daily, Long monthly) { }
}
