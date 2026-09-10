# CLAUDE.md

Contexto do projeto para o Claude Code. Leia antes de sugerir qualquer coisa.

---

## Como me ajudar neste projeto

**Estou escrevendo este código à mão de propósito.** O objetivo é que eu consiga
explicar cada decisão técnica numa entrevista. Portanto:

- **Não gere arquivos inteiros sem eu pedir.** Prefira responder com o trecho
  relevante e o raciocínio por trás.
- **Explique o "porquê" antes do "como".** Se existe alternativa, diga qual você
  descartou e por quê.
- **Me questione.** Se eu pedir algo que contradiz as decisões abaixo, aponte a
  contradição em vez de simplesmente obedecer.
- **Não introduza dependência, camada ou padrão novo sem justificar** contra a
  seção "Anti-goals".
- Quando eu pedir código, prefira o mínimo que funciona. Nada de abstração
  preventiva.

---

## O que é

Sistema de agendamento para uma clínica médica pequena. **Dois usuários, uma
médica, sem multi-tenancy.** Monólito rodando numa VPS.

Funcionalidades:

1. Cadastro de pacientes (nome, e-mail, telefone — **nenhum dado de saúde**)
2. Calendário interativo onde a médica marca blocos livres e a assistente agenda
   consultas dentro deles
3. Financeiro com gráficos e comparação mês a mês
4. WhatsApp via Evolution API (confirmação no ato + lembrete 24h antes)

---

## Stack

| Camada | Escolha |
|---|---|
| Linguagem | Java 21 |
| Framework | Spring Boot 4.1.1 |
| Banco | PostgreSQL 17 |
| Migrations | Flyway |
| View | Thymeleaf + HTMX (sem build step, sem npm) |
| JS | FullCalendar e Chart.js via CDN |
| Auth | Spring Security, form login + sessão |
| Build | Maven |
| Deploy | jar via systemd; Postgres e Evolution API em Docker; Caddy como proxy/TLS |

### Atenção: é Spring Boot 4, não 3

Os starters mudaram de nome em relação a todo material online (que ainda é
majoritariamente Boot 3):

- `spring-boot-starter-webmvc` (era `spring-boot-starter-web`)
- `spring-boot-starter-flyway` (antes adicionava-se `flyway-core` na mão)
- starters de teste granulares: `spring-boot-starter-data-jpa-test`,
  `-security-test`, `-thymeleaf-test`, `-validation-test`, `-webmvc-test`

Ao traduzir tutorial de Boot 3, esperar essa diferença.

### Dependências (fechadas)

Spring Web (webmvc), Spring Data JPA, PostgreSQL Driver, Flyway
(+ `flyway-database-postgresql`), Spring Security, Thymeleaf, Validation, Lombok,
`thymeleaf-extras-springsecurity6`.

**Ainda falta adicionar:** `spring-boot-testcontainers` +
`testcontainers:postgresql` (escopo `test`) — necessários no teste de integração
da EXCLUDE constraint.

Nada além disso sem discussão.

---

## Schema

```
usuario         (id, email, senha_hash, nome, papel)
paciente        (id, nome, email, telefone, criado_em)
disponibilidade (id, inicio, fim)
agendamento     (id, paciente_id, inicio, fim, periodo, status, valor,
                 confirmacao_enviada_em, lembrete_enviado_em,
                 tentativas_envio, criado_por)
lancamento      (id, data, tipo, valor, descricao, agendamento_id, criado_por)
```

**Não existe tabela `profissional`.** Só há uma médica; a assistente é usuário,
não recurso agendável. Se um dia entrar uma segunda médica: adiciona coluna
nullable, backfill, torna NOT NULL, volta o `profissional_id` na constraint.
Uma migration.

`papel` é `varchar` com `MEDICA` | `ASSISTENTE`. **Não** existe tabela `role`,
`usuario_role` nem tela de permissão.

`lancamento.tipo` é `RECEITA` | `DESPESA` na mesma tabela. Sem plano de contas,
sem categoria hierárquica.

### Lacuna conhecida: a 6ª tabela

O schema acima lista 5 tabelas, mas a seção da Evolution API exige gravar
payload cru de webhook e deduplicar por id de mensagem do WhatsApp — o que
precisa de uma tabela que não está aqui. **Modelar só quando vir o primeiro
payload real**, porque o formato do id e a estrutura do evento são contrato de
sistema externo. Não adivinhar.

### Invariante crítica: sem double booking

```sql
periodo tstzrange GENERATED ALWAYS AS (tstzrange(inicio, fim, '[)')) STORED

CONSTRAINT sem_sobreposicao
    EXCLUDE USING gist (periodo WITH &&) WHERE (status <> 'CANCELADO')
```

O banco garante isso, **não o Java**. Verificação `if (existeConflito())` no
service sofre race condition: duas requisições simultâneas passam pelo check
antes de qualquer uma inserir. O service captura a violação e devolve 409 —
identificando a constraint **pelo nome** `sem_sobreposicao`, já que uma
`DataIntegrityViolationException` pode vir de qualquer constraint da tabela.

Detalhes que fazem essa constraint funcionar:

- **O bound `'[)'`** (início inclusivo, fim exclusivo) é o que permite consultas
  consecutivas: uma que termina 10:00 e outra que começa 10:00 não colidem. Com
  `'[]'` seria impossível agendar horários encostados.
- **`status` precisa ser `NOT NULL`**: `NULL <> 'CANCELADO'` avalia como `NULL`,
  a linha ficaria fora do índice parcial e escaparia da proteção.
- **Não precisa de `btree_gist`**: `tstzrange` tem opclass GiST nativa. A
  extensão só seria necessária se um dia entrasse uma coluna escalar no índice
  (ex.: `profissional_id WITH =`).
- **`periodo` não é mapeada na entidade JPA.** É `GENERATED ALWAYS`; se existisse
  um campo, o Hibernate a incluiria no INSERT e o Postgres recusaria.

### Disponibilidade: sem motor de slots

A médica arrasta no FullCalendar e vira uma linha em `disponibilidade`.
**Não existe** regra recorrente, tabela de bloqueio, exceção nem geração de
slots no backend. A subdivisão visual em intervalos de 30min é config do
FullCalendar (`slotDuration`), problema do front.

Agendar = 2 passos:

1. `SELECT EXISTS (... tstzrange @> tstzrange ...)` — está dentro de um bloco livre?
2. INSERT — a constraint barra sobreposição.

O passo 1 **não é derivável por nome de método nem escrevível em JPQL** (`@>` e
`tstzrange` são específicos do Postgres). Vai precisar de
`@Query(nativeQuery = true)`.

---

## Autorização

A política vive **num lugar só**, o `SecurityFilterChain`:

```java
.requestMatchers("/webhook/**", "/css/**", "/login").permitAll()
.requestMatchers("/financeiro/**", "/api/financeiro/**", "/despesas/**")
    .hasRole("MEDICA")
.anyRequest().authenticated()
```

Fronteira desenhada no **consolidado**, não no ato de receber pagamento:

| | Assistente | Médica |
|---|---|---|
| Calendário, agendar, cancelar | sim | sim |
| Cadastro de paciente | sim | sim |
| Reenviar WhatsApp | sim | sim |
| Ver valor da consulta / marcar como paga | sim | sim |
| Dashboard, gráficos, comparação mensal | **não** | sim |
| Despesas, lançamento avulso, editar valor | **não** | sim |
| Excluir paciente | **não** | sim |

Motivo: a assistente recebe dinheiro na recepção. Bloquear ela de tudo que é
financeiro faz a médica passar a senha dela, e aí não há controle nenhum.

`sec:authorize` no Thymeleaf é **UX, não segurança**. O que protege é o filter
chain.

---

## Convenções

- `spring.jpa.open-in-view=false` (obrigatório)
- `spring.jpa.hibernate.ddl-auto=validate` — schema só via Flyway
- **Escrita** via JPA. **Leitura de dashboard** via `JdbcClient` direto para
  `record` DTO. Sem hydration de entidade em query de agregação.
- Agregação é trabalho de SQL: `date_trunc`, `FILTER (WHERE ...)`, `LAG()`.
  Nunca carregar linhas e somar com Stream.
- Dinheiro é `BigDecimal` / `numeric(12,2)`. Nunca `double`.
- Datas são `timestamptz`. JVM e Postgres em UTC. Converte para
  `America/Fortaleza` só na renderização.
- **Sem camada de DTO no CRUD.** Entidade vai direto para o template Thymeleaf.
  DTO (`record`) só nos 3 endpoints JSON. Sem MapStruct, sem ModelMapper.
- Lombok: `@RequiredArgsConstructor` nos services, `@Getter`/`@Setter` nas
  entidades. **Nunca `@Data` em entidade JPA** (gera `equals`/`hashCode` com
  todos os campos; objeto mutável dentro de `HashSet` "some" da coleção quando
  um campo do hash muda — e o id muda no `save()`).
- Injeção por construtor sempre. Nunca `@Autowired` em campo.

### Convenções de entidade JPA (aprendidas na prática)

- **Id é sempre wrapper (`Long`), nunca primitivo.** `0` não é um id, é ausência
  de id — e genéricos (`JpaRepository<Paciente, Long>`) não aceitam primitivo.
- **`@GeneratedValue(strategy = GenerationType.IDENTITY)` explícito.** O default
  (`AUTO`) resolve para `SEQUENCE` no Postgres e procura uma sequence que o
  Flyway não criou.
- **Sem `@AllArgsConstructor` em entidade.** Gera construtor que recebe `id`,
  que o banco (`GENERATED ALWAYS AS IDENTITY`) recusa. E construtor completo
  junto com `@Setter` é contraditório: a garantia que ele prometeria é desfeita
  pelo setter na linha seguinte.
- **Sem `@Table` / `@Column` redundantes.** A naming strategy do Spring Boot
  converte `Paciente` → `paciente` e `senhaHash` → `senha_hash` sozinha. Cuidado
  com siglas: `pacienteID` vira `paciente_i_d`; escreva `pacienteId`.
- **`@ManyToOne` é EAGER por padrão** — sempre declarar `fetch = LAZY`. Com
  `open-in-view=false`, acessar o proxy no template lança
  `LazyInitializationException`: use fetch join na query quando souber que vai
  precisar.
- **`@JoinColumn` obrigatório quando o nome default não bate.** O default é
  `<campo>_<pk>`: `criadoPor` geraria `criado_por_id`, que não existe.
- **`@Enumerated(EnumType.STRING)` sempre.** O default é `ORDINAL`, que grava o
  índice numérico — reordenar o enum reinterpretaria todas as linhas gravadas.
- **`@PrePersist` para `criado_em`.** `DEFAULT now()` no Postgres só vale quando
  a coluna é omitida do INSERT, e o Hibernate inclui todas as colunas mapeadas
  (mandando `NULL`, que não aciona default). O `DEFAULT` na migration continua
  valendo para escrita feita por fora da aplicação.
- **`enum` no Java e `CHECK` no banco são duas listas mantidas à mão.**
  Adicionar um status exige alterar os dois — e o segundo é uma migration.

---

## Evolution API

**Nunca chamar a Evolution dentro da transação de criar agendamento.** Se ela
estiver lenta ou fora, segura a conexão do pool e o usuário toma timeout num
agendamento que deveria ter sido salvo.

O próprio `agendamento` é o outbox (colunas `confirmacao_enviada_em`,
`lembrete_enviado_em`, `tentativas_envio`). **Não criar tabela de outbox
separada.**

`@Scheduled` de 5 em 5 minutos:

```sql
SELECT * FROM agendamento
WHERE status = 'AGENDADO'
  AND lembrete_enviado_em IS NULL
  AND tentativas_envio < 5
  AND inicio BETWEEN now() + interval '23 hours' AND now() + interval '25 hours'
FOR UPDATE SKIP LOCKED;
```

- `RestClient` com timeout explícito: 3s conexão, 10s leitura.
- Após 5 tentativas marca falha. O reset é **manual**, pelo botão "Reenviar"
  (`UPDATE agendamento SET tentativas_envio = 0 WHERE id = ?`). Reset automático
  recriaria o loop infinito que o contador existe para impedir.
- **Zerar `tentativas_envio` junto com o sucesso do envio.** Senão o lembrete
  herda o contador gasto pela confirmação e pode nunca ser tentado.
- O estado "falhou definitivamente" é derivável
  (`tentativas_envio >= 5 AND lembrete_enviado_em IS NULL`) — não precisa de
  coluna nem de status novo. **Nunca criar `status = 'FALHA_ENVIO'`**: o `status`
  descreve o agendamento, não a mensagem. Uma consulta cujo WhatsApp falhou
  continua agendada.
- Webhook de entrada: valida token no path, grava payload cru, responde `202`
  imediatamente, processa depois. Dedupe pelo id da mensagem do WhatsApp.
- Duas mensagens fixas no total. Sem engine de template, sem tela de
  configuração de mensagem.
- Throttle nos envios: a Evolution usa conexão não-oficial do WhatsApp e rajada
  de mensagem leva a ban de número.

### Lacuna conhecida: backoff não é possível com o schema atual

`tentativas_envio` diz **quantas** vezes falhou, nunca **quando** foi a última.
Backoff exponencial exigiria `ultima_tentativa_em`. O que existe hoje é retry em
intervalo fixo de 5 min (a cadência do `@Scheduled`), 5 vezes, numa janela de 25
minutos — provavelmente suficiente, dada a janela de lembrete de 23–25h. Decidir
conscientemente: ou aceita e corrige a palavra "backoff", ou adiciona a coluna.

---

## Restrições da VPS

Orçamento aproximado de RAM (VPS de 4 GB):

| | RSS |
|---|---|
| Spring Boot | 350–450 MB |
| PostgreSQL | 250–350 MB |
| Evolution API | 300–600 MB (cresce) |
| Caddy + SO | ~420 MB |

Toda dependência nova custa RAM, startup e superfície de ataque.

**Esse orçamento é da VPS, não da máquina de desenvolvimento** (32 GB). Não
otimizar RAM em dev.

JVM:

```
-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=128m
-Duser.timezone=UTC
```

`UseSerialGC` é intencional: com heap de ~400 MB e 2 vCPU, o G1 gasta mais em
estruturas auxiliares e threads de GC do que compensa.

`spring.threads.virtual.enabled=true` (o gargalo é I/O, não CPU) — **exige Java
21**; em 17 é ignorado em silêncio.

Postgres: `shared_buffers=256MB`, `effective_cache_size=768MB`, `work_mem=8MB`,
`random_page_cost=1.1` (SSD), `max_connections=50`. Hikari
`maximum-pool-size=10` (que já é o default do HikariCP — não precisa declarar).

---

## Anti-goals

Não sugerir, a menos que eu peça explicitamente e com justificativa:

Redis · Kafka/RabbitMQ · microsserviços · Kubernetes · Elasticsearch · JWT ·
Spring Data REST · Actuator · DevTools · Quartz · MapStruct · ModelMapper ·
Spring Session · GraalVM native image · npm/webpack/qualquer build de front ·
SPA · tabela de roles · soft delete genérico · repositório genérico
`BaseRepository<T>` · service interface + impl com uma implementação só ·
plano de contas · multi-tenancy · auditoria com Envers · `@Repository` em
interface que já estende `JpaRepository`.

Também fora de escopo: tela de cadastro de usuário, recuperação de senha,
prontuário, anamnese, upload de arquivo, relatório em PDF.

---

## Testes

Não testar getter. Cobertura não é a métrica.

Dois testes que importam:

1. **Agendamento**: dentro da disponibilidade, sem conflito, e a constraint
   barrando sobreposição. Teste de integração com **Testcontainers**.
2. **Agregado financeiro**: a query mensal com `FILTER` e `LAG`.

H2 não serve: não tem `tstzrange` nem `EXCLUDE` constraint. Testar a regra mais
crítica do sistema num banco que não suporta a regra é teatro.

**Testcontainers exige Docker** — ver pendência de virtualização abaixo.

`@SpringBootTest` procura a `@SpringBootConfiguration` subindo os packages a
partir do package do teste. A estrutura de `src/test` tem que espelhar a de
`src/main`.

---

## Ordem de construção

1. Flyway + entidades + CRUD de paciente
2. Disponibilidade + agendamento + a EXCLUDE constraint
3. FullCalendar plugado nos endpoints JSON
4. Financeiro (uma query, Chart.js)
5. Evolution API (por último — o sistema funciona sem ela)

---

## Pegadinhas conhecidas

- `@PreAuthorize` exige `@EnableMethodSecurity`. Sem isso é **ignorado
  silenciosamente** e o método executa normal.
- **Padrão geral: em Spring e JPA, comportamento é ativado por anotação, e a
  ausência dela quase nunca gera erro — gera silêncio.** `@Entity` faltando
  numa classe com `@Table`, `@PrePersist` faltando num método de callback,
  propriedade YAML mal indentada: todos falham calados.
- `hasRole("MEDICA")` procura a authority `ROLE_MEDICA`. Ou grava com prefixo no
  `UserDetails`, ou usa `hasAuthority("MEDICA")`. Escolher um.
- Chamada interna (`this.metodo()`) não passa pelo proxy, então `@PreAuthorize`
  e `@Transactional` não são avaliados. Mesma armadilha nos dois.
- CSRF fica **ligado**. HTMX precisa mandar o token:
  `<body hx-headers='{"X-CSRF-TOKEN": "[[${_csrf.token}]]"}'>`
- `/webhook/**` fora do CSRF: `.csrf(c -> c.ignoringRequestMatchers("/webhook/**"))`
- Sem `thymeleaf-extras-springsecurity6`, o `sec:authorize` é ignorado e a
  assistente vê o link do financeiro.
- **YAML não aceita tab**, e chave mal indentada vira propriedade desconhecida —
  que o Spring ignora sem warning. Quando uma config "não pega", suspeitar da
  indentação antes de qualquer outra coisa.
- **Placeholder não resolvido em `spring.datasource.*` não explode** — o binding
  de `@ConfigurationProperties` passa o texto cru adiante. Um `${DB_PASSWORD}`
  não resolvido chega ao driver como senha literal e vira erro de autenticação,
  não erro de configuração. (Diferente de `@Value`, que aí sim lança
  `Could not resolve placeholder`.)
- **O database da aplicação pertence ao Flyway.** Nenhum DDL manual nele. Para
  rascunhar SQL à mão, usar um database separado — senão a próxima migration
  falha com `relation already exists`.
- Migration já aplicada **nunca se edita**: o Flyway grava um checksum e se
  recusa a subir. Errou? Nova migration. Em dev, dropar o volume/banco.
- Nome de migration precisa de **dois underscores**: `V1__descricao.sql`. Com um
  só, o arquivo é ignorado em silêncio.

---

## Operação

- `pg_dump` diário via cron para object storage, retenção 30 dias. **Testar o
  restore.**
- `mem_limit` em cada container do compose (evita a Evolution derrubar o Postgres
  via OOM killer) + swap de 2 GB no host.
- Deploy: `scp app.jar vps:/opt/clinica/ && systemctl restart clinica`

## LGPD

Nome, e-mail e telefone são dado pessoal comum, não sensível. Requisitos:
HTTPS, não logar telefone em log de aplicação, exclusão de paciente que
realmente apaga. Só isso.

**Atenção:** as FKs de `agendamento` estão sem `ON DELETE`, então apagar um
paciente com agendamentos falha. Isso é proposital — `CASCADE` apagaria
histórico financeiro em silêncio. A decisão de o que fazer nesse caso pertence
ao código de exclusão, e ainda não foi tomada.

---

# Estado atual

_Atualizado em 2026-09-09._

## Ambiente de desenvolvimento

- **Windows 11**, 32 GB RAM. IntelliJ.
- **PostgreSQL 17 nativo**, serviço `postgresql-x64-17`. Sobe com
  `net start postgresql-x64-17` (admin). Estava em StartType `Manual`.
- Database: **`sistemadrakerly`** (não `clinica`). Usuário `postgres`.
- **pgAdmin** instalado e conectado.
- **Docker NÃO funciona**: Docker Desktop falha por virtualização. A BIOS está
  correta (AMD-V habilitado, Ryzen 7 5700X); o que falta são as features do
  Windows — `VirtualMachinePlatform` e WSL (`wsl` retorna
  `REGDB_E_CLASSNOTREG`). Pendente: habilitar via `dism`, reiniciar,
  `wsl --update`. **Bloqueia o passo 2 dos testes (Testcontainers).**
- Senha do banco em `.env` na raiz (no `.gitignore`), lido via
  `spring.config.import: optional:file:.env[.properties]`.
- **Projeto não é repositório git ainda** (`git init` pendente).

## Feito

- `pom.xml`: `artifactId` `clinica`, package `br.com.drakerlyhelena.clinica`,
  classe `ClinicaApplication`.
- `application.yaml`: datasource, `open-in-view: false`, `ddl-auto: validate`,
  `threads.virtual.enabled: true`, `spring.config.import` do `.env`,
  `logging.level.org.hibernate.SQL: DEBUG`.
- **V1** `criar_usuario_e_paciente.sql` — aplicada.
- **V2** `criar_disponibilidade_e_agendamento.sql` — aplicada, com a coluna
  gerada `periodo` e a constraint `sem_sobreposicao`.
- Entidades em `entity/`: `Paciente`, `Usuario`, `Agendamento`,
  `Disponibilidade`, `StatusAgendamento` (enum).
- Packages criados: `controller`, `entity`, `repository`, `service` (organização
  por camada, decisão consciente sobre package-by-feature).

## Próximo passo

Passo 1 da ordem de construção: **`PacienteRepository`**, depois service e
controller do CRUD de paciente.

Criar repository só quando houver consumidor — interface vazia sem uso é
abstração especulativa.

## Pendências

- [ ] `<java.version>` no pom ainda é **17**; a JVM que roda é 21. Subir para 21
      (o `threads.virtual.enabled` depende disso).
- [ ] `DemoApplicationTests` ainda em `br.com.drakerlyhelena.demo` — vai quebrar
      no `mvn test`. Mover para `...clinica` e renomear.
- [ ] Import `lombok.AllArgsConstructor` sem uso em `Usuario`.
- [ ] `Usuario.papel` é `String`; deveria ser enum `Papel` como
      `StatusAgendamento` — e é mais crítico, porque alimenta o Spring Security.
- [ ] `@Table` / `@Column` redundantes em `Paciente` e `Usuario` (ausentes em
      `Agendamento` e `Disponibilidade` — padronizar um dos dois estilos).
- [ ] Rodar os três testes manuais da EXCLUDE constraint no pgAdmin (sobreposto
      deve falhar, encostado deve passar, cancelado deve liberar).
- [ ] Adicionar Testcontainers ao pom (depois que o Docker funcionar).
- [ ] `git init`.
