CREATE TABLE disponibilidade (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inicio timestamptz NOT NULL,
    fim timestamptz NOT NULL,
    CHECK(fim > inicio)
);

CREATE TABLE agendamento (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    paciente_id bigint NOT NULL REFERENCES paciente (id),
    inicio timestamptz NOT NULL,
    fim timestamptz NOT NULL,
    periodo tstzrange GENERATED ALWAYS AS (tstzrange(inicio, fim, '[)')) STORED,
    status VARCHAR(20) NOT NULL CHECK (status IN ('AGENDADO', 'REALIZADO', 'CANCELADO')),
    valor NUMERIC(12,2),
    confirmacao_enviada_em timestamptz,
    lembrete_enviado_em timestamptz,
    tentativas_envio INTEGER NOT NULL DEFAULT 0,
    criado_por bigint NOT NULL REFERENCES usuario (id),

    CHECK(fim > inicio),
    CONSTRAINT sem_sobreposicao
                         EXCLUDE USING gist (periodo WITH &&) WHERE(status <> 'CANCELADO')
);