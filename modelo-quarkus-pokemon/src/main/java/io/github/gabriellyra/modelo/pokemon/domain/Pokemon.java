package io.github.gabriellyra.modelo.pokemon.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Pokemon com exclusao logica: excluir marca ativo=false e preenche excluidoEm,
 * o registro continua na tabela.
 */
@Entity
@Table(name = "pokemon")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Pokemon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "numero_pokedex", nullable = false)
    private Integer numeroPokedex;

    @Column(name = "nome", nullable = false, length = 50)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_primario", nullable = false, length = 20)
    private TipoPokemon tipoPrimario;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_secundario", length = 20)
    private TipoPokemon tipoSecundario;

    @Column(name = "altura_metros", precision = 5, scale = 2)
    private BigDecimal alturaMetros;

    @Column(name = "peso_kg", precision = 7, scale = 2)
    private BigDecimal pesoKg;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    @Column(name = "excluido_em")
    private LocalDateTime excluidoEm;
}
