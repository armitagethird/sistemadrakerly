package br.com.drakerlyhelena.clinica.repository;

import br.com.drakerlyhelena.clinica.entity.Paciente;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PacienteRepository extends JpaRepository<Paciente, Long> {
}