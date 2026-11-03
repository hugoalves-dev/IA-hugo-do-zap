# Issue #39 — Aposentadoria do watcher TypeScript/Baileys/SQLite

**Status: BLOQUEADO para exclusão do legado até comprovação do cutover e encerramento do rollback.**

Este documento é o procedimento operacional para [#39](https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/39). O repositório, por si só, **não comprova** o estado da VPS. Nenhuma verificação descrita abaixo foi atestada na VPS pela criação deste documento.

## 1. Inventário técnico antes de qualquer exclusão

- Runtime atual versionado: `Dockerfile` executa JAR Java 25; `compose.yaml` tem `watcher` Spring e `evolution-api`; CI em `.github/workflows/backend.yml` executa `mvn -B -ntp verify` e publica/deploya em push de `main`.
- Legado executável separado: `src/*.ts`, `tests/*.ts`, `package.json`, `package-lock.json`, `tsconfig.json` (confirmar arquivos efetivamente presentes antes de remover).
- **Não remover** `src/main/`, `src/test/`, `pom.xml`, `tests/ci/`, Dockerfile, Compose, GH Actions ou scripts de deploy Java.
- **SQLite ainda tem uso no código Java**: `LegacyImportRunner`, `LegacyImportService`, `LegacySqliteSnapshotRepository` e a dependência `org.xerial:sqlite-jdbc` em `pom.xml`. Decidir separadamente quando aposentar a ferramenta de importação de snapshots. Não remover essa dependência apenas por remover o watcher Node.
- Os arquivos de `docs/superpower/`, `docs/superpowers/` e `.superpowers/` são históricos/evidência: preservar com identificação clara, não executá-los como instruções de produção.
- O deploy em `scripts/deploy-vps.sh` contém referências `legacy` para **metadados de bootstrap/rollback do deploy Java**; não removê-las automaticamente por correspondência textual.

## 2. Gates de produção (preencher com evidência, nunca pressupor)

| Gate | Evidência exigida | Estado |
| --- | --- | --- |
| Cutover Java | ID do container, digest GHCR implantado, horário, readiness e versão | PENDENTE |
| Eventos Evolution | Evento novo elegível recebido e processado no Spring, sem conteúdo pessoal em logs | PENDENTE |
| Persistência PostgreSQL | Inbox/job/estado/outbox persistidos e consistentes; migrações Flyway válidas | PENDENTE |
| Telegram | Entrega efetiva de notificação permitida, com confirmação observável e sem disparos de teste indevidos | PENDENTE |
| Políticas | Arquivados, exclusões, janelas e filas seguem comportamento esperado; kill switch preservado | PENDENTE |
| Ausência Node/Baileys | Inventário de processos, containers, serviços systemd, crons, volumes e fluxos sem dependência do watcher antigo | PENDENTE |
| Backups | Backup PostgreSQL + Evolution + dados/sessões legadas identificados, protegidos e com restauração validada | PENDENTE |
| Rollback | Digest anterior, estratégia de restauração e responsável registrados; janela de rollback formalmente encerrada | PENDENTE |

**Regra:** qualquer `PENDENTE` impede a remoção destrutiva e o merge do PR de limpeza. Evidência negativa também bloqueia. Para evitar expor dados, registrar horários, IDs técnicos mínimos, resultados agregados e links internos restritos, nunca tokens, mensagens ou dumps.

### Comandos de inspeção somente leitura (na VPS, pelo operador autorizado)

Usar o diretório de deploy real, normalmente `/docker/ia-hugo-do-zap`. Nunca colar saída de `docker compose config` completo (pode revelar credenciais).

```bash
cd /docker/ia-hugo-do-zap
docker compose --env-file .env ps
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker compose --env-file .env ps -q watcher
docker compose --env-file .env logs --tail=60 watcher
docker image inspect "$(docker inspect --format '{{.Image}}' "$(docker compose --env-file .env ps -q watcher)")" --format '{{json .RepoDigests}}'
systemctl list-units --all --type=service --no-pager
systemctl list-timers --all --no-pager
crontab -l || true
```

A inspeção de logs deve ser feita **sem exportar texto sensível**. Conferir `/actuator/health/readiness` no namespace/rede apropriado, sem expor Actuator publicamente. Queries no PostgreSQL devem ser somente leitura, usando os nomes/tabelas efetivos do schema Flyway e sem consultar payloads pessoais. Confirmar a execução real do fluxo antes de atribuir `PASS`.

## 3. Limpeza versionada, depois dos gates

1. Criar branch específica e comparar com `main`. Excluir **somente** os arquivos TS e testes TS comprovadamente exclusivos do watcher antigo; conferir utilitários compartilhados.
2. Remover `package.json`, lockfile e `tsconfig.json` se não houver consumidores atuais. **Não remover Node do frontend**, se existir.
3. Ajustar `README.md`, `.env.example`, `.dockerignore`, `.gitignore` e scripts somente onde estiverem referenciando o executor TS; conservar histórico de migração e mecanismos Java de importação ainda necessários.
4. Atualizar `tests/ci` quando testes de contrato ainda exigirem frases do README sobre TypeScript como opção de rollback. Preservar todos os testes de contrato de segurança e deploy.
5. Verificar que nenhuma referência a `src/*.ts`, `npm`, `ts-node`, `Baileys` ou `better-sqlite3` sobrevive **no caminho de execução**. Referências históricas podem permanecer se marcadas como históricas.
6. Rodar os gates locais e CI no commit exato:

```bash
python3 -B -m unittest discover -s tests/ci -p 'test*.py'
mvn -B -ntp verify
docker compose --env-file .env.example --profile prod config --quiet
docker build -t ia-hugo-do-zap:issue-39-check .
```

Os testes Java de integração precisam de Docker/Testcontainers. O Compose com `.env.example` valida a estrutura, **não** os segredos nem a saúde da VPS. Guardar resultados e SHA da revisão.

7. Abrir PR para revisão. Merge em `main` **dispara deploy automático na VPS**: somente após aprovação explícita de release, backups e janela operacional definida. O deploy existente atualiza apenas `watcher` e preserva volumes/serviços correlatos.
8. Depois do deploy autorizado, conferir readiness, ingestão Evolution, persistência, outbox, Telegram, políticas, métricas, filas e eventuais regressões de forma não destrutiva. Não declarar sucesso só por HTTP 200.

## 4. Rollback e retenção de dados

- **Não executar** `docker compose down -v`, `--remove-orphans`, `docker system prune`, remoção de volumes, sessões, `watcher.db`, dumps ou bancos durante a limpeza do Git.
- Registrar o digest anterior e manter a opção de restaurar a imagem imutável do watcher Java. O rollback automático do deploy cobre falha inicial de healthcheck, **não** regressões funcionais tardias.
- Não reverter migrations Flyway cegamente, não recriar PostgreSQL e não reprocessar envios WhatsApp com resultado `UNKNOWN`.
- Reter backup isolado do legado segundo política de segurança/privacidade aprovada, com acesso restrito e prazo de expiração definido. Encerrar a janela de rollback por decisão explícita, registrada com data e responsável.
