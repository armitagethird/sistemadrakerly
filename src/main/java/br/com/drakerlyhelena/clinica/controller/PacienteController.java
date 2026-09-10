package br.com.drakerlyhelena.clinica.controller;

import br.com.drakerlyhelena.clinica.entity.Paciente;
import br.com.drakerlyhelena.clinica.service.PacienteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/pacientes")
@RequiredArgsConstructor
public class PacienteController {

    private final PacienteService pacienteService;

    @GetMapping
    public String listar(Model model) {
        model.addAttribute("pacientes", pacienteService.buscarTodos());
        return "pacientes/lista";
    }

    @GetMapping("/novo")
    public String formularioNovo(Model model) {
        model.addAttribute("paciente", new Paciente());
        return "pacientes/formulario";
    }

    @PostMapping
    public String criar(@Valid @ModelAttribute("paciente") Paciente paciente,
                        BindingResult erros) {
        if (erros.hasErrors()) {
            return "pacientes/formulario";
        }
        pacienteService.salvar(paciente);
        return "redirect:/pacientes";
    }

    @GetMapping("/{id}/editar")
    public String formularioEditar(@PathVariable Long id, Model model) {
        model.addAttribute("paciente", pacienteService.buscarPorId(id));
        return "pacientes/formulario";
    }

    @PostMapping("/{id}")
    public String atualizar(@PathVariable Long id,
                            @Valid @ModelAttribute("paciente") Paciente dados,
                            BindingResult erros) {
        if (erros.hasErrors()) {
            dados.setId(id);
            return "pacientes/formulario";
        }
        pacienteService.atualizar(id, dados);
        return "redirect:/pacientes";
    }

    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id) {
        pacienteService.excluir(id);
        return "redirect:/pacientes";
    }
}
