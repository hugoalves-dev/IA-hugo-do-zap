# Revisão P1: canonicalização de pendências e modo import

## Alterações

- A importação agora resolve cada chave pendente pelo alias canônico conhecido no snapshot e no PostgreSQL e agrupa aliases antes da persistência.
- O merge soma contagens com detecção de overflow, preserva menor `firstMessageAt`, maior `lastMessageAt`, prévia mais nova, menor vencimento e maior retenção. Metadados incompatíveis e colisões de alias falham fechados e o APPLY reverte a transação.
- `LegacyImportEnvironmentPostProcessor` força `watcher.runtime.scheduling-enabled=false` e `spring.main.web-application-type=none` quando `watcher.legacy-import.snapshot` está presente, mesmo com flag manual verdadeira.
- Os exemplos README deixam de depender das flags manuais de segurança.

## Verificação

- Teste PostgreSQL com SQLite sintético: dois pendings (alias phone + canonical LID) viram uma linha canônica com contagem e prévia preservadas, visível no join com snapshot sincronizado.
- Teste de contexto: snapshot presente e scheduling manual `true` resultam sem scheduler e ambiente `web-application-type=none`; default normal continua habilitado.
- `mvn "-Dtest=LegacyImportServiceTest,WatcherSchedulingConfigurationTest" test`: 9 testes, 0 falhas/erros (PostgreSQL 17 Testcontainers).
- `git diff --check`: limpo.

Nenhum SQLite legado original, VPS, pairing, envio ou serviço externo foi acessado/iniciado. Os testes usaram somente fixtures temporárias.
