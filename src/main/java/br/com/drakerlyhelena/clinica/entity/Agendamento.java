package br.com.drakerlyhelena.clinica.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@NoArgsConstructor
@Getter
@Setter
public class Agendamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paciente_id")
    private Paciente paciente;

    private OffsetDateTime inicio;
    private OffsetDateTime fim;

    @Enumerated(EnumType.STRING)
    private StatusAgendamento status;

    @Column(precision = 12, scale = 2)
    private BigDecimal valor;

    private OffsetDateTime confirmacaoEnviadaEm;
    private OffsetDateTime lembreteEnviadoEm;
    private int tentativasEnvio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "criado_por")
    private Usuario criadoPor;
}