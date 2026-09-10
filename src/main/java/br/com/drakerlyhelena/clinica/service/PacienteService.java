package br.com.drakerlyhelena.clinica.service;

import br.com.drakerlyhelena.clinica.entity.Paciente;
import br.com.drakerlyhelena.clinica.repository.PacienteRepository;
import jakarta.persistence.EntityNotFoundException;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Transactional
@Service
@RequiredArgsConstructor
public class PacienteService {

    private final PacienteRepository pacienteRepository;

    public List<Paciente> findAll() {
        return pacienteRepository.findAll();
    }

    public Paciente buscarPorId(Long id) {
        return pacienteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Paciente " + id + "não encontrado"));
    }

    }
    public Paciente salvarPaciente(Paciente paciente) {
        return pacienteRepository.save(paciente);
    }
public void excluir(Long id) {
    try {
        SimpleJpaRepository<Object, Object> pacienteRepository;
        pacienteRepository.deleteById(id);
        pacienteRepository.flush();
    } catch (DataIntegrityViolationException e) {
        throw new IllegalStateException("Paciente possui agendamentos e não pode ser excluído");
    }
}



}
