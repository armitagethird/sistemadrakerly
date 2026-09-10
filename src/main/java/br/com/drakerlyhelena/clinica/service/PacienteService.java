package br.com.drakerlyhelena.clinica.service;

import br.com.drakerlyhelena.clinica.entity.Paciente;
import br.com.drakerlyhelena.clinica.repository.PacienteRepository;
import jakarta.persistence.EntityNotFoundException;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;


@Transactional
@Service
@RequiredArgsConstructor
public class PacienteService {

    private final PacienteRepository pacienteRepository;

    public List<Paciente> buscarTodos() {
        return pacienteRepository.findAll();
    }

    public Paciente buscarPorId(Long id) {
        return pacienteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Paciente " + id + "não encontrado"));
    }

    public Paciente salvar(Paciente paciente) {
        return pacienteRepository.save(paciente);
    }
public void excluir(Long id) {
    try {
        pacienteRepository.delete(buscarPorId(id));
        pacienteRepository.flush();
    } catch (DataIntegrityViolationException e) {
        throw new IllegalStateException("Paciente possui agendamentos e não pode ser excluído");
    }
}
    @Transactional
    public Paciente atualizar(Long id, Paciente dados) {
        Paciente paciente = buscarPorId(id);
        paciente.setNome(dados.getNome());
        paciente.setTelefone(dados.getTelefone());
        paciente.setEmail(dados.getEmail());
        return paciente;
    }

}




