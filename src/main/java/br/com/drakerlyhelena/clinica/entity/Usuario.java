package br.com.drakerlyhelena.clinica.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "usuario")
@NoArgsConstructor
@Getter
@Setter
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String papel;
    private String email;
    private String senhaHash;
}
